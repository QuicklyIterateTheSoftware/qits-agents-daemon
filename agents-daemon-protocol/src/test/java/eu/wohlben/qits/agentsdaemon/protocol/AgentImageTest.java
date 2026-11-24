package eu.wohlben.qits.agentsdaemon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves, and it resolves to a CalVer — the guard on this module's resource filtering.
 * The shape is asserted, not a value: the version is whatever release stamped this tree.
 */
class AgentImageTest {

  @Test
  void theVersionIsFilteredInAndIsACalVer() {
    String version = AgentImage.VERSION;
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9]"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theRepositoryIsTheOneThePipelinePublishes() {
    // A cross-repository contract: `qits/agent` is what `.config/qits/release.yml` declares and
    // pushes, and what qits-agents-service composes the image reference from.
    assertEquals("qits/agent", AgentImage.REPOSITORY);
  }
}
