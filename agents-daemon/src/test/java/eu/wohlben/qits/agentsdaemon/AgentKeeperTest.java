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
import org.junit.jupiter.api.Test;

/** The keeper against a fake launch service and fake doors, on a hand-driven clock. */
class AgentKeeperTest {

  static final UUID PROJECT_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

  final FakeDoors doors = new FakeDoors();
  final FakeHarness harness = new FakeHarness();
  final ManualScheduler scheduler = new ManualScheduler();
  final AgentKeeper keeper =
      new AgentKeeper(
          doors,
          harness,
          scheduler,
          scheduler::now,
          AgentKeeper.Timing.DEFAULT,
          "qits",
          "developer",
          "qits-qits",
          () -> null);

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
    assertEquals(List.of(recovery), doors.delivered);

    keeper.onActivity("cmd-2", "BUSY", null);
    keeper.onActivity("cmd-2", "IDLE", null);
    assertEquals(List.of("cmd-2:You were restarted.", "cmd-2:Take qits-1."), harness.typed);
    assertEquals(List.of(recovery, assigned), doors.delivered);
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
      return pending();
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
