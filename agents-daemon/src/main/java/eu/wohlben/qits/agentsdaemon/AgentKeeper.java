package eu.wohlben.qits.agentsdaemon;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentLaunchRequest;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfiguration;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import eu.wohlben.qits.commands.GuardedInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The agent's one kept session: launched, relaunched, replaced on a new generation, fed its turns,
 * and reported — all against qits-agents-service's doors.
 *
 * <ul>
 *   <li><b>Launch.</b> {@code launch} is read at start, every 30 s, and on every {@code
 *       ProjectAgentChanged} for this agent. The session is an interactive, {@code
 *       REPOSITORY}-scoped launch of surface {@code project.agent} in {@code /workspace/<wrapper>},
 *       with the launch's model and effort, and an appended system prompt of {@link OverseerBrief}
 *       then the launch's {@code brief}.
 *   <li><b>Crash.</b> The session exits while the generation is unchanged: relaunched on {@code
 *       SessionLadder}'s ladder — 5 s doubling to 5 min, reset after 10 min up, never giving up —
 *       resuming {@code launch.sessionId} (else the last session seen) when its transcript is on
 *       the credential volume.
 *   <li><b>New generation.</b> {@code launch.generation} exceeds the running one: the session is
 *       ended and a <em>fresh</em> one launched (no resume); the pending turns follow it, RECOVERY
 *       first.
 *   <li><b>Turns</b> are read every 10 s and right after a launch, queued (FIFO of 16, RECOVERY
 *       first, then by {@code createdAt}) and typed one at a time at the session's next idle, as
 *       {@code FrontDeskInbox} does; each is acknowledged with {@code delivered} once typed.
 *   <li><b>Report</b>: STARTING at launch, IDLE/BUSY from the harness hooks, EXITED or FAILED on
 *       exit, always with the running generation and the session id.
 *   <li><b>Retired</b> ({@code launch} answers 409): the session is stopped and the keeper idles,
 *       still reading {@code launch} in case the agent is declared again.
 * </ul>
 *
 * <p><b>Threading.</b> Every step runs on the one {@link Scheduler} thread; hook activity arrives on
 * the webhook's thread and is handed over.
 */
final class AgentKeeper {

  private static final Logger LOG = Logger.getLogger(AgentKeeper.class);

  /** The inbox bound, as FrontDeskInbox's. */
  static final int CAPACITY = 16;

  /** The longest detail a report carries. */
  static final int DETAIL_LIMIT = 200;

  /** Cadences and the ladder, in milliseconds. */
  record Timing(
      long initialBackoffMs,
      long maxBackoffMs,
      long resetAfterMs,
      long launchPollMs,
      long turnsPollMs,
      long watchMs,
      long startGraceMs) {

    static final Timing DEFAULT = new Timing(5_000, 300_000, 600_000, 30_000, 10_000, 2_000, 30_000);
  }

  /** What one launch asks the harness library for. */
  record Plan(AgentLaunchRequest request, AgentSurfaceConfiguration configuration, int generation) {}

  /** What the keeper needs of the harness library, behind one seam a test can fake. */
  interface Harness {

    /** Launches the session; answers its command id. Throws when it did not launch. */
    String launch(Plan plan);

    boolean running(String commandId);

    Optional<Integer> exitCode(String commandId);

    /** The harness session id the command reported, once it has. */
    Optional<String> sessionId(String commandId);

    /** Whether {@code sessionId}'s transcript is on the credential volume, so it can be resumed. */
    boolean transcriptExists(String sessionId);

    boolean terminate(String commandId);

    /** Types {@code text} and Enter into the session, unless a person left a draft there. */
    GuardedInput type(String commandId, String text);
  }

  /** Runs a step later, on the keeper's one thread. */
  interface Scheduler {
    void schedule(Runnable step, long delayMs);
  }

  private final AgentsDoors doors;
  private final Harness harness;
  private final Scheduler scheduler;
  private final LongSupplier clock;
  private final Timing timing;
  private final String project;
  private final String name;
  private final String wrapper;
  private final Supplier<String> pendingDetail;

