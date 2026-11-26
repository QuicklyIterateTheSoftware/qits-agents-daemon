package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What this daemon asks qits-agents-service, and why</b>: the runner doors of qits-2518 and the
 * usage door of qits-2521,
 * each for the agent's own row and with the agent's own bearer — the provider state {@value
 * #STATE}, whose {@code authorization} param is that bearer.
 *
 * <ul>
 *   <li>{@code getAgentLaunch}: what to run the agent as — {@code AgentKeeper} at start, every 30 s
 *       and on {@code ProjectAgentChanged};
 *   <li>{@code listAgentTurns}: the undelivered turns, every 10 s and right after a launch;
 *   <li>{@code markAgentTurnDelivered}: once a turn was typed into the session;
 *   <li>{@code reportAgent}: on every session state change;
 *   <li>{@code recordAgentUsage}: the token usage of the session's new assistant messages, by
 *       {@code UsageScanner} as the transcript grows;
 *   <li>{@code getAgent}: the row's {@code generation} and {@code recoveryPrompt}, when a fresh
 *       session past the first generation owes its recovery prompt and no RECOVERY turn of that
 *       generation is pending (one already acknowledged by a container that died). The provider
 *       recorded it in {@value #ROW_STATE}, without a bearer; the door admits {@code qits:agent},
 *       and the daemon sends its own bearer as on every call.
 * </ul>
 *
 * <p>Driven through the real {@link HttpAgentsDoors} against a pact mock server. The pact is {@code
 * pacts/qits-agents-daemon_qits-agents-service.json}; {@code -Dgolden.update=true} rewrites it.
 */
class AgentsServicePactTest {

  static final String CONSUMER = "qits-agents-daemon";

  static final GoldenMasters AGENTS = GoldenMasters.of("qits-agents-service", "qits-agents");

  static final String STATE = "a running agent calling as itself";

  static final GoldenInteraction LAUNCH =
      GoldenInteraction.of(
              Trigger.schedule("every 30 s, and on ProjectAgentChanged"), STATE, "getAgentLaunch")
          .header("Authorization", "{authorization}")
          .consumes(
              "project", "projectId", "name", "generation", "brief", "model", "effort", "sessionId");

  static final GoldenInteraction TURNS =
      GoldenInteraction.of(Trigger.schedule("every 10 s, and after a launch"), STATE, "listAgentTurns")
          .header("Authorization", "{authorization}")
          .consumes("[].id", "[].generation", "[].kind", "[].text", "[].createdAt")
          .anyLength("$");

  static final GoldenInteraction DELIVERED =
      GoldenInteraction.of(
              Trigger.of("call", "call", "a turn typed into the session"),
              STATE,
              "markAgentTurnDelivered")
          .header("Authorization", "{authorization}");

  static final GoldenInteraction REPORT =
      GoldenInteraction.of(Trigger.event("the session's state changed"), STATE, "reportAgent")
          .header("Authorization", "{authorization}");

  static final GoldenInteraction USAGE =
      GoldenInteraction.of(
              Trigger.event("the session's transcript grew"), STATE, "recordAgentUsage")
          .header("Authorization", "{authorization}");

  static final String ROW_STATE = "a running agent";

  static final GoldenInteraction AGENT =
      GoldenInteraction.of(
              Trigger.event("a fresh session owes its generation's recovery prompt"),
              ROW_STATE,
              "getAgent")
          .consumes("generation", "recoveryPrompt");

  static final ConsumerPact PACT =
      ConsumerPact.of(CONSUMER, AGENTS, LAUNCH, TURNS, DELIVERED, REPORT, USAGE, AGENT);

  @Test
  void theKeeperReadsTheLaunch() {
    PACT.run(
        LAUNCH,
        (url, recorded) -> {
          var json = AGENTS.json(STATE, LAUNCH.operationId());
          AgentsDoors.Launch launch = doors(url, recorded.params()).launch();
          assertEquals(json.path("generation").asInt(), launch.generation());
          assertEquals(UUID.fromString(json.path("projectId").asText()), launch.projectId());
          assertEquals(json.path("brief").asText(), launch.brief());
          assertEquals(json.path("sessionId").asText(), launch.sessionId());
          assertEquals("", launch.model(), "null is the platform's default model");
        });
  }

  @Test
  void theKeeperReadsThePendingTurns() {
    PACT.run(
        TURNS,
        (url, recorded) -> {
          var first = AGENTS.json(STATE, TURNS.operationId()).path(0);
          List<AgentsDoors.Turn> turns = doors(url, recorded.params()).pendingTurns();
          assertEquals(1, turns.size());
          assertEquals(UUID.fromString(first.path("id").asText()), turns.getFirst().id());
          assertEquals(first.path("kind").asText(), turns.getFirst().kind());
          assertEquals(first.path("text").asText(), turns.getFirst().text());
        });
  }

  @Test
  void theKeeperAcknowledgesATypedTurn() {
    PACT.run(
        DELIVERED,
        (url, recorded) ->
            doors(url, recorded.params())
                .delivered(UUID.fromString(recorded.params().get("turnId"))));
  }

  @Test
  void theKeeperReportsTheSessionsState() {
    PACT.run(
        REPORT,
        (url, recorded) -> {
          var body = recorded.body();
          doors(url, recorded.params())
              .report(
                  new AgentsDoors.Report(
                      body.path("generation").asInt(),
                      body.path("state").asText(),
                      body.path("sessionId").asText(),
                      null));
        });
  }

  @Test
  void theScannerReportsTheSessionsUsage() {
    PACT.run(
        USAGE,
        (url, recorded) -> {
          var body = recorded.body();
          List<AgentsDoors.UsageMessage> messages = new ArrayList<>();
          for (var message : body.path("messages")) {
            messages.add(
                new AgentsDoors.UsageMessage(
                    message.path("messageId").asText(),
                    message.path("workQualifiedId").isNull()
                        ? null
                        : message.path("workQualifiedId").asText(),
                    message.path("model").asText(),
                    message.path("inputTokens").asLong(),
                    message.path("outputTokens").asLong(),
                    message.path("cacheCreationTokens").asLong(),
                    message.path("cacheReadTokens").asLong(),
                    Instant.parse(message.path("at").asText())));
          }
          doors(url, recorded.params())
              .usage(new AgentsDoors.UsageReport(body.path("sessionId").asText(), messages));
        });
  }

  @Test
  void theKeeperReadsTheRecoveryPromptFromTheRow() {
    PACT.run(
        AGENT,
        (url, recorded) -> {
          var json = AGENTS.json(ROW_STATE, AGENT.operationId());
          AgentsDoors.Agent agent =
              new HttpAgentsDoors(
                      HttpAgentsDoors.client(),
                      url + "/agents",
                      recorded.params().get("project"),
                      recorded.params().get("name"),
                      "qits_tok_contract-developer")
                  .agent();
          assertEquals(json.path("generation").asInt(), agent.generation());
          assertEquals(json.path("recoveryPrompt").asText(), agent.recoveryPrompt());
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(6, PACT.recorded().size(), "every row is recorded: " + PACT.unrecorded());
  }

  /** The doors at the mock's {@code /agents}, with the state's bearer as the agent's token. */
  private static HttpAgentsDoors doors(String url, Map<String, String> params) {
    String token = params.get("authorization").substring("Bearer ".length());
    return new HttpAgentsDoors(
        HttpAgentsDoors.client(), url + "/agents", params.get("project"), params.get("name"), token);
  }
}
