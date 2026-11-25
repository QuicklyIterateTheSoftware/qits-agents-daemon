package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The doors over the JDK's HTTP client, at {@code <serviceUrl>/api} — {@code
 * QITS_AGENTS_DAEMON_SERVICE_URL} is {@code https://agents.qits.<domain>/agents}, the public edge.
 * Every call carries {@code Authorization: Bearer $QITS_TOKEN}, the agent's own commissioned token,
 * which the doors admit for the agent's own row.
 *
 * <p>The turns are read without {@code ?pending=true}: {@code pending} defaults to true on the door,
 * and the request the provider recorded (and the consumer pact pins) carries no query.
 *
 * <p>Never logs the token.
 */
final class HttpAgentsDoors implements AgentsDoors {

  static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final HttpClient http;
  private final String agentBase;
  private final String bearer;

  HttpAgentsDoors(HttpClient http, String serviceUrl, String project, String name, String token) {
    this.http = http;
    this.agentBase =
        Checkout.trimSlash(serviceUrl)
            + "/api/projects/"
            + segment(project)
            + "/agents/"
            + segment(name);
    this.bearer = "Bearer " + token;
  }

  /** HTTP/1.1: the edge speaks it, and a plain-http peer (a test's mock) is not asked to upgrade. */
  static HttpClient client() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
  }

  @Override
  public Launch launch() {
    HttpResponse<String> response = send(get("/launch"));
    if (response.statusCode() == 409) {
      throw new Retired("launch answered 409: " + error(response));
    }
    JsonObject body = object(expect(response, 200, "launch"));
    String projectId = body.getString("projectId");
    return new Launch(
        body.getString("project"),
        projectId == null ? null : UUID.fromString(projectId),
        body.getString("name"),
        body.getInteger("generation", 0),
        body.getString("brief"),
        body.getString("model"),
        body.getString("effort"),
        body.getString("sessionId"));
  }

  @Override
  public List<Turn> pendingTurns() {
    String body = expect(send(get("/turns")), 200, "turns");
    JsonArray array;
    try {
      array = new JsonArray(body);
    } catch (RuntimeException e) {
      throw new Unavailable("turns answered no JSON array", e);
    }
    List<Turn> turns = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonObject turn = array.getJsonObject(i);
      turns.add(
          new Turn(
              UUID.fromString(turn.getString("id")),
              turn.getInteger("generation", 0),
              turn.getString("kind"),
              turn.getString("text", ""),
              turn.getString("createdAt")));
    }
    return turns;
  }

  @Override
  public void delivered(UUID turnId) {
    HttpRequest request =
        base("/turns/" + turnId + "/delivered").POST(HttpRequest.BodyPublishers.noBody()).build();
    expect(send(request), 204, "delivered");
  }

  @Override
  public void report(Report report) {
    JsonObject body = new JsonObject().put("generation", report.generation()).put("state", report.state());
    if (report.sessionId() != null) {
      body.put("sessionId", report.sessionId());
    }
    if (report.detail() != null) {
      body.put("detail", report.detail());
    }
    HttpRequest request =
        base("/report")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body.encode()))
            .build();
    expect(send(request), 204, "report");
  }

  @Override
  public Agent agent() {
    JsonObject body = object(expect(send(get("")), 200, "agent"));
    return new Agent(body.getInteger("generation", 0), body.getString("recoveryPrompt"));
  }

  private HttpRequest get(String path) {
    return base(path).header("Accept", "application/json").GET().build();
  }

  private HttpRequest.Builder base(String path) {
    return HttpRequest.newBuilder(URI.create(agentBase + path))
        .timeout(TIMEOUT)
        .header("Authorization", bearer);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new Unavailable(request.method() + " " + request.uri().getPath() + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable("interrupted", e);
    }
  }

  private static String expect(HttpResponse<String> response, int status, String door) {
    if (response.statusCode() != status) {
      throw new Unavailable(door + " answered " + response.statusCode() + ": " + error(response));
    }
    return response.body();
  }

  private static JsonObject object(String body) {
    try {
      return new JsonObject(body);
    } catch (RuntimeException e) {
      throw new Unavailable("answered no JSON object", e);
    }
  }

  /** The service's {@code {error}} text, else the start of the body. */
  private static String error(HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body();
    try {
      String error = new JsonObject(body).getString("error");
      if (error != null) {
        return error;
      }
    } catch (RuntimeException notJson) {
      // fall through
    }
    return body.length() > 200 ? body.substring(0, 200) : body;
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