  // --- keeper-thread state ---------------------------------------------------------------------
  private volatile AgentsDoors.Launch launch;
  private boolean retired;
  private volatile String commandId;
  private int runningGeneration = -1;
  private String sessionId;
  private long launchedAt;
  private long backoffMs;
  /** Bumped on every launch and stop, so a relaunch scheduled before is void. */
  private long epoch;
  private boolean relaunchPending;
  private String activity;
  private long typedAt = -1;
  private final List<AgentsDoors.Turn> queue = new ArrayList<>();
  /** Typed into a session; acknowledged or still owed an acknowledgement. */
  private final Set<UUID> typed = new HashSet<>();
  private final Set<UUID> acknowledged = new HashSet<>();
  private String lastReport;

  AgentKeeper(
      AgentsDoors doors,
      Harness harness,
      Scheduler scheduler,
      LongSupplier clock,
      Timing timing,
      String project,
      String name,
      String wrapper,
      Supplier<String> pendingDetail) {
    this.doors = doors;
    this.harness = harness;
    this.scheduler = scheduler;
    this.clock = clock;
    this.timing = timing;
    this.project = project;
    this.name = name;
    this.wrapper = wrapper;
    this.pendingDetail = pendingDetail == null ? () -> null : pendingDetail;
    this.backoffMs = timing.initialBackoffMs();
  }

  // --- entry points, from any thread -----------------------------------------------------------

  /** Starts the three loops: launch every 30 s, turns every 10 s, the session's exit every 2 s. */
  void start() {
    scheduler.schedule(this::refreshLoop, 0);
    scheduler.schedule(this::turnsLoop, timing.turnsPollMs());
    scheduler.schedule(this::watchLoop, timing.watchMs());
  }

  /** Reads {@code launch} again soon: a {@code ProjectAgentChanged} for this agent arrived. */
  void refreshSoon() {
    scheduler.schedule(this::refresh, 0);
  }

  /** A hook reported the session's activity. */
  void activity(String commandId, String state, String sessionId) {
    scheduler.schedule(() -> onActivity(commandId, state, sessionId), 0);
  }

  /** The project id {@code launch} names; null before the first answer. */
  String projectId() {
    AgentsDoors.Launch current = launch;
    return current == null || current.projectId() == null ? null : current.projectId().toString();
  }

  /** The command id of the running session, or null. */
  String commandId() {
    return commandId;
  }

  // --- the loops -------------------------------------------------------------------------------

  private void refreshLoop() {
    refresh();
    scheduler.schedule(this::refreshLoop, timing.launchPollMs());
  }

  private void turnsLoop() {
    pollTurns();
    scheduler.schedule(this::turnsLoop, timing.turnsPollMs());
  }

  private void watchLoop() {
    watch();
    scheduler.schedule(this::watchLoop, timing.watchMs());
  }

  // --- keeper-thread steps ---------------------------------------------------------------------

  /** Reads {@code launch} and converges: launch, replace on a new generation, or stop if retired. */
  void refresh() {
    if (!readLaunch()) {
      return;
    }
    if (retired) {
      return;
    }
    if (commandId != null && launch.generation() > runningGeneration) {
      LOG.infof(
          "AgentKeeper: generation %d replaces %d; ending session %s and launching a fresh one",
          launch.generation(), runningGeneration, commandId);
      stopSession();
      launchNow(true);
    } else if (commandId == null && runningGeneration >= 0 && launch.generation() > runningGeneration) {
      // Waiting out a backoff when the generation moved: the new one starts now, fresh.
      launchNow(true);
    } else if (commandId == null && !relaunchPending) {
      launchNow(false);
    }
  }

  /** Reads {@code launch}. False when the door did not answer usefully (nothing changes then). */
  private boolean readLaunch() {
    AgentsDoors.Launch next;
    try {
      next = doors.launch();
    } catch (AgentsDoors.Retired e) {
      onRetired(e.getMessage());
      return false;
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: launch not read: %s", e.getMessage());
      return launch != null && !retired;
    }
    if (retired) {
      LOG.infof("AgentKeeper: the agent is declared again at generation %d", next.generation());
      retired = false;
    }
    launch = next;
    return true;
  }

  private void onRetired(String why) {
    if (!retired) {
      LOG.infof("AgentKeeper: the agent is retired (%s); stopping its session and idling", why);
    }
    retired = true;
    epoch++;
    relaunchPending = false;
    queue.clear();
    if (commandId != null) {
      stopSession();
      report(AgentsDoors.Report.EXITED, "retired");
    }
  }

