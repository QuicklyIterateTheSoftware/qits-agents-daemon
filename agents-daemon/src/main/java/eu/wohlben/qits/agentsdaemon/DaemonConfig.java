package eu.wohlben.qits.agentsdaemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the container is told, read from its environment ({@code AgentSpecs} in qits-agents-service
 * composes it). The project id is not among it: it comes from {@code launch}.
 *
 * @param project {@code QITS_AGENTS_DAEMON_PROJECT}, the project slug
 * @param agentName {@code QITS_AGENTS_DAEMON_AGENT_NAME}
 * @param wrapper {@code QITS_AGENTS_DAEMON_WRAPPER}, the wrapper repository's name
 * @param gitBase {@code QITS_AGENTS_DAEMON_GIT_BASE}, {@code https://githost.qits.<domain>/git/<slug>}
 * @param serviceUrl {@code QITS_AGENTS_DAEMON_SERVICE_URL}, {@code https://agents.qits.<domain>/agents}
 * @param token {@code QITS_TOKEN}, the agent's own commissioned token
 * @param domain {@code QITS_DOMAIN}
 * @param eventsUrl qits-events' base: {@code QITS_AGENTS_DAEMON_EVENTS_URL}, else {@code
 *     https://events.qits.<domain>}
 * @param projectsUrl qits-projects' base: {@code QITS_AGENTS_DAEMON_PROJECTS_URL}, else {@code
 *     https://projects.qits.<domain>}
 * @param platformMcpUrl the {@code qits} MCP server: {@code QITS_PLATFORM_MCP_URL}, else {@code
 *     https://mcp.qits.<domain>/mcp}, the address the desk's container is given
 * @param workspace where the work volume mounts: {@code /workspace}
 * @param claudeMount the shared credential volume: {@code /claude-home}
 */
record DaemonConfig(
    String project,
    String agentName,
    String wrapper,
    String gitBase,
    String serviceUrl,
    String token,
    String domain,
    String eventsUrl,
    String projectsUrl,
    Optional<String> platformMcpUrl,
    Path workspace,
    String claudeMount,
    int hooksPort) {

  static final int HOOKS_PORT = 13337;

  static DaemonConfig from(Map<String, String> env) {
    String domain = value(env, "QITS_DOMAIN");
    String events = value(env, "QITS_AGENTS_DAEMON_EVENTS_URL");
    String projects = value(env, "QITS_AGENTS_DAEMON_PROJECTS_URL");
    String mcp = value(env, "QITS_PLATFORM_MCP_URL");
    return new DaemonConfig(
        value(env, "QITS_AGENTS_DAEMON_PROJECT"),
        value(env, "QITS_AGENTS_DAEMON_AGENT_NAME"),
        value(env, "QITS_AGENTS_DAEMON_WRAPPER"),
        value(env, "QITS_AGENTS_DAEMON_GIT_BASE"),
        value(env, "QITS_AGENTS_DAEMON_SERVICE_URL"),
        value(env, "QITS_TOKEN"),
        domain,
        events != null ? events : domain == null ? null : "https://events.qits." + domain,
        projects != null ? projects : domain == null ? null : "https://projects.qits." + domain,
        Optional.ofNullable(mcp != null ? mcp : domain == null ? null : "https://mcp.qits." + domain + "/mcp"),
        Path.of(env.getOrDefault("QITS_AGENTS_DAEMON_WORKSPACE", "/workspace")),
        env.getOrDefault("QITS_AGENTS_DAEMON_CLAUDE_MOUNT", "/claude-home"),
        HOOKS_PORT);
  }

  /** The names that must be set for the daemon to do anything; empty when all are. */
  List<String> missing() {
    List<String> missing = new ArrayList<>();
    if (project == null) missing.add("QITS_AGENTS_DAEMON_PROJECT");
    if (agentName == null) missing.add("QITS_AGENTS_DAEMON_AGENT_NAME");
    if (wrapper == null) missing.add("QITS_AGENTS_DAEMON_WRAPPER");
    if (gitBase == null) missing.add("QITS_AGENTS_DAEMON_GIT_BASE");
    if (serviceUrl == null) missing.add("QITS_AGENTS_DAEMON_SERVICE_URL");
    if (token == null) missing.add("QITS_TOKEN");
    if (eventsUrl == null || projectsUrl == null) missing.add("QITS_DOMAIN");
    return missing;
  }

  /** The wrapper checkout: {@code /workspace/<wrapper>}. */
  Path checkout() {
    return workspace.resolve(wrapper);
  }

  private static String value(Map<String, String> env, String name) {
    String value = env.get(name);
    return value == null || value.isBlank() ? null : value.strip();
  }
}
