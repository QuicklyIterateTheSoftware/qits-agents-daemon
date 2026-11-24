package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DaemonConfigTest {

  @Test
  void theContainersEnvironmentIsReadAndTheRestDerivedFromTheDomain() {
    DaemonConfig config =
        DaemonConfig.from(
            Map.of(
                "QITS_AGENTS_DAEMON_PROJECT", "qits",
                "QITS_AGENTS_DAEMON_AGENT_NAME", "developer",
                "QITS_AGENTS_DAEMON_WRAPPER", "qits-qits",
                "QITS_AGENTS_DAEMON_GIT_BASE", "https://githost.qits.example.test/git/qits",
                "QITS_AGENTS_DAEMON_SERVICE_URL", "https://agents.qits.example.test/agents",
                "QITS_TOKEN", "qits_tok_x",
                "QITS_DOMAIN", "example.test"));

    assertEquals(List.of(), config.missing());
    assertEquals(Path.of("/workspace/qits-qits"), config.checkout());
    assertEquals("https://events.qits.example.test", config.eventsUrl());
    assertEquals("https://projects.qits.example.test", config.projectsUrl());
    assertEquals(Optional.of("https://mcp.qits.example.test/mcp"), config.platformMcpUrl());
    assertEquals("/claude-home", config.claudeMount());
  }

  @Test
  void whatIsMissingIsNamed() {
    assertEquals(
        List.of(
            "QITS_AGENTS_DAEMON_PROJECT",
            "QITS_AGENTS_DAEMON_AGENT_NAME",
            "QITS_AGENTS_DAEMON_WRAPPER",
            "QITS_AGENTS_DAEMON_GIT_BASE",
            "QITS_AGENTS_DAEMON_SERVICE_URL",
            "QITS_TOKEN",
            "QITS_DOMAIN"),
        DaemonConfig.from(Map.of()).missing());
  }
}
