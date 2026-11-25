package eu.wohlben.qits.agentsdaemon;

import java.util.List;
import java.util.UUID;

/**
 * qits-agents-service's four runner doors, and the agent's own row, as this daemon calls them:
 * always for its own agent and always with its own bearer. The seam {@code AgentKeeper} is tested
 * through.
 */
interface AgentsDoors {

  /** What {@code launch} answers: what to run the agent as, at which generation. */
  record Launch(
      String project,
      UUID projectId,
      String name,
      int generation,
      String brief,
      String model,
      String effort,
      String sessionId) {

    public Launch {
      brief = brief == null ? "" : brief;
      model = model == null ? "" : model;
      effort = effort == null ? "" : effort;
      sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
    }
  }

  /** One turn of the agent's inbox. */
  record Turn(UUID id, int generation, String kind, String text, String createdAt) {

    static final String RECOVERY = "RECOVERY";

    public boolean recovery() {
      return RECOVERY.equals(kind);
    }
  }

  /** What the runtime says the agent is doing. {@code sessionId} and {@code detail} may be null. */
  record Report(int generation, String state, String sessionId, String detail) {

    static final String STARTING = "STARTING";
    static final String IDLE = "IDLE";
    static final String BUSY = "BUSY";
    static final String EXITED = "EXITED";
    static final String FAILED = "FAILED";
  }

  /** What the agent's row says of its current generation: the recovery prompt a fresh session begins with. */
  record Agent(int generation, String recoveryPrompt) {

    public Agent {
      recoveryPrompt = recoveryPrompt == null ? "" : recoveryPrompt;
    }
  }

  /** {@code launch} answered 409: the agent is RETIRED (or holds no credential yet). */
  final class Retired extends RuntimeException {
    public Retired(String message) {
      super(message);
    }
  }

  /** A door answered something this daemon cannot use; the caller tries again later. */
  final class Unavailable extends RuntimeException {
    public Unavailable(String message) {
      super(message);
    }

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** {@code GET /projects/{p}/agents/{name}/launch}. */
  Launch launch();

  /** {@code GET /projects/{p}/agents/{name}/turns} — the undelivered turns, oldest first. */
  List<Turn> pendingTurns();

  /** {@code POST /projects/{p}/agents/{name}/turns/{turnId}/delivered}. */
  void delivered(UUID turnId);

  /** {@code PUT /projects/{p}/agents/{name}/report}. */
  void report(Report report);

  /** {@code GET /projects/{p}/agents/{name}}: the row, for its generation's recovery prompt. */
  Agent agent();
}
