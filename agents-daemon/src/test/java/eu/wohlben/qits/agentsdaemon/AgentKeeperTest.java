package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.AgentLaunchMode;
import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.commands.GuardedInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The keeper against a fake launch service and fake doors, on a hand-driven clock. */
class AgentKeeperTest {

  static final UUID PROJECT_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

  final FakeDoors doors = new FakeDoors();
  final FakeHarness harness = new FakeHarness();
  final ManualScheduler scheduler = new ManualScheduler();
  /** The work volume's ledger: it outlives a keeper, as the volume outlives a container. */
  final SessionLedger ledger = SessionLedger.inMemory();
  final AgentKeeper keeper = keeper(harness);

  /** A keeper as a (new) container starts one, over the same doors and the same work volume. */
  AgentKeeper keeper(FakeHarness on) {
    return new AgentKeeper(
        doors,
        on,
        scheduler,
        scheduler::now,
        AgentKeeper.Timing.DEFAULT,
        "qits",
        "developer",
        "qits-qits",
        () -> null,
        ledger);
  }

  @Test
  void aRelaunchAfterAnExitResumesTheSessionId() {
    doors.launch = launch(1, null);
    keeper.refresh();
    assertEquals(1, harness.plans.size());
    assertNull(harness.plans.getFirst().request().resumeSessionId(), "nothing to resume yet");

    // The session reported itself; the service now names it in launch.
    harness.sessions.put("cmd-1", "session-1");
    harness.transcripts.add("session-1");
    keeper.watch();
    doors.launch = launch(1, "session-1");
    harness.exit("cmd-1", 1);
    keeper.watch();

    assertEquals("EXITED", doors.reports.getLast().state());
    assertEquals(1, doors.reports.getLast().generation());
    scheduler.advance(4_999);
    assertEquals(1, harness.plans.size(), "the ladder waits 5 s");
    scheduler.advance(1);
    assertEquals(2, harness.plans.size());
    assertEquals("session-1", harness.plans.get(1).request().resumeSessionId());

    // A second crash waits twice as long.
    harness.exit("cmd-2", 1);
    keeper.watch();
    scheduler.advance(9_999);
    assertEquals(2, harness.plans.size());
    scheduler.advance(1);
    assertEquals(3, harness.plans.size());
  }

  @Test
  void aNewerGenerationGivesAFreshSessionWhoseFirstTurnIsRecovery() {
    doors.launch = launch(1, "session-1");
    harness.transcripts.add("session-1");
    ledger.record("session-1", 1);
    keeper.refresh();
    assertEquals("session-1", harness.plans.getFirst().request().resumeSessionId());

    doors.launch = launch(2, "session-1");
    UUID assigned = doors.turn(2, "ASSIGNED", "Take qits-1.", "2026-01-01T00:00:01Z");
    UUID recovery = doors.turn(2, "RECOVERY", "You were restarted.", "2026-01-01T00:00:03Z");
    keeper.refresh();

    assertEquals(List.of("cmd-1"), harness.terminated, "the old session is ended");
    AgentKeeper.Plan fresh = harness.plans.get(1);
    assertNull(fresh.request().resumeSessionId(), "a new generation never resumes");
    assertEquals(2, fresh.generation());
    assertTrue(harness.typed.isEmpty(), "nothing is typed before the session is idle");

    keeper.onActivity("cmd-2", "IDLE", "session-2");
    assertEquals(List.of("cmd-2:You were restarted."), harness.typed);
    assertTrue(doors.delivered.isEmpty(), "typed is not taken: acknowledged on the session's BUSY");

    keeper.onActivity("cmd-2", "BUSY", null);
    assertEquals(List.of(recovery), doors.delivered);
    keeper.onActivity("cmd-2", "IDLE", null);
    assertEquals(List.of("cmd-2:You were restarted.", "cmd-2:Take qits-1."), harness.typed);
    keeper.onActivity("cmd-2", "BUSY", null);
    assertEquals(List.of(recovery, assigned), doors.delivered);
    assertEquals(0, doors.agentReads, "the pending RECOVERY turn is the prompt; the row is not read");
  }

