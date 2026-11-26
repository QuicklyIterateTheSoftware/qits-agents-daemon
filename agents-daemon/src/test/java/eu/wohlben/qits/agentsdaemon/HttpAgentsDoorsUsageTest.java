package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The usage door over HTTP, against a local server: path, bearer and body as qits-agents-service's
 * {@code AgentsWire.UsageReport} reads them, and a non-204 answer as {@link
 * AgentsDoors.Unavailable}. The provider's own recording of the door is the {@code recordAgentUsage}
 * row of {@link AgentsServicePactTest}.
 */
class HttpAgentsDoorsUsageTest {

  HttpServer server;
  final AtomicReference<String> path = new AtomicReference<>();
  final AtomicReference<String> authorization = new AtomicReference<>();
  final AtomicReference<String> body = new AtomicReference<>();
  final AtomicInteger status = new AtomicInteger(204);

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (status.get() == 204) {
            exchange.sendResponseHeaders(204, -1);
          } else {
            byte[] error = "{\"error\":\"no such door\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), error.length);
            exchange.getResponseBody().write(error);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void postsTheReportWithTheAgentsOwnBearer() {
    doors()
        .usage(
            new AgentsDoors.UsageReport(
                "session-1",
                List.of(
                    new AgentsDoors.UsageMessage(
                        "msg_a", "qits-2565", "claude-opus-5-5", 12, 340, 0, 51234,
                        Instant.parse("2026-11-26T10:00:00Z")),
                    new AgentsDoors.UsageMessage(
                        "msg_b", null, "claude-opus-5-5", 1, 2, 3, 4,
                        Instant.parse("2026-11-26T10:01:00Z")))));

    assertEquals("POST /agents/api/projects/qits/agents/developer/usage", path.get());
    assertEquals("Bearer qits_tok_agent", authorization.get());
    JsonObject sent = new JsonObject(body.get());
    assertEquals("session-1", sent.getString("sessionId"));
    JsonObject first = sent.getJsonArray("messages").getJsonObject(0);
    assertEquals(
        new JsonObject()
            .put("messageId", "msg_a")
            .put("workQualifiedId", "qits-2565")
            .put("model", "claude-opus-5-5")
            .put("inputTokens", 12)
            .put("outputTokens", 340)
            .put("cacheCreationTokens", 0)
            .put("cacheReadTokens", 51234)
            .put("at", "2026-11-26T10:00:00Z"),
        first);
    JsonObject second = sent.getJsonArray("messages").getJsonObject(1);
    assertTrue(second.containsKey("workQualifiedId"), "the overseer's own message names null");
    assertEquals(null, second.getValue("workQualifiedId"));
  }

  @Test
  void anAnswerOtherThan204IsUnavailable() {
    status.set(404);
    AgentsDoors.Unavailable failed =
        assertThrows(
            AgentsDoors.Unavailable.class,
            () -> doors().usage(new AgentsDoors.UsageReport("session-1", List.of())));
    assertEquals("usage answered 404: no such door", failed.getMessage());
  }

  private HttpAgentsDoors doors() {
    return new HttpAgentsDoors(
        HttpAgentsDoors.client(),
        "http://127.0.0.1:" + server.getAddress().getPort() + "/agents",
        "qits",
        "developer",
        "qits_tok_agent");
  }
}
