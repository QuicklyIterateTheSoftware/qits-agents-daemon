package eu.wohlben.qits.agentsdaemon;

import eu.wohlben.qits.agents.AgentAuthStatus;
import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.agents.AgentSessionStore;
import eu.wohlben.qits.agents.AgentTranscriptService;
import eu.wohlben.qits.agents.AgentTranscriptTailService;
import eu.wohlben.qits.agents.CommandsAgentCommands;
import eu.wohlben.qits.agents.LocalProcessExecutor;
import eu.wohlben.qits.agentsdaemon.protocol.AgentImage;
import eu.wohlben.qits.commands.ActionResolver;
import eu.wohlben.qits.commands.CheckoutContext;
import eu.wohlben.qits.commands.CommandLifecycleService;
import eu.wohlben.qits.commands.CommandLogService;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandService;
import eu.wohlben.qits.commands.CommandStore;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Wires the container's parts and starts them, in order: the checkout (provisioned once), the
 * harness library over it, the hook webhook, the {@link AgentKeeper}, the {@link MainFollower}'s
 * backstop, the {@link OriginPush} cycle, the {@link UsageScanner} and the event stream.
 *
 * <p>A container missing its environment logs what is missing and idles rather than dying: the
 * runner would only restart it into the same state.
 */
@ApplicationScoped
public class AgentDaemon {

  private static final Logger LOG = Logger.getLogger(AgentDaemon.class);

  static final long FOLLOW_BACKSTOP_MS = 60_000;
  static final long PUSH_POLL_MS = 10_000;
  static final long PROVISION_RETRY_MS = 30_000;
  static final long TERM_GRACE_MS = 5_000;

  /** The {@link SessionLedger}, on the work volume (kept across containers), beside the checkout. */
  static final String SESSION_LEDGER = ".qits-agents-daemon/sessions";

  @Inject Vertx vertx;

  private final ScheduledExecutorService boot = worker("agents-daemon-boot");
  private final ScheduledExecutorService follow = worker("agents-daemon-follow");
  private final ScheduledExecutorService push = worker("agents-daemon-push");
  private final ScheduledExecutorService keep = worker("agents-daemon-keeper");
  private final ScheduledExecutorService usage = worker("agents-daemon-usage");

  private volatile CommandRegistry registry;
  private volatile AgentTranscriptTailService tail;
  private volatile HookWebhook hooks;
  private volatile AgentKeeper keeper;
  private volatile EventFollower events;

  void start() {
    DaemonConfig config = DaemonConfig.from(System.getenv());
    List<String> missing = config.missing();
    LOG.infof("qits-agents-daemon %s starting", AgentImage.VERSION);
    if (!missing.isEmpty()) {
      LOG.errorf("Not starting: the container's environment lacks %s", missing);
      return;
    }
    LOG.infof(
        "Agent %s of project %s, checkout %s", config.agentName(), config.project(), config.checkout());
    Git git = new Git();
    Checkout checkout = new Checkout(git, config.checkout(), config.wrapper(), config.gitBase());
    Provisioner provisioner = new Provisioner(git, checkout);
    boot.execute(() -> provisionThenRun(config, git, checkout, provisioner));
  }

  private void provisionThenRun(DaemonConfig config, Git git, Checkout checkout, Provisioner provisioner) {
    String failed = provisioner.provision();
    if (failed != null) {
      LOG.warnf("Provisioner: %s; trying again in %d s", failed, PROVISION_RETRY_MS / 1000);
      boot.schedule(
          () -> provisionThenRun(config, git, checkout, provisioner),
          PROVISION_RETRY_MS,
          TimeUnit.MILLISECONDS);
      return;
    }
    run(config, git, checkout);
  }

