package eu.wohlben.qits.agentsdaemon;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * Reports the token usage of the agent's sessions to qits-agents-service's usage door, every
 * {@value #POLL_MS} ms. Covers the session {@link AgentKeeper} runs and every earlier session the
 * {@link SessionLedger} places at the running generation.
 *
 * <p>A session's transcripts are {@code <claudeMount>/.claude/projects/<escaped-cwd>/<sessionId>.jsonl}
 * and its subagents' {@code <sessionId>/subagents/agent-*.jsonl} beside it, the files {@code
 * AgentTranscriptService} imports. Each is read from the offset this scanner reached, complete lines
 * only, through {@link TranscriptUsage}; a {@code message.id} counts once per session.
 *
 * <p>A message is attributed to a work item, in this order:
 *
 * <ol>
 *   <li>its line's own {@code cwd} under {@code /workspace/worktrees/<qualified id>/}, the worktrees
 *       {@link OverseerBrief} prescribes;
 *   <li>in a subagent's file, the item its task prompt (the file's first user message) names: the
 *       first {@code /workspace/worktrees/<qualified id>/} path in it, else the first bare {@code
 *       <project>-<n>}. A subagent's lines carry its parent session's {@code cwd}, not the worktree
 *       it works in, so rule 1 alone attributes nothing a subagent does;
 *   <li>else none ({@code workQualifiedId} null): the agent's own overhead.
 * </ol>
 *
 * <p>Offsets live in memory: a new container rescans from 0, and the door's dedupe on {@code
 * (sessionId, messageId)} absorbs the repeats. A session's offsets move only once all its batches
 * were accepted, so a failed call is retried whole on the next tick. A failure is logged at WARN once
 * per streak and never thrown.
 */
final class UsageScanner {

  private static final Logger LOG = Logger.getLogger(UsageScanner.class);

  static final long POLL_MS = 60_000;

  static final Pattern WORKTREE = Pattern.compile("^/workspace/worktrees/([a-z][a-z0-9-]*-[0-9]+)(/|$)");

  /** A worktree path anywhere in a task prompt. */
  static final Pattern WORKTREE_IN_TEXT = Pattern.compile("/workspace/worktrees/([a-z][a-z0-9-]*-[0-9]+)/");

  private final Path projectsRoot;
  private final Supplier<Collection<String>> sessions;
  private final AgentsDoors doors;
  /** A bare qualified id of this project, {@code <project>-<n>}, in a task prompt. */
  private final Pattern qualifiedId;

  /** Per session: where each of its files was read up to, and the message ids already sent. */
  private final Map<String, SessionState> states = new HashMap<>();

  private boolean failing;

  private static final class SessionState {
    Path main;
    final Map<Path, Long> offsets = new HashMap<>();
    final Set<String> sent = new HashSet<>();
    /** Per subagent file: the item its task prompt names, once that prompt was read. */
    final Map<Path, Sidechain> sidechains = new HashMap<>();
  }

  /**
   * What a subagent file's task prompt names. Derived from the file's content alone, so it is kept
   * even when the send of its messages fails.
   */
  private static final class Sidechain {
    boolean promptRead;
    String workQualifiedId;
  }

  /**
   * @param projectsRoot {@code <claudeMount>/.claude/projects}
   * @param project the project's slug, the prefix of its qualified ids
   * @param sessions the session ids to cover, read on every tick
   */
  UsageScanner(
      Path projectsRoot, String project, Supplier<Collection<String>> sessions, AgentsDoors doors) {
    this.projectsRoot = projectsRoot;
    this.qualifiedId = Pattern.compile("\\b(" + Pattern.quote(project) + "-[0-9]+)\\b");
    this.sessions = sessions;
    this.doors = doors;
  }

  /** One pass over the sessions; never throws. */
  void tick() {
    try {
      for (String sessionId : new LinkedHashSet<>(sessions.get())) {
        if (sessionId != null && !sessionId.isBlank() && !scan(sessionId)) {
          return;
        }
      }
      if (failing) {
        failing = false;
        LOG.info("UsageScanner: the usage door accepts reports again");
      }
    } catch (RuntimeException e) {
      failed(e);
    }
  }

