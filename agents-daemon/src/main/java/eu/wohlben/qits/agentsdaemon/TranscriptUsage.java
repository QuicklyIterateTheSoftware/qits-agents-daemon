package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The token usage one Claude Code transcript line carries. Claude Code writes one line per
 * assistant content block, and every line of one message carries the same {@code message.id} and
 * the same {@code message.usage}: the caller counts a {@code message.id} once per session, the first
 * line seen.
 *
 * <p>Only {@code "type":"assistant"} lines with a {@code message.usage} object count. A missing
 * number counts as 0; a line that does not parse is skipped, logged at DEBUG.
 */
final class TranscriptUsage {

  private static final Logger LOG = Logger.getLogger(TranscriptUsage.class);

  /** One line's usage; {@code at} and {@code cwd} are null when the line carries none. */
  record Usage(
      String messageId,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      Instant at,
      String cwd) {}

  private TranscriptUsage() {}

  static Optional<Usage> of(String jsonLine) {
    if (jsonLine == null || jsonLine.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonObject line = new JsonObject(jsonLine);
      if (!"assistant".equals(line.getValue("type"))
          || !(line.getValue("message") instanceof JsonObject message)
          || !(message.getValue("usage") instanceof JsonObject usage)
          || !(message.getValue("id") instanceof String id)
          || id.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(
          new Usage(
              id,
              message.getValue("model") instanceof String model ? model : null,
              number(usage, "input_tokens"),
              number(usage, "output_tokens"),
              number(usage, "cache_creation_input_tokens"),
              number(usage, "cache_read_input_tokens"),
              instant(line.getValue("timestamp")),
              line.getValue("cwd") instanceof String cwd ? cwd : null));
    } catch (RuntimeException e) {
      LOG.debugf("TranscriptUsage skipped a line that does not parse: %s", e.getMessage());
      return Optional.empty();
    }
  }

  private static long number(JsonObject usage, String key) {
    return usage.getValue(key) instanceof Number n ? n.longValue() : 0L;
  }

  private static Instant instant(Object value) {
    if (value instanceof String text) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException e) {
        return null;
      }
    }
    return null;
  }
}
