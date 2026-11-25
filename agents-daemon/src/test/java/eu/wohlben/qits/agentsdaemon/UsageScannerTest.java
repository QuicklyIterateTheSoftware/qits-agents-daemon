package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scanner over a temp {@code .claude/projects} tree, against fake doors. */
class UsageScannerTest {

  static final String SESSION = "11111111-2222-4333-8444-555555555555";

  @TempDir Path projects;

  final FakeDoors doors = new FakeDoors();
  final List<String> sessions = new ArrayList<>(List.of(SESSION));

  Path dir;

  UsageScanner scanner() {
    return new UsageScanner(projects, () -> sessions, doors);
  }

  @Test
  void attributesByWorktreeCountsEachMessageOnceAndReadsTheSidechains() throws IOException {
    Path main = main();
    append(
        main,
        assistant("msg_a", "/workspace/worktrees/qits-2565/qits-qits", 12, 340),
        // the same message's next content block: ignored
        assistant("msg_a", "/workspace/worktrees/qits-2565/qits-qits", 99, 99),
        assistant("msg_b", "/workspace/qits-qits", 1, 2),
        "{\"type\":\"user\",\"message\":{\"role\":\"user\"}}",
        "not json at all");
    append(sidechain("agent-x1"), assistant("msg_c", "/workspace/worktrees/qits-2565", 5, 6));

    UsageScanner scanner = scanner();
    scanner.tick();

    assertEquals(1, doors.reports.size());
    AgentsDoors.UsageReport report = doors.reports.getFirst();
    assertEquals(SESSION, report.sessionId());
    Map<String, AgentsDoors.UsageMessage> byId = byId(report);
    assertEquals(3, byId.size());
    assertEquals("qits-2565", byId.get("msg_a").workQualifiedId());
    assertEquals(12, byId.get("msg_a").inputTokens(), "the first line of a message wins");
    assertEquals(340, byId.get("msg_a").outputTokens());
    assertEquals(7, byId.get("msg_a").cacheCreationTokens());
    assertEquals(51234, byId.get("msg_a").cacheReadTokens());
    assertEquals("claude-opus-5-5", byId.get("msg_a").model());
    assertEquals(Instant.parse("2026-11-26T10:00:00Z"), byId.get("msg_a").at());
    assertNull(byId.get("msg_b").workQualifiedId(), "the overseer's own overhead");
    assertEquals("qits-2565", byId.get("msg_c").workQualifiedId(), "the sidechain is read");

    // A repeated id on a later tick is not sent again; only the new message is.
    append(
        main,
        assistant("msg_a", "/workspace/worktrees/qits-2565/qits-qits", 1, 1),
        assistant("msg_d", "/workspace/qits-qits", 3, 4));
    scanner.tick();
    assertEquals(2, doors.reports.size());
    assertEquals(List.of("msg_d"), ids(doors.reports.get(1)));

    scanner.tick();
    assertEquals(2, doors.reports.size(), "nothing new, nothing sent");
  }