  /** Reads and sends what is new in {@code sessionId}'s files; false when a send failed. */
  private boolean scan(String sessionId) {
    SessionState state = states.computeIfAbsent(sessionId, id -> new SessionState());
    if (state.main == null || !Files.isRegularFile(state.main)) {
      state.main = find(sessionId + ".jsonl");
      if (state.main == null) {
        return true;
      }
    }
    Map<Path, Long> reached = new HashMap<>();
    Map<String, AgentsDoors.UsageMessage> fresh = new LinkedHashMap<>();
    for (Path file : files(state.main, sessionId)) {
      long from = state.offsets.getOrDefault(file, 0L);
      if (size(file) < from) {
        from = 0; // replaced: read it again
      }
      Sidechain sidechain = null;
      if (!file.equals(state.main)) {
        if (from == 0) {
          state.sidechains.remove(file);
        }
        sidechain = state.sidechains.computeIfAbsent(file, f -> new Sidechain());
      }
      Sidechain within = sidechain;
      long to = read(file, from, line -> collect(line, within, state, fresh));
      if (to != from) {
        reached.put(file, to);
      }
    }
    List<AgentsDoors.UsageMessage> messages = new ArrayList<>(fresh.values());
    for (int i = 0; i < messages.size(); i += AgentsDoors.UsageReport.MAX) {
      List<AgentsDoors.UsageMessage> batch =
          messages.subList(i, Math.min(messages.size(), i + AgentsDoors.UsageReport.MAX));
      try {
        doors.usage(new AgentsDoors.UsageReport(sessionId, List.copyOf(batch)));
      } catch (RuntimeException e) {
        failed(e);
        return false;
      }
    }
    state.offsets.putAll(reached);
    state.sent.addAll(fresh.keySet());
    if (!messages.isEmpty()) {
      LOG.debugf("UsageScanner reported %d messages of session %s", messages.size(), sessionId);
    }
    return true;
  }

  private void collect(
      String line,
      Sidechain sidechain,
      SessionState state,
      Map<String, AgentsDoors.UsageMessage> fresh) {
    if (sidechain != null && !sidechain.promptRead) {
      TranscriptUsage.userText(line)
          .ifPresent(
              prompt -> {
                sidechain.promptRead = true;
                sidechain.workQualifiedId = promptWorkQualifiedId(prompt);
              });
    }
    TranscriptUsage.of(line)
        .filter(u -> !state.sent.contains(u.messageId()) && !fresh.containsKey(u.messageId()))
        .ifPresent(
            u ->
                fresh.put(
                    u.messageId(),
                    new AgentsDoors.UsageMessage(
                        u.messageId(),
                        attribute(u.cwd(), sidechain),
                        u.model(),
                        u.inputTokens(),
                        u.outputTokens(),
                        u.cacheCreationTokens(),
                        u.cacheReadTokens(),
                        u.at() == null ? Instant.now() : u.at())));
  }

  private static String attribute(String cwd, Sidechain sidechain) {
    String byCwd = workQualifiedId(cwd);
    if (byCwd != null || sidechain == null) {
      return byCwd;
    }
    return sidechain.workQualifiedId;
  }

  /** The item a task prompt names: its first worktree path, else its first bare qualified id. */
  String promptWorkQualifiedId(String prompt) {
    Matcher worktree = WORKTREE_IN_TEXT.matcher(prompt);
    if (worktree.find()) {
      return worktree.group(1);
    }
    Matcher bare = qualifiedId.matcher(prompt);
    return bare.find() ? bare.group(1) : null;
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The work item a working directory under {@code /workspace/worktrees/<qid>/} names, else null. */
  static String workQualifiedId(String cwd) {
    if (cwd == null) {
      return null;
    }
    Matcher m = WORKTREE.matcher(cwd);
    return m.find() ? m.group(1) : null;
  }

  private void failed(RuntimeException e) {
    if (!failing) {
      failing = true;
      LOG.warnf("UsageScanner: reporting usage failed (%s); retrying every %d s", e.getMessage(), POLL_MS / 1000);
    }
  }

  /** The main file and, sorted by name, its subagents' {@code agent-*.jsonl}. */
  private static List<Path> files(Path main, String sessionId) {
    List<Path> files = new ArrayList<>();
    files.add(main);
    Path subagents = main.resolveSibling(sessionId).resolve("subagents");
    if (Files.isDirectory(subagents)) {
      try (Stream<Path> list = Files.list(subagents)) {
        list.filter(p -> p.getFileName().toString().matches("agent-.*\\.jsonl"))
            .filter(Files::isRegularFile)
            .sorted()
            .forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }

  /** {@code projects/<escaped-cwd>/<filename>}: depth 2, one spare level, as the library searches. */
  private Path find(String filename) {
    if (!Files.isDirectory(projectsRoot)) {
      return null;
    }
    try (Stream<Path> walk = Files.walk(projectsRoot, 3)) {
      return walk.filter(p -> p.getFileName().toString().equals(filename))
          .filter(Files::isRegularFile)
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Hands every complete line of {@code file} from byte {@code from} on to {@code lines}; answers
   * the offset just past the last complete line. A file shorter than {@code from} (replaced) is read
   * from 0.
   */
  private static long read(Path file, long from, Consumer<String> lines) {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size < from) {
        from = 0;
      }
      if (size == from) {
        return from;
      }
      channel.position(from);
      long reached = from;
      long position = from;
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      InputStream in = new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
      int b;
      while (position < size && (b = in.read()) != -1) {
        position++;
        if (b == '\n') {
          lines.accept(line.toString(StandardCharsets.UTF_8));
          line.reset();
          reached = position;
        } else {
          line.write(b);
        }
      }
      return reached;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