  @Test
  void aContainerStartWithASessionOfAnOlderGenerationStartsFreshWithRecoveryFirst() {
    // The work volume says session-2 is generation 2's; launch still names it at generation 3.
    ledger.record("session-2", 2);
    harness.transcripts.add("session-2");
    doors.launch = launch(3, "session-2");
    UUID assigned = doors.turn(3, "ASSIGNED", "Take qits-1.", "2026-01-01T00:00:01Z");
    UUID recovery = doors.turn(3, "RECOVERY", "You were restarted.", "2026-01-01T00:00:03Z");

    keeper.refresh();

    assertNull(harness.plans.getFirst().request().resumeSessionId(), "never generation 2's session");
    assertEquals(new AgentsDoors.Report(3, "STARTING", null, null), doors.reports.getFirst());
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.onActivity("cmd-1", "BUSY", "session-3");
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.onActivity("cmd-1", "BUSY", "session-3");

    assertEquals(List.of("cmd-1:You were restarted.", "cmd-1:Take qits-1."), harness.typed);
    assertEquals(List.of(recovery, assigned), doors.delivered);
    assertEquals(3, ledger.generationOf("session-3").getAsInt());
    assertNeverPaired(3, "session-2");
  }

  @Test
  void aContainerStartWithASessionOfTheSameGenerationResumesItAndRetypesNothing() {
    ledger.record("session-3", 3);
    harness.transcripts.add("session-3");
    doors.launch = launch(3, "session-3");
    UUID recovery = doors.turn(3, "RECOVERY", "You were restarted.", "2026-01-01T00:00:03Z");
    doors.deliveredSet.add(recovery); // the earlier container's session took it

    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.pollTurns();

    assertEquals("session-3", harness.plans.getFirst().request().resumeSessionId());
    assertTrue(harness.typed.isEmpty(), "a resumed session already has its recovery");
    assertEquals(0, doors.agentReads);
    assertEquals(new AgentsDoors.Report(3, "IDLE", "session-3", null), doors.reports.getLast());
  }

  @Test
  void aSessionTheLedgerDoesNotKnowIsNotResumed() {
    harness.transcripts.add("session-2");
    doors.launch = launch(2, "session-2");

    keeper.refresh();

    assertNull(harness.plans.getFirst().request().resumeSessionId(), "launch.sessionId alone is not trusted");
  }

  /**
   * The live generation 2 → 3 change of 2026-11-25: the old container typed RECOVERY into its fresh
   * session and was stopped before the session took it; a new container starts at generation 3 with
   * launch still naming generation 2's session.
   */
  @Test
  void aRecoveryTheDyingContainerTypedIsNotAcknowledgedAndTheNextFreshSessionGetsIt() {
    ledger.record("session-2", 2);
    harness.transcripts.add("session-2");
    doors.launch = launch(2, "session-2");
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-2");

    doors.launch = launch(3, "session-2");
    UUID recovery = doors.turn(3, "RECOVERY", "You were restarted.", "2026-01-01T00:00:03Z");
    keeper.refresh();
    keeper.onActivity("cmd-2", "IDLE", "session-3");
    assertEquals(List.of("cmd-2:You were restarted."), harness.typed);
    keeper.stop(); // SIGTERM: the container is replaced
    keeper.onActivity("cmd-2", "BUSY", "session-3");
    keeper.pollTurns();
    assertTrue(doors.delivered.isEmpty(), "never acknowledged: the session did not take it in time");

    FakeHarness next = new FakeHarness();
    next.transcripts.add("session-2");
    AgentKeeper replaced = keeper(next);
    replaced.refresh();
    assertNull(next.plans.getFirst().request().resumeSessionId());
    replaced.onActivity("cmd-1", "IDLE", "session-4");
    assertEquals(List.of("cmd-1:You were restarted."), next.typed);
    replaced.onActivity("cmd-1", "BUSY", "session-4");
    assertEquals(List.of(recovery), doors.delivered);
    assertNeverPaired(3, "session-2");
  }

