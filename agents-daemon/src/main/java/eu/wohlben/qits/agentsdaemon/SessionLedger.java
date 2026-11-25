package eu.wohlben.qits.agentsdaemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.jboss.logging.Logger;

/**
 * Which generation each of the agent's sessions belongs to, kept on the work volume ({@code
 * /workspace/.qits-agents-daemon/sessions}) so that it outlives the container. The container is
 * replaced at any moment — a spec roll, a new image, a rotated token — and a new one must not
 * resume a session it cannot vouch for: {@code launch.sessionId} is only the last id the service
 * was told, and at a generation change it still names the previous generation's session.
 *
 * <p>A session id is written here when the keeper learns it from a session it launched for a
 * generation (fresh, or resumed because this ledger already placed it there). {@link AgentKeeper}
 * resumes a session only when this ledger says it belongs to the launch's generation, and never
 * reports a session id under another generation.
 *
 * <p>One line per session, {@code <generation> <sessionId>}, the newest last; the {@value #KEEP}
 * newest are kept. Written to a temp file and moved over the old one. A write that fails is logged
 * and the entry kept in memory, so a crash relaunch in the same container still resumes.
 */
final class SessionLedger {

  private static final Logger LOG = Logger.getLogger(SessionLedger.class);

  static final int KEEP = 32;

  private final Path file;
  private final LinkedHashMap<String, Integer> entries = new LinkedHashMap<>();

  private SessionLedger(Path file) {
    this.file = file;
  }

  /** A ledger held in memory only. */
  static SessionLedger inMemory() {
    return new SessionLedger(null);
  }

  /** The ledger at {@code file}, read now; absent or unreadable reads as empty. */
  static SessionLedger at(Path file) {
    SessionLedger ledger = new SessionLedger(file);
    if (Files.isRegularFile(file)) {
      try {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          String[] parts = line.strip().split("\\s+");
          if (parts.length == 2 && !parts[1].isBlank()) {
            try {
              ledger.entries.put(parts[1], Integer.parseInt(parts[0]));
            } catch (NumberFormatException skipped) {
              // not a line of ours
            }
          }
        }
      } catch (IOException e) {
        LOG.warnf("SessionLedger could not read %s (%s); no session will be resumed", file, e.getMessage());
      }
    }
    LOG.infof("SessionLedger holds %d sessions (%s)", ledger.entries.size(), file);
    return ledger;
  }

  /** The generation {@code sessionId} belongs to; empty when this ledger does not know it. */
  synchronized OptionalInt generationOf(String sessionId) {
    Integer generation = sessionId == null ? null : entries.get(sessionId);
    return generation == null ? OptionalInt.empty() : OptionalInt.of(generation);
  }

  /** Records that {@code sessionId} belongs to {@code generation}. */
  synchronized void record(String sessionId, int generation) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    Integer known = entries.get(sessionId);
    if (known != null && known == generation) {
      return;
    }
    entries.remove(sessionId);
    entries.put(sessionId, generation);
    Iterator<String> oldest = entries.keySet().iterator();
    while (entries.size() > KEEP && oldest.hasNext()) {
      oldest.next();
      oldest.remove();
    }
    LOG.infof("SessionLedger recorded %s at generation %d", sessionId, generation);
    write();
  }

  private void write() {
    if (file == null) {
      return;
    }
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : entries.entrySet()) {
      lines.add(entry.getValue() + " " + entry.getKey());
    }
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.write(temp, lines, StandardCharsets.UTF_8);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warnf("SessionLedger could not write %s: %s", file, e.getMessage());
    }
  }
}
