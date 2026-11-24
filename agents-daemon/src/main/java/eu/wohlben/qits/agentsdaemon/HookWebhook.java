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
 * Notification is WAITING (unless it is the idle prompt, which is IDLE), SessionEnd is ENDED. A Stop
 * while a permission prompt is open stays WAITING.
 */
final class HookWebhook {

  private static final Logger LOG = Logger.getLogger(HookWebhook.class);

  static final String PATH = "/hooks/claude-code";

  /** What a mapped hook is handed to: command id, state, session id (may be null). */
  interface Listener {
    void activity(String commandId, String state, String sessionId);
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
    String state = idlePrompt ? "IDLE" : map(hookEvent);
    if (state == null) {
      return;
    }
    if (("Stop".equals(hookEvent) || idlePrompt) && "WAITING".equals(lastState.get(commandId))) {
      state = "WAITING";
    }
    if ("ENDED".equals(state)) {
      lastState.remove(commandId);
    } else {
      lastState.put(commandId, state);
    }
    try {
      launchService.accept(commandId, state);
    } catch (RuntimeException e) {
      LOG.debugf("onActivity failed for %s: %s", commandId, e.getMessage());
    }
    listener.activity(commandId, state, body.getString("session_id"));
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
      case "UserPromptSubmit" -> "BUSY";
      case "Notification" -> "WAITING";
      case "SessionEnd" -> "ENDED";
      default -> null;
    };
  }
}