  @Test
  void aRecoveryAlreadyAcknowledgedIsStillTypedIntoTheFreshSession() {
    ledger.record("session-2", 2);
    doors.launch = launch(3, "session-2");
    doors.recoveryPrompt = "You were restarted; read your claims.";
    UUID recovery = doors.turn(3, "RECOVERY", "You were restarted; read your claims.", "2026-01-01T00:00:03Z");
    doors.deliveredSet.add(recovery); // acknowledged by a container that died
    UUID assigned = doors.turn(3, "ASSIGNED", "Take qits-1.", "2026-01-01T00:00:04Z");

    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    assertEquals(List.of("cmd-1:You were restarted; read your claims."), harness.typed, "recovery first");
    keeper.pollTurns();
    assertEquals(1, harness.typed.size(), "nothing else while the prompt is not taken");
    keeper.onActivity("cmd-1", "BUSY", "session-3");
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.onActivity("cmd-1", "BUSY", "session-3");
    keeper.onActivity("cmd-1", "IDLE", "session-3");

    assertEquals(
        List.of("cmd-1:You were restarted; read your claims.", "cmd-1:Take qits-1."), harness.typed);
    assertEquals(List.of(assigned), doors.delivered);
    assertEquals(1, doors.agentReads);
  }

  @Test
  void aPendingRecoveryOfTheGenerationIsNotRetypedOnceTheRowsPromptWasTaken() {
    doors.launch = launch(3, null);
    doors.failTurns = true; // the inbox does not answer at first
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.onActivity("cmd-1", "BUSY", "session-3");
    assertEquals(List.of("cmd-1:Recover."), harness.typed);

    doors.failTurns = false;
    UUID recovery = doors.turn(3, "RECOVERY", "Recover.", "2026-01-01T00:00:03Z");
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    keeper.pollTurns();

    assertEquals(List.of("cmd-1:Recover."), harness.typed, "typed once");
    assertEquals(List.of(recovery), doors.delivered, "its text reached the session: acknowledged");
  }

  @Test
  void aShutdownDuringAFlushAcknowledgesNothing() {
    doors.launch = launch(1, null);
    doors.turn(1, "COMMENTED", "Hello.", "2026-01-01T00:00:01Z");
    keeper.refresh();
    harness.onType = keeper::stop; // SIGTERM arrives while the turn is being typed

    keeper.onActivity("cmd-1", "IDLE", "session-1");
    int reports = doors.reports.size();
    keeper.onActivity("cmd-1", "BUSY", "session-1");
    keeper.pollTurns();
    keeper.refresh();
    scheduler.advance(600_000);

    assertEquals(List.of("cmd-1:Hello."), harness.typed);
    assertTrue(doors.delivered.isEmpty());
    assertEquals(reports, doors.reports.size(), "a stopping daemon reports nothing");
    assertEquals(1, harness.plans.size(), "nor launches");
  }

  @Test
  void aTurnTypedIntoASessionThatExitsBeforeTakingItIsTypedAgainIntoTheNext() {
    doors.launch = launch(1, null);
    UUID hello = doors.turn(1, "COMMENTED", "Hello.", "2026-01-01T00:00:01Z");
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    harness.transcripts.add("session-1");
    harness.exit("cmd-1", 1);
    keeper.watch();
    keeper.onActivity("cmd-1", "BUSY", "session-1"); // a late hook of the exited command
    assertTrue(doors.delivered.isEmpty());

    scheduler.advance(5_000);
    keeper.onActivity("cmd-2", "IDLE", "session-1");
    keeper.onActivity("cmd-2", "BUSY", "session-1");

    assertEquals("session-1", harness.plans.get(1).request().resumeSessionId());
    assertEquals(List.of("cmd-1:Hello.", "cmd-2:Hello."), harness.typed);
    assertEquals(List.of(hello), doors.delivered);
  }

