package eu.wohlben.qits.agentsdaemon;

import eu.wohlben.qits.agents.AgentMcpScope;
import eu.wohlben.qits.agents.AgentMcpServers;
import eu.wohlben.qits.agents.LocalMcp;
import eu.wohlben.qits.agents.ScopedMcp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP servers the session gets, as {@code WorkspaceMcpServers} in qits-workspace-daemon
 * configures them: the central {@code qits} server with the agent's own bearer, and the local
 * {@code browser} server ({@code qits-browser-mcp}, in the toolchain image).
 */
final class AgentMcp implements AgentMcpServers {

  static final String PLATFORM_KEY = "qits";

  static final LocalMcp BROWSER = new LocalMcp("browser", "qits-browser-mcp", List.of("mcp__browser__*"));

  private final Optional<String> url;
  private final Map<String, String> headers;

  AgentMcp(Optional<String> url, String token) {
    this.url = url.map(String::strip).filter(value -> !value.isEmpty());
    this.headers = token == null || token.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + token.strip());
  }

  @Override
  public List<ScopedMcp> serversFor(AgentMcpScope scope) {
    return url.map(u -> List.of(new ScopedMcp(PLATFORM_KEY, u, List.of(), headers))).orElse(List.of());
  }

  @Override
  public List<LocalMcp> localServers() {
    return List.of(BROWSER);
  }

  @Override
  public Map<String, String> platformHeaders() {
    return headers;
  }
}