  @Test
  void anIncompleteLineWaitsForItsNewline() throws IOException {
    Path main = main();
    String line = assistant("msg_a", "/workspace/qits-qits", 1, 1);
    Files.writeString(main, line.substring(0, 20), StandardCharsets.UTF_8);
    UsageScanner scanner = scanner();
    scanner.tick();
    assertTrue(doors.reports.isEmpty());

    Files.writeString(main, line.substring(20) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    scanner.tick();
    assertEquals(List.of("msg_a"), ids(doors.reports.getFirst()));
  }

  @Test
  void aFailedSendIsRetriedOnTheNextTick() throws IOException {
    append(main(), assistant("msg_a", "/workspace/qits-qits", 1, 1));
    UsageScanner scanner = scanner();
    doors.failures = 2;
    scanner.tick();
    scanner.tick();
    assertEquals(2, doors.attempts);
    assertTrue(doors.reports.isEmpty());

    scanner.tick();
    assertEquals(List.of("msg_a"), ids(doors.reports.getFirst()));
    scanner.tick();
    assertEquals(1, doors.reports.size(), "sent once it was accepted");
  }

  @Test
  void batchesHoldAtMostFiveHundredMessages() throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      lines.add(assistant("msg_" + i, "/workspace/qits-qits", 1, 1));
    }
    append(main(), lines.toArray(String[]::new));
    scanner().tick();
    assertEquals(List.of(500, 500, 1), doors.reports.stream().map(r -> r.messages().size()).toList());
  }

  @Test
  void aSessionWithoutTranscriptSendsNothing() {
    scanner().tick();
    assertTrue(doors.reports.isEmpty());
    assertEquals(0, doors.attempts);
  }

  @Test
  void onlyWorktreePathsNameAWorkItem() {
    assertEquals("qits-2565", UsageScanner.workQualifiedId("/workspace/worktrees/qits-2565/qits-qits"));
    assertEquals("qits-2565", UsageScanner.workQualifiedId("/workspace/worktrees/qits-2565"));
    assertNull(UsageScanner.workQualifiedId("/workspace/worktrees/qits-2565x/qits-qits"));
    assertNull(UsageScanner.workQualifiedId("/workspace/qits-qits"));
    assertNull(UsageScanner.workQualifiedId(null));
  }

  @Test
  void transcriptUsageCountsMissingNumbersAsZero() {
    var usage =
        TranscriptUsage.of(
                "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_z\",\"usage\":{\"output_tokens\":3}}}")
            .orElseThrow();
    assertEquals(0, usage.inputTokens());
    assertEquals(3, usage.outputTokens());
    assertEquals(0, usage.cacheReadTokens());
    assertNull(usage.at());
    assertTrue(TranscriptUsage.of("{\"type\":\"assistant\",\"message\":{\"id\":\"m\"}}").isEmpty());
    assertTrue(TranscriptUsage.of("{broken").isEmpty());
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private Path main() throws IOException {
    dir = Files.createDirectories(projects.resolve("-workspace"));
    Path main = dir.resolve(SESSION + ".jsonl");
    if (!Files.exists(main)) {
      Files.createFile(main);
    }
    return main;
  }

  private Path sidechain(String name) throws IOException {
    Path subagents = Files.createDirectories(dir.resolve(SESSION).resolve("subagents"));
    return subagents.resolve(name + ".jsonl");
  }

  private static void append(Path file, String... lines) throws IOException {
    Files.writeString(
        file,
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static String assistant(String id, String cwd, long input, long output) {
    return "{\"type\":\"assistant\",\"cwd\":\""
        + cwd
        + "\",\"timestamp\":\"2026-11-26T10:00:00Z\",\"message\":{\"id\":\""
        + id
        + "\",\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":"
        + input
        + ",\"output_tokens\":"
        + output
        + ",\"cache_creation_input_tokens\":7,\"cache_read_input_tokens\":51234}}}";
  }

  private static Map<String, AgentsDoors.UsageMessage> byId(AgentsDoors.UsageReport report) {
    return report.messages().stream()
        .collect(Collectors.toMap(AgentsDoors.UsageMessage::messageId, Function.identity()));
  }

  private static List<String> ids(AgentsDoors.UsageReport report) {
    return report.messages().stream().map(AgentsDoors.UsageMessage::messageId).toList();
  }

  /** Records what the usage door accepted; fails the next {@code failures} calls. */
  static final class FakeDoors implements AgentsDoors {

    final List<UsageReport> reports = new ArrayList<>();
    int failures;
    int attempts;

    @Override
    public void usage(UsageReport report) {
      attempts++;
      if (failures > 0) {
        failures--;
        throw new Unavailable("usage answered 404");
      }
      reports.add(report);
    }

    @Override
    public Launch launch() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Turn> pendingTurns() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delivered(UUID turnId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void report(Report report) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Agent agent() {
      throw new UnsupportedOperationException();
    }
  }
}