  @Test
  void aTurnNotTakenWithinTheBoundIsTypedAgainOnceAtTheNextIdleAndAcknowledgedOnce() {
    doors.launch = launch(1, null);
    UUID hello = doors.turn(1, "COMMENTED", "Hello.", "2026-01-01T00:00:01Z");
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    assertEquals(List.of("cmd-1:Hello."), harness.typed);

    scheduler.advance(AgentKeeper.Timing.DEFAULT.takeTimeoutMs() - 1);
    keeper.pollTurns();
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    assertEquals(1, harness.typed.size(), "within the bound nothing is retyped");

    scheduler.advance(1);
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    keeper.pollTurns();
    assertEquals(List.of("cmd-1:Hello.", "cmd-1:Hello."), harness.typed, "typed again, once");

    keeper.onActivity("cmd-1", "BUSY", "session-1");
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    keeper.pollTurns();
    assertEquals(List.of(hello), doors.delivered, "taken on the retry: acknowledged once");
    assertEquals(2, harness.typed.size());
  }

  @Test
  void aTurnNeverTakenIsLeftPendingAfterThreeTriesAndTheSessionGoesOn() {
    doors.launch = launch(1, null);
    UUID hello = doors.turn(1, "COMMENTED", "Hello.", "2026-01-01T00:00:01Z");
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-1");
    for (int i = 0; i < 4; i++) {
      scheduler.advance(AgentKeeper.Timing.DEFAULT.takeTimeoutMs());
      keeper.pollTurns();
    }

    assertEquals(AgentKeeper.TAKE_TRIES, harness.typed.size());
    assertTrue(doors.delivered.isEmpty());
    assertTrue(doors.pending().stream().anyMatch(t -> t.id().equals(hello)), "left pending");
    assertTrue(
        doors.reports.stream()
            .anyMatch(r -> ("turn " + hello + " not taken after 3 tries").equals(r.detail())
                && !"FAILED".equals(r.state())),
        "named on a report: " + doors.reports);
    assertEquals("cmd-1", keeper.commandId(), "the session is kept");

    UUID next = doors.turn(1, "COMMENTED", "Next.", "2026-01-01T00:00:02Z");
    keeper.pollTurns();
    keeper.onActivity("cmd-1", "BUSY", "session-1");
    assertEquals("cmd-1:Next.", harness.typed.getLast(), "the turns after it go on");
    assertEquals(List.of(next), doors.delivered);
  }

  @Test
  void aRowRecoveryPromptNotTakenIsTypedAgain() {
    doors.launch = launch(3, null);
    keeper.refresh();
    keeper.onActivity("cmd-1", "IDLE", "session-3");
    scheduler.advance(AgentKeeper.Timing.DEFAULT.takeTimeoutMs());
    keeper.pollTurns();

    assertEquals(List.of("cmd-1:Recover.", "cmd-1:Recover."), harness.typed);
  }

  private void assertNeverPaired(int generation, String session) {
    for (AgentsDoors.Report report : doors.reports) {
      assertTrue(
          report.generation() != generation || !session.equals(report.sessionId()),
          "reported " + report);
    }
  }

  @Test
  void theFirstStartDeliversRecoveryThenTheOthersEachAcknowledgedOnce() {
    doors.launch = launch(2, null);
    UUID assigned = doors.turn(2, "ASSIGNED", "Take qits-1.", "2026-01-01T00:00:01Z");
    UUID commented = doors.turn(2, "COMMENTED", "A comment on qits-1.", "2026-01-01T00:00:02Z");
    UUID recovery = doors.turn(2, "RECOVERY", "You were restarted.", "2026-01-01T00:00:03Z");
    doors.failNextDelivered = true; // the first acknowledgement does not land
    keeper.refresh();
    assertNull(harness.plans.getFirst().request().resumeSessionId());

    for (int i = 0; i < 4; i++) {
      keeper.onActivity("cmd-1", "IDLE", null);
      keeper.pollTurns();
      keeper.pollTurns();
      keeper.onActivity("cmd-1", "BUSY", null);
    }

    assertEquals(
        List.of("cmd-1:You were restarted.", "cmd-1:Take qits-1.", "cmd-1:A comment on qits-1."),
        harness.typed,
        "each typed once, RECOVERY first");
    assertEquals(List.of(recovery, assigned, commented), doors.delivered, "each acknowledged once");
    assertTrue(doors.pending().isEmpty());
  }

