package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The hook mapping: what the keeper hears, and what the harness library is handed. */
class HookWebhookTest {

  record Heard(String commandId, String state, String sessionId, String error) {}

  final List<Heard> heard = new ArrayList<>();
  final List<String> stored = new ArrayList<>();
  final HookWebhook webhook =
      new HookWebhook(
          null,
          0,
          (commandId, state, sessionId, error) -> heard.add(new Heard(commandId, state, sessionId, error)),
          (commandId, state) -> stored.add(state));

  @Test
  void aStopFailureIsAFailedTurnWithItsErrorToTheKeeperAndIdleToTheLibrary() {
    webhook.handle(
        "StopFailure",
        new JsonObject()
            .put("hook_event_name", "StopFailure")
            .put("session_id", "session-1")
            .put("error", "server_error")
            .put("error_details", "Unable to connect to API:\n  Self-signed certificate detected")
            .put("last_assistant_message", "API Error"),
        "cmd-1");

    assertEquals(
        List.of(
            new Heard(
                "cmd-1",
                "FAILED_TURN",
                "session-1",
                "server_error: Unable to connect to API: Self-signed certificate detected")),
        heard);
    assertEquals(List.of("IDLE"), stored, "a failed turn is idle again to the library");
  }

  @Test
  void theErrorTextIsTheCategoryAloneWithoutDetailsAndCappedAtTheLimit() {
    assertEquals("rate_limit", HookWebhook.error(new JsonObject().put("error", "rate_limit").putNull("error_details")));
    assertNull(HookWebhook.error(new JsonObject()));
    String capped = HookWebhook.error(new JsonObject().put("error", "unknown").put("error_details", "x".repeat(500)));
    assertEquals(HookWebhook.ERROR_LIMIT, capped.length());
    assertEquals("unknown: xxx", capped.substring(0, 12));
  }

  @Test
  void onlyAStopIsIdleToTheKeeperTheIdlePromptIsNot() {
    webhook.handle("Stop", new JsonObject(), "cmd-1");
    webhook.handle("Notification", new JsonObject().put("notification_type", "idle_prompt"), "cmd-1");

    assertEquals(List.of("IDLE", "IDLE_PROMPT"), heard.stream().map(Heard::state).toList());
    assertEquals(List.of("IDLE", "IDLE"), stored);
  }

  @Test
  void aStopFailureWinsOverAnOpenPermissionPromptWhereAStopDoesNot() {
    webhook.handle("Notification", new JsonObject().put("notification_type", "permission_prompt"), "cmd-1");
    webhook.handle("Stop", new JsonObject(), "cmd-1");
    webhook.handle("StopFailure", new JsonObject().put("error", "overloaded"), "cmd-1");

    assertEquals(List.of("WAITING", "WAITING", "FAILED_TURN"), heard.stream().map(Heard::state).toList());
    assertEquals(List.of("WAITING", "WAITING", "IDLE"), stored);
    assertEquals("overloaded", heard.getLast().error());
  }
}
