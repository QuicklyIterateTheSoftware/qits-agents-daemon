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
import java.util.OptionalInt;
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
 *       resuming {@code launch.sessionId} (else the last session seen) when the {@link
 *       SessionLedger} places it at the launch's generation and its transcript is on the credential
 *       volume. A session the ledger does not place there is never resumed: a new container at a
 *       new generation would otherwise resume the previous generation's session, which {@code
 *       launch.sessionId} still names.
 *   <li><b>New generation.</b> {@code launch.generation} exceeds the running one: the session is
 *       ended and a <em>fresh</em> one launched (no resume); the pending turns follow it, RECOVERY
 *       first.
 *   <li><b>Recovery.</b> Every fresh session at a generation past the first begins with that
 *       generation's recovery prompt: the pending RECOVERY turn of the generation when there is
 *       one, else the row's {@code recoveryPrompt} — the RECOVERY turn may already have been
 *       acknowledged by a container that died before its session took it. Typed once per session;
 *       a pending RECOVERY turn of the generation is acknowledged, not retyped, once the session
 *       took the prompt.
 *   <li><b>Turns</b> are read every 10 s and right after a launch, queued (FIFO of 16, RECOVERY
 *       first, then by {@code createdAt}) and typed one at a time at the session's next idle, as
 *       {@code FrontDeskInbox} does. A typed turn is acknowledged with {@code delivered} only once
 *       the session took it — its {@code UserPromptSubmit} hook (BUSY) for that command — while
 *       the session is still the running generation's and the daemon is not stopping. Until then
 *       nothing else is typed; a session that ends first leaves the turn pending, for the next one.
 *   <li><b>Report</b>: STARTING at launch, IDLE/BUSY from the harness hooks, EXITED or FAILED on
 *       exit, always with the running generation and a session id the ledger does not place at
 *       another generation.
 *   <li><b>Stopping</b> (the container stops): {@link #stop} before the sessions are terminated;
 *       from then on nothing is typed, acknowledged, reported or launched.
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
  private final SessionLedger ledger;

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
  /** Taken by a session (its BUSY came); still owed an acknowledgement. */
  private final Set<UUID> taken = new HashSet<>();
  private final Set<UUID> acknowledged = new HashSet<>();
  private String lastReport;
  /** What was typed into {@code commandId} and not yet taken; nothing else is typed meanwhile. */
  private InFlight inFlight;
  /** The generation whose recovery prompt the running (fresh) session still owes, else -1. */
  private int recoveryOwed = -1;
  /** The command that took the running generation's recovery prompt, else null. */
  private String recoveryTakenBy;
  /** Set once, from the shutdown thread: the container is stopping. */
  private volatile boolean stopping;

  /**
   * Typed into {@code commandId} at {@code generation}: a turn of the inbox, or (turn null) the
   * row's recovery prompt; {@code recovery} when it is that generation's recovery prompt.
   */
  private record InFlight(String commandId, AgentsDoors.Turn turn, boolean recovery, int generation) {

    String what() {
      return turn == null ? "the recovery prompt" : turn.kind() + " turn " + turn.id();
    }
  }

  AgentKeeper(
      AgentsDoors doors,
      Harness harness,
      Scheduler scheduler,
      LongSupplier clock,
      Timing timing,
      String project,
      String name,
      String wrapper,
      Supplier<String> pendingDetail,
      SessionLedger ledger) {
    this.doors = doors;
    this.harness = harness;
    this.scheduler = scheduler;
    this.clock = clock;
    this.timing = timing;
    this.project = project;
    this.name = name;
    this.wrapper = wrapper;
    this.pendingDetail = pendingDetail == null ? () -> null : pendingDetail;
    this.ledger = ledger == null ? SessionLedger.inMemory() : ledger;
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

  /**
   * The container is stopping: from now on nothing is typed, acknowledged, reported or launched. A
   * turn typed and not yet taken stays pending at the service, for the next container's session.
   * Called before the sessions are terminated.
   */
  void stop() {
    if (!stopping) {
      stopping = true;
      LOG.info("AgentKeeper stopping: no turn is acknowledged from now on");
    }
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
    if (stopping || !readLaunch()) {
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
    inFlight = null;
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
    if (inFlight != null) {
      LOG.infof("AgentKeeper: %s typed into %s was not taken; it stays pending", inFlight.what(), ending);
      inFlight = null;
    }
    try {
      harness.terminate(ending);
    } catch (RuntimeException e) {
      LOG.warnf("AgentKeeper: could not end session %s: %s", ending, e.getMessage());
    }
  }

  /**
   * Launches the session: fresh, or resuming what {@code launch} (else the last session) names —
   * only a session the ledger places at the launch's generation.
   */
  private void launchNow(boolean fresh) {
    if (launch == null || retired || stopping) {
      return;
    }
    epoch++;
    relaunchPending = false;
    int generation = launch.generation();
    String resume = null;
    if (!fresh) {
      resume = resumable(launch.sessionId(), generation);
      if (resume == null && sessionId != null && !sessionId.equals(launch.sessionId())) {
        resume = resumable(sessionId, generation);
      }
    }
    Plan plan = new Plan(request(resume), configuration(launch), generation);
    if (fresh) {
      // Whatever was queued was for the session being replaced: the inbox is read again below.
      queue.clear();
    }
    inFlight = null;
    runningGeneration = generation;
    sessionId = resume;
    // A fresh session knows nothing: past the first generation it begins with the recovery prompt.
    recoveryOwed = resume == null && generation > 1 ? generation : -1;
    recoveryTakenBy = null;
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
    try {
      harness.sessionId(launched).ifPresent(this::learnSession);
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: session id of %s not read yet: %s", launched, e.getMessage());
    }
    pollTurns();
  }

  /**
   * {@code candidate}, when the ledger places it at {@code generation} and its transcript is on the
   * credential volume; else null.
   */
  private String resumable(String candidate, int generation) {
    if (candidate == null) {
      return null;
    }
    OptionalInt belongs = ledger.generationOf(candidate);
    if (belongs.isEmpty() || belongs.getAsInt() != generation) {
      LOG.infof(
          "AgentKeeper: not resuming %s at generation %d: it belongs to %s",
          candidate,
          generation,
          belongs.isEmpty() ? "no generation this daemon recorded" : "generation " + belongs.getAsInt());
      return null;
    }
    return transcriptPresent(candidate) ? candidate : null;
  }

  /** The running command reported {@code id}: it is this generation's session. */
  private void learnSession(String id) {
    if (id == null || id.isBlank() || commandId == null) {
      return;
    }
    sessionId = id;
    ledger.record(id, runningGeneration);
  }

  private void scheduleRelaunch(long delayMs) {
    relaunchPending = true;
    long scheduledIn = epoch;
    scheduler.schedule(
        () -> {
          if (scheduledIn != epoch || commandId != null || stopping) {
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
    if (commandId == null || stopping) {
      return;
    }
    Optional<String> reported = harness.sessionId(commandId);
    if (reported.isPresent() && !reported.get().equals(sessionId)) {
      learnSession(reported.get());
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
    if (inFlight != null) {
      LOG.infof("AgentKeeper: %s typed into %s was not taken; it stays pending", inFlight.what(), exited);
      inFlight = null;
    }
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
    if (retired || launch == null || stopping) {
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
      if (taken.contains(turn.id())) {
        acknowledge(turn); // taken, but the acknowledgement did not land: owed, not retyped
        continue;
      }
      if (inFlight != null && inFlight.turn() != null && inFlight.turn().id().equals(turn.id())) {
        continue; // typed, not yet taken
      }
      if (recoveryTaken(turn)) {
        // This session already took its generation's recovery prompt: the same text, not retyped.
        taken.add(turn.id());
        acknowledge(turn);
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
    if (stopping || forCommand == null || !forCommand.equals(commandId) || state == null) {
      return;
    }
    if (reportedSession != null && !reportedSession.isBlank()) {
      learnSession(reportedSession);
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
        if ("BUSY".equals(state)) {
          onTaken(forCommand); // UserPromptSubmit: the session took what was typed
        }
        report(AgentsDoors.Report.BUSY, null);
      }
      default -> {
        // ENDED: the watch sees the exit and decides what follows.
      }
    }
  }

  /**
   * Types the next thing into the session, if it is idle, up for the running generation and not
   * still taking the last one: the recovery prompt a fresh session owes, else the first queued turn.
   */
  private void flushOne() {
    if (stopping || commandId == null || !AgentsDoors.Report.IDLE.equals(activity) || inFlight != null) {
      return;
    }
    if (launch == null || launch.generation() != runningGeneration) {
      return; // a generation switch is due: the next session gets the turns
    }
    if (typedAt >= 0 && clock.getAsLong() - typedAt < timing.startGraceMs()) {
      return;
    }
    if (recoveryOwed == runningGeneration) {
      Optional<AgentsDoors.Turn> pending =
          queue.stream().filter(t -> t.recovery() && t.generation() == runningGeneration).findFirst();
      if (pending.isPresent()) {
        typeTurn(pending.get(), true);
      } else {
        typeRecoveryFromRow();
      }
      return;
    }
    if (!queue.isEmpty()) {
      typeTurn(queue.getFirst(), false);
    }
  }

  private void typeTurn(AgentsDoors.Turn turn, boolean recovery) {
    if (!type(turn.text(), turn.id().toString())) {
      return;
    }
    queue.remove(turn);
    inFlight = new InFlight(commandId, turn, recovery, runningGeneration);
    typedAt = clock.getAsLong();
    LOG.infof("AgentKeeper typed %s turn %s into %s", turn.kind(), turn.id(), commandId);
  }

  /** The running generation's recovery prompt, from the row: no RECOVERY turn of it is pending. */
  private void typeRecoveryFromRow() {
    AgentsDoors.Agent row;
    try {
      row = doors.agent();
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: the agent's row not read (recovery prompt owed): %s", e.getMessage());
      return; // owed still: the next idle or turns poll asks again, and nothing else is typed first
    }
    if (row.generation() != runningGeneration) {
      LOG.debugf(
          "AgentKeeper: the row is at generation %d, the session at %d; the recovery prompt waits",
          row.generation(), runningGeneration);
      return;
    }
    if (row.recoveryPrompt().isBlank()) {
      recoveryOwed = -1;
      flushOne();
      return;
    }
    if (!type(row.recoveryPrompt(), "the recovery prompt")) {
      return;
    }
    inFlight = new InFlight(commandId, null, true, runningGeneration);
    typedAt = clock.getAsLong();
    LOG.infof(
        "AgentKeeper typed the generation %d recovery prompt into %s (from the agent's row)",
        runningGeneration, commandId);
  }

  /** Types {@code text} into the running session; true when it was written. */
  private boolean type(String text, String what) {
    GuardedInput written;
    try {
      written = harness.type(commandId, text);
    } catch (RuntimeException e) {
      LOG.warnf("AgentKeeper: %s not typed: %s", what, e.getMessage());
      return false;
    }
    if (written != GuardedInput.WRITTEN) {
      LOG.debugf("AgentKeeper: %s kept (%s)", what, written);
      return false;
    }
    return true;
  }

  /**
   * {@code forCommand} submitted a prompt: what was typed into it is taken, and acknowledged — unless
   * the daemon is stopping or the generation is being replaced, when it stays pending.
   */
  private void onTaken(String forCommand) {
    InFlight typed = inFlight;
    if (typed == null || !typed.commandId().equals(forCommand)) {
      return;
    }
    inFlight = null;
    if (stopping
        || launch == null
        || typed.generation() != runningGeneration
        || launch.generation() != runningGeneration) {
      LOG.infof(
          "AgentKeeper: %s taken by %s at generation %d is not acknowledged: the generation is being replaced",
          typed.what(), forCommand, typed.generation());
      return;
    }
    if (typed.recovery()) {
      recoveryOwed = -1;
      recoveryTakenBy = forCommand;
      LOG.infof("AgentKeeper: %s took the generation %d recovery prompt", forCommand, runningGeneration);
    }
    if (typed.turn() != null) {
      taken.add(typed.turn().id());
      acknowledge(typed.turn());
    }
    if (typed.recovery()) {
      // A pending RECOVERY turn of this generation carries the same text: acknowledged, not retyped.
      for (AgentsDoors.Turn turn : List.copyOf(queue)) {
        if (recoveryTaken(turn)) {
          queue.remove(turn);
          taken.add(turn.id());
          acknowledge(turn);
        }
      }
    }
  }

  /** {@code turn} is the running generation's RECOVERY, and the running session took that prompt. */
  private boolean recoveryTaken(AgentsDoors.Turn turn) {
    return turn.recovery()
        && turn.generation() == runningGeneration
        && commandId != null
        && commandId.equals(recoveryTakenBy);
  }

  private void acknowledge(AgentsDoors.Turn turn) {
    if (stopping) {
      return;
    }
    try {
      doors.delivered(turn.id());
      acknowledged.add(turn.id());
      taken.remove(turn.id());
      LOG.infof("AgentKeeper acknowledged %s turn %s", turn.kind(), turn.id());
    } catch (RuntimeException e) {
      LOG.debugf("AgentKeeper: turn %s not acknowledged yet: %s", turn.id(), e.getMessage());
    }
  }

  private void report(String state, String detail) {
    if (stopping) {
      return; // the next container reports; a late report here would overwrite it
    }
    String said = detail != null ? detail : pendingDetail.get();
    if (said != null && said.length() > DETAIL_LIMIT) {
      said = said.substring(0, DETAIL_LIMIT);
    }
    int generation = runningGeneration >= 0 ? runningGeneration : launch == null ? 0 : launch.generation();
    String session = sessionId;
    OptionalInt belongs = ledger.generationOf(session);
    if (belongs.isPresent() && belongs.getAsInt() != generation) {
      session = null; // never a session id under a generation it does not belong to
    }
    String key = generation + "|" + state + "|" + session + "|" + said;
    if (said == null && Objects.equals(key, lastReport)) {
      return;
    }
    lastReport = key;
    try {
      doors.report(new AgentsDoors.Report(generation, state, session, said));
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