  @Test
  void theLedgerOnTheWorkVolumeOutlivesTheContainer(@TempDir Path volume) throws Exception {
    Path file = volume.resolve(AgentDaemon.SESSION_LEDGER);
    SessionLedger.at(file).record("session-2", 2);
    SessionLedger.at(file).record("session-3", 3);

    SessionLedger read = SessionLedger.at(file);
    assertEquals(2, read.generationOf("session-2").getAsInt());
    assertEquals(3, read.generationOf("session-3").getAsInt());
    assertTrue(read.generationOf("session-9").isEmpty());
    for (int i = 0; i < SessionLedger.KEEP + 5; i++) {
      read.record("s-" + i, 4);
    }
    assertTrue(SessionLedger.at(file).generationOf("session-2").isEmpty(), "the oldest fall out");
    assertEquals(SessionLedger.KEEP, Files.readAllLines(file).size());
  }

  @Test
  void aTurnIsNotTypedWhileTheSessionIsBusy() {
    doors.launch = launch(1, null);
    keeper.refresh();
    keeper.onActivity("cmd-1", "BUSY", null);
    doors.turn(1, "COMMENTED", "Hello.", "2026-01-01T00:00:01Z");

    keeper.pollTurns();

    assertTrue(harness.typed.isEmpty());
    keeper.onActivity("cmd-1", "IDLE", null);
    assertEquals(List.of("cmd-1:Hello."), harness.typed);
  }

  @Test
  void theSystemPromptStartsWithTheOverseerBrief() {
    doors.launch = new AgentsDoors.Launch("qits", PROJECT_ID, "developer", 2, "You are the developer.", "opus", "high", null);
    keeper.refresh();

    AgentKeeper.Plan plan = harness.plans.getFirst();
    String prompt = plan.configuration().systemPrompt();
    assertTrue(prompt.startsWith(OverseerBrief.render("qits", "developer", "qits-qits")), prompt);
    assertTrue(prompt.endsWith("\n\nYou are the developer."), prompt);
    assertEquals("opus", plan.configuration().model());
    assertEquals("high", plan.configuration().effort());
    assertEquals(AgentSurface.PROJECT_AGENT.key(), plan.configuration().surface());
    assertEquals(AgentSurface.PROJECT_AGENT, plan.request().surface());
    assertEquals(AgentLaunchMode.INTERACTIVE, plan.request().mode());
    assertEquals(AgentMcpScope.REPOSITORY, plan.request().scope());
    assertTrue(plan.configuration().activityTracking(), "the hooks are how idle is known");
    assertEquals(PROJECT_ID.toString(), keeper.projectId());
  }

  @Test
  void aConflictOnLaunchStopsTheSessionAndNothingRelaunchesIt() {
    doors.launch = launch(1, null);
    keeper.refresh();
    doors.retired = true;

    keeper.refresh();

    assertEquals(List.of("cmd-1"), harness.terminated);
    assertEquals("EXITED", doors.reports.getLast().state());
    harness.exit("cmd-1", 143);
    keeper.watch();
    scheduler.advance(600_000);
    keeper.refresh();
    keeper.pollTurns();
    assertEquals(1, harness.plans.size(), "a retired agent is not launched");
    assertNull(keeper.commandId());
  }

  @Test
  void theReportsCarryTheGenerationAndTheSessionId() {
    doors.launch = launch(3, null);
    keeper.refresh();
    assertEquals("STARTING", doors.reports.getFirst().state());
    keeper.onActivity("cmd-1", "IDLE", "session-9");
    keeper.onActivity("cmd-1", "BUSY", "session-9");

    AgentsDoors.Report busy = doors.reports.getLast();
    assertEquals(new AgentsDoors.Report(3, "BUSY", "session-9", null), busy);
  }

  @Test
  void aFailedLaunchIsReportedAndRetriedOnTheLadder() {
    doors.launch = launch(1, null);
    harness.failNext = true;
    keeper.refresh();

    assertEquals("FAILED", doors.reports.getLast().state());
    assertTrue(doors.reports.getLast().detail().startsWith("launch failed:"));
    assertNull(keeper.commandId());
    scheduler.advance(5_000);
    assertEquals("cmd-1", keeper.commandId());
  }

  // --- fakes -----------------------------------------------------------------------------------