  private void run(DaemonConfig config, Git git, Checkout checkout) {
    HttpClient http = HttpAgentsDoors.client();
    MainFollower follower = new MainFollower(git, checkout);
    OriginPush pusher = new OriginPush(git, checkout);

    ProjectAgentDefaults defaults = new ProjectAgentDefaults(config.domain());
    CommandStore store = new CommandStore();
    CommandLogService logs = new CommandLogService(store, null);
    CommandLifecycleService lifecycle = new CommandLifecycleService(store, () -> {});
    CommandRegistry commandRegistry = new CommandRegistry(config.checkout(), TERM_GRACE_MS);
    registry = commandRegistry;
    CheckoutContext context =
        new CheckoutContext() {
          @Override
          public String branch() {
            return Checkout.MAIN;
          }

          @Override
          public String commitHash() {
            Git.Result head = git.in(config.checkout(), "rev-parse", "HEAD");
            return head.ok() ? head.line() : "";
          }
        };
    CommandService commandService =
        new CommandService(store, commandRegistry, lifecycle, logs, context, NO_ACTIONS);
    AgentTranscriptService transcripts =
        new AgentTranscriptService(store, logs, new AgentSessionStore(), config.claudeMount(), () -> {});
    AgentTranscriptTailService transcriptTail = new AgentTranscriptTailService(transcripts, logs, 500);
    transcriptTail.start();
    tail = transcriptTail;
    AgentAuthStatus authStatus =
        new AgentAuthStatus(new LocalProcessExecutor(), config.claudeMount(), config.checkout());
    AgentCommands agentCommands = new CommandsAgentCommands(commandService, commandRegistry, store);
    AgentLaunchService launchService =
        new AgentLaunchService(
            agentCommands,
            authStatus,
            transcripts,
            transcriptTail,
            defaults,
            new AgentMcp(config.platformMcpUrl(), config.token()),
            context,
            config.claudeMount(),
            config.hooksPort());

    AgentsDoors doors =
        new HttpAgentsDoors(http, config.serviceUrl(), config.project(), config.agentName(), config.token());
    SessionLedger ledger = SessionLedger.at(config.workspace().resolve(SESSION_LEDGER));
    AgentKeeper keeper =
        new AgentKeeper(
            doors,
            new LibraryHarness(launchService, defaults, store, commandRegistry, agentCommands, config.claudeMount()),
            (step, delayMs) -> keep.schedule(guarded("keeper", step), delayMs, TimeUnit.MILLISECONDS),
            System::currentTimeMillis,
            AgentKeeper.Timing.DEFAULT,
            config.project(),
            config.agentName(),
            config.wrapper(),
            follower::takeDetail,
            ledger);
    this.keeper = keeper;

    HookWebhook webhook = new HookWebhook(vertx, config.hooksPort(), keeper::activity, launchService::onActivity);
    webhook.start();
    hooks = webhook;
    keeper.start();

    follow.scheduleWithFixedDelay(guarded("follower", follower::backstop), 0, FOLLOW_BACKSTOP_MS, TimeUnit.MILLISECONDS);
    push.scheduleWithFixedDelay(guarded("push", pusher::cycle), PUSH_POLL_MS, PUSH_POLL_MS, TimeUnit.MILLISECONDS);

    UsageScanner scanner =
        new UsageScanner(
            Path.of(config.claudeMount(), ".claude", "projects"),
            config.project(),
            () -> {
              // The running session, and the earlier ones of its generation.
              Set<String> sessions = new LinkedHashSet<>(ledger.sessionsAt(keeper.generation()));
              String current = keeper.sessionId();
              if (current != null) {
                sessions.add(current);
              }
              return sessions;
            },
            doors);
    usage.scheduleWithFixedDelay(
        guarded("usage", scanner::tick), UsageScanner.POLL_MS, UsageScanner.POLL_MS, TimeUnit.MILLISECONDS);

    RepositoryNames names = new RepositoryNames(http, config.projectsUrl(), config.token());
    EventFollower follower0 =
        new EventFollower(
            http,
            config.eventsUrl(),
            config.token(),
            keeper::projectId,
            config.agentName(),
            names,
            new EventFollower.Listener() {
              @Override
              public void connected() {
                // The catch-up: whatever moved while the stream was down.
                follow.execute(guarded("follower", follower::backstop));
                keeper.refreshSoon();
              }

              @Override
              public void commit(String repoName, String branch) {
                follow.execute(guarded("follower", () -> follower.onCommit(repoName, branch)));
              }

              @Override
              public void agentChanged() {
                keeper.refreshSoon();
              }
            });
    follower0.start();
    events = follower0;
    LOG.info("qits-agents-daemon running");
  }

  private static Runnable guarded(String what, Runnable step) {
    return () -> {
      try {
        step.run();
      } catch (RuntimeException e) {
        LOG.warnf(e, "%s step failed; the next one runs as scheduled", what);
      }
    };
  }

  private static ScheduledExecutorService worker(String name) {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread thread = new Thread(r, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  /** The agent runs no declared actions: commands here are its own session, nothing else. */
  private static final ActionResolver NO_ACTIONS =
      new ActionResolver() {
        @Override
        public Optional<ResolvedAction> resolve(String actionId) {
          return Optional.empty();
        }

        @Override
        public List<ResolvedAction> actions() {
          return List.of();
        }
      };

  @PreDestroy
  void stop() {
    // First: once the sessions are being terminated, nothing typed into them may be acknowledged.
    AgentKeeper k = keeper;
    if (k != null) {
      k.stop();
    }
    EventFollower e = events;
    if (e != null) {
      e.stop();
    }
    HookWebhook h = hooks;
    if (h != null) {
      h.close();
    }
    AgentTranscriptTailService t = tail;
    if (t != null) {
      t.close();
    }
    CommandRegistry r = registry;
    if (r != null) {
      r.terminateAll();
    }
    boot.shutdownNow();
    follow.shutdownNow();
    push.shutdownNow();
    keep.shutdownNow();
    usage.shutdownNow();
  }
}
