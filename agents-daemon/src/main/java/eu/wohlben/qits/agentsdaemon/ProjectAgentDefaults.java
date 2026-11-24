package eu.wohlben.qits.agentsdaemon;

import eu.wohlben.qits.agents.AgentConfigurationDocument;
import eu.wohlben.qits.agents.AgentDefaults;
import eu.wohlben.qits.agents.AgentSurface;
import eu.wohlben.qits.agents.AgentSurfaceConfiguration;
import eu.wohlben.qits.agents.AgentSurfaceConfigurations;
import eu.wohlben.qits.agents.AgentType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The harness library's defaults seam. Its one moving part is the {@code project.agent}
 * configuration the next launch renders — model, effort and the appended system prompt come from
 * {@code launch} and change with the generation, so {@link AgentKeeper}'s plan sets it right before
 * each launch ({@link #use}). The library resolves the configuration once per launch.
 */
final class ProjectAgentDefaults implements AgentDefaults {

  private final Optional<String> domain;
  private volatile AgentSurfaceConfigurations configurations = AgentSurfaceConfigurations.shipped();

  ProjectAgentDefaults(String domain) {
    this.domain = Optional.ofNullable(domain);
  }

  /** The configuration the next {@code project.agent} launch renders. */
  void use(AgentSurfaceConfiguration configuration) {
    configurations =
        AgentSurfaceConfigurations.of(
            new AgentConfigurationDocument(
                AgentConfigurationDocument.CURRENT_VERSION,
                Instant.now().toString(),
                Map.of(AgentSurface.PROJECT_AGENT.key(), configuration)));
  }

  @Override
  public AgentType defaultAgentType() {
    return AgentType.CLAUDE;
  }

  @Override
  public boolean activityTrackingEnabled() {
    return true;
  }

  @Override
  public Optional<String> refinementModel() {
    return Optional.empty();
  }

  @Override
  public AgentSurfaceConfigurations surfaceConfigurations() {
    return configurations;
  }

  @Override
  public Optional<String> platformDomain() {
    return domain;
  }
}
