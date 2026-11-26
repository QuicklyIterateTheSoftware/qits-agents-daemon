package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import java.util.Locale;
import java.util.function.BiConsumer;
import org.jboss.logging.Logger;

/**
 * The loopback sink the session's lifecycle hooks POST to — the harness library renders the hook
 * curl to {@code 127.0.0.1:<port>/hooks/claude-code?commandId=…}. Mapped as qits-projects-daemon's
 * {@code HookWebhook} maps it: SessionStart and Stop are IDLE, UserPromptSubmit is BUSY, a
 * Notification is WAITING, SessionEnd is ENDED. A Stop while a permission prompt is open stays
 * WAITING. Two words only the keeper hears, both IDLE to the harness library:
 *
 * <ul>
 *   <li>{@code StopFailure} — Claude's Stop when an API error ended the turn — is {@value
 *       #FAILED_TURN}, its {@code error} and {@code error_details} handed over as one line of at
 *       most {@value #ERROR_LIMIT} characters ({@code server_error: Unable to connect to API}).
 *   <li>The idle-prompt Notification (Claude's "waiting for your input", some 60 s after a turn
 *       ended) is {@value #IDLE_PROMPT}: idle, but no proof that a turn succeeded — it follows a
 *       failed turn as surely as a good one. Only a Stop (or a SessionStart) is IDLE to the keeper.
 * </ul>
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  /** The keeper's word for a turn an API error ended ({@code StopFailure}). */
  static final String FAILED_TURN = "FAILED_TURN";

  /** The keeper's word for the idle-prompt Notification: idle, not a turn that succeeded. */
  static final String IDLE_PROMPT = "IDLE_PROMPT";

  /** The longest error text a failed turn hands over. */
  static final int ERROR_LIMIT = 200;

  /**
   * What a mapped hook is handed to: command id, state, session id (may be null), and the error of
   * a {@value #FAILED_TURN} (null otherwise, and when the hook named none).
   */
  interface Listener {
    void activity(String commandId, String state, String sessionId, String error);
  }

  private final Vertx vertx;
  private final int port;
  private final Listener listener;
  private final BiConsumer<String, String> launchService;
  private final java.util.Map<String, String> lastState = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile HttpServer server;

  /** @param launchService the harness library's {@code onActivity}, for its live renames */
  HookWebhook(Vertx vertx, int port, Listener listener, BiConsumer<String, String> launchService) {
    this.vertx = vertx;
    this.port = port;
    this.listener = listener;
    this.launchService = launchService;
  }

  void start() {
    server = vertx.createHttpServer();
    server
        .requestHandler(this::onRequest)
        .listen(port, "127.0.0.1")
        .onSuccess(s -> LOG.infof("HookWebhook listening on 127.0.0.1:%d", port))
        .onFailure(t -> LOG.errorf(t, "HookWebhook failed to bind :%d", port));
  }

  void close() {
    HttpServer s = server;
    if (s != null) {
      s.close();
    }
  }

  private void onRequest(HttpServerRequest request) {
    if (request.method() != HttpMethod.POST || !PATH.equals(request.path())) {
      request.response().setStatusCode(404).end();
      return;
    }
    String commandId = request.getParam("commandId");
    request.bodyHandler(
        body -> {
          try {
            JsonObject json = body.length() == 0 ? new JsonObject() : new JsonObject(body);
            handle(json.getString("hook_event_name"), json, commandId);
          } catch (RuntimeException e) {
            LOG.debugf("HookWebhook dropped an undecodable hook payload: %s", e.getMessage());
          }
          request.response().setStatusCode(200).end();
        });
  }

  void handle(String hookEvent, JsonObject body, String commandId) {
    if (commandId == null || commandId.isBlank()) {
      return;
    }
    boolean idlePrompt = "Notification".equals(hookEvent) && isIdlePrompt(body);
    String state = idlePrompt ? IDLE_PROMPT : map(hookEvent);
    if (state == null) {
      return;
    }
    // A StopFailure is not held at WAITING as a Stop is: an API error ended the turn, so no
    // permission prompt of it can still be open, and a WAITING left over from a prompt answered
    // earlier in the turn (it lasts until the next UserPromptSubmit) would hide the failure.
    if (("Stop".equals(hookEvent) || idlePrompt) && "WAITING".equals(lastState.get(commandId))) {
      state = "WAITING";
    }
    // The harness library knows IDLE, BUSY, WAITING and ENDED: the keeper's two words are IDLE there.
    String stored = IDLE_PROMPT.equals(state) || FAILED_TURN.equals(state) ? "IDLE" : state;
    if ("ENDED".equals(stored)) {
      lastState.remove(commandId);
    } else {
      lastState.put(commandId, stored);
    }
    try {
      launchService.accept(commandId, stored);
    } catch (RuntimeException e) {
      LOG.debugf("onActivity failed for %s: %s", commandId, e.getMessage());
    }
    String error = FAILED_TURN.equals(state) ? error(body) : null;
    listener.activity(commandId, state, body.getString("session_id"), error);
  }

  /**
   * A StopFailure's {@code error} and {@code error_details}, as {@code <error>: <details>} on one
   * line of at most {@value #ERROR_LIMIT} characters; null when it names neither.
   */
  static String error(JsonObject body) {
    String category = text(body.getValue("error"));
    String details = text(body.getValue("error_details"));
    String said =
        category == null ? details : details == null ? category : category + ": " + details;
    if (said == null) {
      return null;
    }
    return said.length() > ERROR_LIMIT ? said.substring(0, ERROR_LIMIT) : said;
  }

  /** {@code value} as one trimmed line, or null when it is absent, blank or not a string. */
  private static String text(Object value) {
    if (!(value instanceof String raw)) {
      return null;
    }
    String line = raw.replaceAll("\\s+", " ").trim();
    return line.isEmpty() ? null : line;
  }

  static boolean isIdlePrompt(JsonObject body) {
    Object type = body.getValue("notification_type");
    if (type != null) {
      return "idle_prompt".equals(type);
    }
    return body.getValue("message") instanceof String text
        && text.toLowerCase(Locale.ROOT).contains("waiting for your input");
  }

  private static String map(String hookEvent) {
    if (hookEvent == null) {
      return null;
    }
    return switch (hookEvent) {
      case "SessionStart", "Stop" -> "IDLE";
      case "StopFailure" -> FAILED_TURN;
      case "UserPromptSubmit" -> "BUSY";
      case "Notification" -> "WAITING";
      case "SessionEnd" -> "ENDED";
      default -> null;
    };
  }
}