  /** Ends the running session; its exit is not a crash, so nothing relaunches it. */
  private void stopSession() {
    String ending = commandId;
    epoch++;
    commandId = null;
    activity = null;
    typedAt = -1;
    try {
      harness.terminate(ending);
    } catch (RuntimeException e) {
      LOG.warnf("AgentKeeper: could not end session %s: %s", ending, e.getMessage());
    }
  }

  /** Launches the session: fresh, or resuming what {@code launch} (else the last session) names. */
  private void launchNow(boolean fresh) {
    if (launch == null || retired) {
      return;
    }
    epoch++;
    relaunchPending = false;
    String resume = null;
    if (!fresh) {
      String candidate = launch.sessionId() != null ? launch.sessionId() : sessionId;
      if (candidate != null && transcriptPresent(candidate)) {
        resume = candidate;
      }
    }
    int generation = launch.generation();
    Plan plan = new Plan(request(resume), configuration(launch), generation);
    if (fresh) {
      // Whatever was queued was for the session being replaced: the inbox is read again below.
      queue.clear();
    }
    runningGeneration = generation;
    sessionId = resume;
    report(AgentsDoors.Report.STARTING, null);
    String launched;
    try {
      launched = harness.launch(plan);
    } catch (RuntimeException e) {
      String why = "launch failed: " + e.getMessage();
      LOG.warnf("AgentKeeper: %s", why);
      report(AgentsDoors.Report.FAILED, why);
      scheduleRelaunch(nextBackoff());
      return;
    }
    commandId = launched;
    launchedAt = clock.getAsLong();
    activity = null;
    typedAt = -1;
    LOG.infof(
        "AgentKeeper launched %s at generation %d (%s)",
        commandId, generation, resume == null ? "fresh" : "resuming " + resume);
    pollTurns();
  }

  private void scheduleRelaunch(long delayMs) {
    relaunchPending = true;
    long scheduledIn = epoch;
    scheduler.schedule(
        () -> {
          if (scheduledIn != epoch || commandId != null) {
            return;
          }
          relaunchPending = false;
          readLaunch();
          if (retired || launch == null) {
            return;
          }
          launchNow(runningGeneration >= 0 && launch.generation() > runningGeneration);
        },
        delayMs);
  }

  /** Notices the session's exit, and its session id once the harness reports one. */
  void watch() {
    if (commandId == null) {
      return;
    }
    Optional<String> reported = harness.sessionId(commandId);
    if (reported.isPresent() && !reported.get().equals(sessionId)) {
      sessionId = reported.get();
      if (activity != null) {
        report(activity, null);
      }
    }
    if (harness.running(commandId)) {
      return;
    }
    String exited = commandId;
    int code = harness.exitCode(exited).orElse(-1);
    commandId = null;
    activity = null;
    typedAt = -1;
    if (clock.getAsLong() - launchedAt >= timing.resetAfterMs()) {
      backoffMs = timing.initialBackoffMs();
    }
    long delay = nextBackoff();
    String detail = "exit " + code + "; relaunching in " + (delay / 1000) + " s";
    LOG.infof("AgentKeeper: session %s exited (%s)", exited, detail);
    report(AgentsDoors.Report.EXITED, detail);
    scheduleRelaunch(delay);
  }

  /** Reads the pending turns and queues the new ones; types one if the session is idle. */
  void pollTurns() {
    if (retired || launch == null) {
      return;
    }
    List<AgentsDoors.Turn> pending;
    try {
      pending = doors.pendingTurns();
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: turns not read: %s", e.getMessage());
      return;
    }
    for (AgentsDoors.Turn turn : pending) {
      if (acknowledged.contains(turn.id())) {
        continue;
      }
      if (typed.contains(turn.id())) {
        acknowledge(turn); // typed, but the acknowledgement did not land: owed, not retyped
        continue;
      }
      boolean queued = queue.stream().anyMatch(q -> q.id().equals(turn.id()));
      if (!queued && queue.size() < CAPACITY) {
        queue.add(turn);
      }
    }
    queue.sort(ORDER);
    flushOne();
  }

  /** RECOVERY first, then oldest first. */
  static final Comparator<AgentsDoors.Turn> ORDER =
      Comparator.comparing((AgentsDoors.Turn t) -> !t.recovery())
          .thenComparing(t -> t.createdAt() == null ? "" : t.createdAt());

