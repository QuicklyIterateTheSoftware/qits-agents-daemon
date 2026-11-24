package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * A repository's name by its id, for an {@code SCMPublishCommit} that carries no {@code repoName}
 * (a push on the git host's internal storage-id scheme). Asked once per id of qits-projects —
 * {@code GET /projects/api/repositories/{id}}, answered {@code {repository: {name, projectId, …}}}
 * — and cached for the container's life. A repository of another project caches as empty.
 */
class RepositoryNames {

  private static final Logger LOG = Logger.getLogger(RepositoryNames.class);

  /** One answer: the repository's name and project. */
  record Named(String name, String projectId) {}

  private final HttpClient http;
  private final String projectsUrl;
  private final String bearer;
  private final Map<String, Optional<Named>> cache = new ConcurrentHashMap<>();

  /**
   * @param projectsUrl qits-projects' base, {@code https://projects.qits.<domain>} — the path
   *     {@code /projects/api/…} is appended
   */
  RepositoryNames(HttpClient http, String projectsUrl, String token) {
    this.http = http;
    this.projectsUrl = Checkout.trimSlash(projectsUrl);
    this.bearer = token == null || token.isBlank() ? null : "Bearer " + token;
  }

  /** The repository {@code id} names, when it is one of {@code projectId}'s. */
  Optional<String> nameOf(String id, String projectId) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    Optional<Named> named = cache.get(id);
    if (named == null) {
      named = lookup(id);
      if (named == null) {
        return Optional.empty(); // not cached: a failure is asked again next time
      }
      cache.put(id, named);
    }
    return named.filter(n -> projectId == null || projectId.equals(n.projectId())).map(Named::name);
  }

  /** The lookup itself; null when it failed and should be asked again. */
  Optional<Named> lookup(String id) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(projectsUrl + "/projects/api/repositories/" + id))
            .timeout(HttpAgentsDoors.TIMEOUT)
            .header("Accept", "application/json")
            .GET();
    if (bearer != null) {
      request.header("Authorization", bearer);
    }
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 404) {
        return Optional.empty();
      }
      if (response.statusCode() != 200) {
        LOG.debugf("repository %s answered %d", id, response.statusCode());
        return null;
      }
      JsonObject repository = new JsonObject(response.body()).getJsonObject("repository");
      if (repository == null || repository.getString("name") == null) {
        return Optional.empty();
      }
      return Optional.of(new Named(repository.getString("name"), repository.getString("projectId")));
    } catch (IOException | RuntimeException e) {
      LOG.debugf("repository %s not looked up: %s", id, e.getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
