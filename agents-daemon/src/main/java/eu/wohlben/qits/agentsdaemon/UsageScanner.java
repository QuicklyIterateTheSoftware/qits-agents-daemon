package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.json.JsonObject;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
 *   <li>in a subagent's file, the first {@code /workspace/worktrees/<qualified id>/} path its task
 *       prompt (the file's first user message) names. A subagent's lines carry its parent session's
 *       {@code cwd}, not the worktree it works in, so rule 1 alone attributes nothing a subagent
 *       does;
 *   <li>in a nested subagent's file, its parent's item: the {@code parentAgentId} of the {@code
 *       agent-<id>.meta.json} Claude Code writes beside it, resolved by these same rules. A research
 *       subagent of a refinement names the items it reads about, not the one it serves;
 *   <li>the first bare {@code <project>-<n>} its task prompt names;
 *   <li>else none ({@code workQualifiedId} null): the agent's own overhead.
 * </ol>
 *
 * <p>The item is decided per file, once its prompt is read, and holds for every message of it: a
 * subagent resumed for hours keeps appending to its one {@code agent-<id>.jsonl}. Every prompt and
 * meta file of a session is read before its messages, so a parent's item is known first; the
 * subagent files are read by their meta file's {@code spawnDepth}, then by name, so a message id a
 * fork copies from its parent counts once, as the parent's.
 *
 * <p>Offsets live in memory: a new container rescans from 0, and the door's dedupe on {@code
 * (sessionId, messageId)} absorbs the repeats. The door re-attributes a resent message that now
 * names an item (qits-2722), so a rescan also corrects what an earlier daemon attributed. A session's offsets move only once all its batches
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
   * What a subagent file's task prompt and meta file name. Derived from the files' content alone,
   * so it is kept even when the send of its messages fails.
   */
  private static final class Sidechain {
    boolean promptRead;
    /** The first worktree path's item in the prompt. */
    String worktree;
    /** The first bare qualified id in the prompt. */
    String bare;
    boolean metaRead;
    String parentAgentId;
    int spawnDepth = 1;
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
    List<Path> subagents = subagents(state.main, sessionId);
    Map<Path, Long> from = new HashMap<>();
    for (Path file : subagents) {
      long at = state.offsets.getOrDefault(file, 0L);
      if (size(file) < at) {
        at = 0; // replaced: read it again
      }
      if (at == 0) {
        state.sidechains.remove(file);
      }
      from.put(file, at);
      Sidechain sidechain = state.sidechains.computeIfAbsent(file, f -> new Sidechain());
      if (!sidechain.promptRead) {
        readPrompt(file, sidechain);
      }
      if (!sidechain.metaRead) {
        readMeta(file, sidechain);
      }
    }
    Map<Path, String> items = new HashMap<>();
    for (Path file : subagents) {
      items.put(file, item(file, state, 0));
    }
    List<Path> files = new ArrayList<>();
    files.add(state.main);
    subagents.stream()
        .sorted(
            Comparator.comparingInt((Path f) -> state.sidechains.get(f).spawnDepth)
                .thenComparing(f -> f.getFileName().toString()))
        .forEach(files::add);
    for (Path file : files) {
      long start = from.getOrDefault(file, state.offsets.getOrDefault(file, 0L));
      boolean sidechain = !file.equals(state.main);
      String item = sidechain ? items.get(file) : null;
      long to = read(file, start, line -> collect(line, sidechain, item, state, fresh));
      if (to != start) {
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

  private static void collect(
      String line,
      boolean sidechain,
      String item,
      SessionState state,
      Map<String, AgentsDoors.UsageMessage> fresh) {
    TranscriptUsage.of(line)
        .filter(u -> !state.sent.contains(u.messageId()) && !fresh.containsKey(u.messageId()))
        .ifPresent(
            u ->
                fresh.put(
                    u.messageId(),
                    new AgentsDoors.UsageMessage(
                        u.messageId(),
                        attribute(u.cwd(), sidechain, item),
                        u.model(),
                        u.inputTokens(),
                        u.outputTokens(),
                        u.cacheCreationTokens(),
                        u.cacheReadTokens(),
                        u.at() == null ? Instant.now() : u.at())));
  }

  private static String attribute(String cwd, boolean sidechain, String item) {
    String byCwd = workQualifiedId(cwd);
    if (byCwd != null || !sidechain) {
      return byCwd;
    }
    return item;
  }

  /** A subagent file's item: its prompt's worktree, else its parent's item, else its prompt's bare id. */
  private static String item(Path file, SessionState state, int depth) {
    Sidechain sidechain = state.sidechains.get(file);
    if (sidechain == null) {
      return null;
    }
    if (sidechain.worktree != null) {
      return sidechain.worktree;
    }
    if (sidechain.parentAgentId != null && depth < MAX_NESTING) {
      Path parent = file.resolveSibling("agent-" + sidechain.parentAgentId + ".jsonl");
      if (!parent.equals(file)) {
        String inherited = item(parent, state, depth + 1);
        if (inherited != null) {
          return inherited;
        }
      }
    }
    return sidechain.bare;
  }

  /** How many parents up an item is looked for; guards a meta file naming a cycle. */
  static final int MAX_NESTING = 8;

  /** The item a task prompt names: its first worktree path, else its first bare qualified id. */
  String promptWorkQualifiedId(String prompt) {
    String worktree = promptWorktree(prompt);
    return worktree != null ? worktree : promptBare(prompt);
  }

  private static String promptWorktree(String prompt) {
    Matcher worktree = WORKTREE_IN_TEXT.matcher(prompt);
    return worktree.find() ? worktree.group(1) : null;
  }

  private String promptBare(String prompt) {
    Matcher bare = qualifiedId.matcher(prompt);
    return bare.find() ? bare.group(1) : null;
  }

  /** Reads {@code file}'s first user message, complete lines only, and stops there. */
  private void readPrompt(Path file, Sidechain sidechain) {
    readUntil(
        file,
        0,
        line -> {
          Optional<String> prompt = TranscriptUsage.userText(line);
          if (prompt.isEmpty()) {
            return true;
          }
          sidechain.promptRead = true;
          sidechain.worktree = promptWorktree(prompt.get());
          sidechain.bare = promptBare(prompt.get());
          return false;
        });
  }

  /**
   * Reads {@code agent-<id>.meta.json} beside {@code file}: its {@code parentAgentId} and {@code
   * spawnDepth}. Absent or unparseable, the file has no parent and depth 1; it is looked for again
   * on the next tick.
   */
  private static void readMeta(Path file, Sidechain sidechain) {
    String name = file.getFileName().toString();
    Path meta = file.resolveSibling(name.substring(0, name.length() - ".jsonl".length()) + ".meta.json");
    if (!Files.isRegularFile(meta)) {
      return;
    }
    try {
      JsonObject json = new JsonObject(Files.readString(meta, StandardCharsets.UTF_8));
      sidechain.metaRead = true;
      sidechain.parentAgentId =
          json.getValue("parentAgentId") instanceof String id && !id.isBlank() ? id : null;
      sidechain.spawnDepth = json.getValue("spawnDepth") instanceof Number n ? n.intValue() : 1;
    } catch (IOException | RuntimeException e) {
      LOG.debugf("UsageScanner skipped a meta file that does not read: %s", e.getMessage());
    }
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

  /** The main file's subagents' {@code agent-*.jsonl}, sorted by name. */
  private static List<Path> subagents(Path main, String sessionId) {
    List<Path> files = new ArrayList<>();
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
    return readUntil(
        file,
        from,
        line -> {
          lines.accept(line);
          return true;
        });
  }

  /** As {@link #read(Path, long, Consumer)}, stopping after the line {@code lines} answers false for. */
  private static long readUntil(Path file, long from, Predicate<String> lines) {
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
          boolean more = lines.test(line.toString(StandardCharsets.UTF_8));
          line.reset();
          reached = position;
          if (!more) {
            break;
          }
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