  void onActivity(String forCommand, String state, String reportedSession) {
    if (forCommand == null || !forCommand.equals(commandId) || state == null) {
      return;
    }
    if (reportedSession != null && !reportedSession.isBlank()) {
      sessionId = reportedSession;
    }
    switch (state) {
      case "IDLE" -> {
        activity = AgentsDoors.Report.IDLE;
        report(AgentsDoors.Report.IDLE, null);
        flushOne();
      }
      case "BUSY", "WAITING" -> {
        activity = AgentsDoors.Report.BUSY;
        typedAt = -1;
        report(AgentsDoors.Report.BUSY, null);
      }
      default -> {
        // ENDED: the watch sees the exit and decides what follows.
      }
    }
  }

  /** Types the first queued turn, if the session is idle and not still taking the last one. */
  private void flushOne() {
    if (queue.isEmpty() || commandId == null || !AgentsDoors.Report.IDLE.equals(activity)) {
      return;
    }
    if (typedAt >= 0 && clock.getAsLong() - typedAt < timing.startGraceMs()) {
      return;
    }
    AgentsDoors.Turn turn = queue.getFirst();
    GuardedInput written;
    try {
      written = harness.type(commandId, turn.text());
    } catch (RuntimeException e) {
      LOG.warnf("AgentKeeper: turn %s not typed: %s", turn.id(), e.getMessage());
      return;
    }
    if (written != GuardedInput.WRITTEN) {
      LOG.debugf("AgentKeeper: turn %s kept (%s)", turn.id(), written);
      return;
    }
    queue.removeFirst();
    typed.add(turn.id());
    typedAt = clock.getAsLong();
    LOG.infof("AgentKeeper typed %s turn %s into %s", turn.kind(), turn.id(), commandId);
    acknowledge(turn);
  }

  private void acknowledge(AgentsDoors.Turn turn) {
    try {
      doors.delivered(turn.id());
      acknowledged.add(turn.id());
      typed.remove(turn.id());
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: turn %s not acknowledged yet: %s", turn.id(), e.getMessage());
    }
  }

  private void report(String state, String detail) {
    String said = detail != null ? detail : pendingDetail.get();
    if (said != null && said.length() > DETAIL_LIMIT) {
      said = said.substring(0, DETAIL_LIMIT);
    }
    int generation = runningGeneration >= 0 ? runningGeneration : launch == null ? 0 : launch.generation();
    String key = generation + "|" + state + "|" + sessionId + "|" + said;
    if (said == null && Objects.equals(key, lastReport)) {
      return;
    }
    lastReport = key;
    try {
      doors.report(new AgentsDoors.Report(generation, state, sessionId, said));
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: %s not reported: %s", state, e.getMessage());
    }
  }

  private boolean transcriptPresent(String candidate) {
    try {
      return harness.transcriptExists(candidate);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private long nextBackoff() {
    long delay = backoffMs;
    backoffMs = Math.min(timing.maxBackoffMs(), Math.max(1, backoffMs) * 2);
    return delay;
  }

  // --- what a launch is ------------------------------------------------------------------------

  /** An interactive, REPOSITORY-scoped {@code project.agent} session, resuming {@code resume}. */
  static AgentLaunchRequest request(String resume) {
    return new AgentLaunchRequest(
        AgentMcpScope.REPOSITORY,
        AgentSurface.PROJECT_AGENT,
        AgentLaunchMode.INTERACTIVE,
        null,
        resume,
        false,
        false,
        null,
        null,
        Map.of());
  }

  /** The {@code project.agent} configuration: the shipped one, with this launch's steering. */
  AgentSurfaceConfiguration configuration(AgentsDoors.Launch from) {
    AgentSurfaceConfiguration shipped =
        AgentSurfaceConfigurations.shippedFor(AgentSurface.PROJECT_AGENT, AgentType.CLAUDE, true);
    String prompt = OverseerBrief.render(project, name, wrapper);
    if (!from.brief().isBlank()) {
      prompt = prompt + "\n\n" + from.brief();
    }
    return new AgentSurfaceConfiguration(
        shipped.surface(),
        shipped.harness(),
        from.model(),
        from.effort(),
        shipped.remoteControl(),
        shipped.permissionMode(),
        // The hooks are how the keeper knows the session is idle: never off here.
        true,
        prompt,
        "",
        null,
        List.of(),
        false);
  }
}