  static AgentsDoors.Launch launch(int generation, String sessionId) {
    return new AgentsDoors.Launch("qits", PROJECT_ID, "developer", generation, "brief", null, null, sessionId);
  }

  static final class FakeDoors implements AgentsDoors {
    Launch launch;
    boolean retired;
    boolean failNextDelivered;
    boolean failTurns;
    String recoveryPrompt = "Recover.";
    int agentReads;
    final List<Turn> turns = new ArrayList<>();
    final Set<UUID> deliveredSet = new HashSet<>();
    final List<UUID> delivered = new ArrayList<>();
    final List<Report> reports = new ArrayList<>();

    UUID turn(int generation, String kind, String text, String createdAt) {
      UUID id = UUID.randomUUID();
      turns.add(new Turn(id, generation, kind, text, createdAt));
      return id;
    }

    List<Turn> pending() {
      return turns.stream()
          .filter(t -> !deliveredSet.contains(t.id()))
          .sorted(Comparator.comparing(Turn::createdAt))
          .toList();
    }

    @Override
    public Launch launch() {
      if (retired) {
        throw new Retired("agent developer is RETIRED");
      }
      return launch;
    }

    @Override
    public List<Turn> pendingTurns() {
      if (failTurns) {
        throw new Unavailable("turns answered 502");
      }
      return pending();
    }

    @Override
    public Agent agent() {
      agentReads++;
      return new Agent(launch.generation(), recoveryPrompt);
    }

    @Override
    public void delivered(UUID turnId) {
      if (failNextDelivered) {
        failNextDelivered = false;
        throw new Unavailable("delivered answered 502");
      }
      if (deliveredSet.add(turnId)) {
        delivered.add(turnId);
      }
    }

    @Override
    public void report(Report report) {
      reports.add(report);
    }
  }

  static final class FakeHarness implements AgentKeeper.Harness {
    final List<AgentKeeper.Plan> plans = new ArrayList<>();
    final Map<String, Integer> exited = new HashMap<>();
    final Map<String, String> sessions = new HashMap<>();
    final Set<String> transcripts = new HashSet<>();
    final List<String> terminated = new ArrayList<>();
    final List<String> typed = new ArrayList<>();
    boolean failNext;
    Runnable onType = () -> {};

    void exit(String commandId, int code) {
      exited.put(commandId, code);
    }

    @Override
    public String launch(AgentKeeper.Plan plan) {
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("claude is not signed in");
      }
      plans.add(plan);
      return "cmd-" + plans.size();
    }

    @Override
    public boolean running(String commandId) {
      return !exited.containsKey(commandId);
    }

    @Override
    public Optional<Integer> exitCode(String commandId) {
      return Optional.ofNullable(exited.get(commandId));
    }

    @Override
    public Optional<String> sessionId(String commandId) {
      return Optional.ofNullable(sessions.get(commandId));
    }

    @Override
    public boolean transcriptExists(String sessionId) {
      return transcripts.contains(sessionId);
    }

    @Override
    public boolean terminate(String commandId) {
      terminated.add(commandId);
      exited.put(commandId, 143);
      return true;
    }

    @Override
    public GuardedInput type(String commandId, String text) {
      if (exited.containsKey(commandId)) {
        return GuardedInput.NOT_RUNNING;
      }
      onType.run();
      typed.add(commandId + ":" + text);
      return GuardedInput.WRITTEN;
    }
  }

  /** Steps run when the hand-driven clock reaches them, in order. */
  static final class ManualScheduler implements AgentKeeper.Scheduler {
    private record Task(long due, long seq, Runnable step) {}

    private final PriorityQueue<Task> tasks =
        new PriorityQueue<>(Comparator.comparingLong(Task::due).thenComparingLong(Task::seq));
    private long now = 1_000_000;
    private long seq;

    long now() {
      return now;
    }

    @Override
    public void schedule(Runnable step, long delayMs) {
      tasks.add(new Task(now + delayMs, seq++, step));
    }

    void advance(long ms) {
      long until = now + ms;
      while (!tasks.isEmpty() && tasks.peek().due() <= until) {
        Task task = tasks.poll();
        now = Math.max(now, task.due());
        task.step().run();
      }
      now = until;
    }
  }
}
