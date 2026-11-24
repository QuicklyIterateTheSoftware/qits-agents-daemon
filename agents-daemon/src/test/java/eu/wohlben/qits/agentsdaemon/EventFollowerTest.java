package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The frames: which ones reach the follower and the keeper, and as what. */
class EventFollowerTest {

  static final String PROJECT = "00000000-0000-4000-8000-000000000002";

  final List<String> heard = new ArrayList<>();

  final RepositoryNames names =
      new RepositoryNames(HttpClient.newHttpClient(), "http://unused.invalid", null) {
        @Override
        Optional<Named> lookup(String id) {
          return Optional.of(new Named("alpha", PROJECT));
        }
      };

  final EventFollower follower =
      new EventFollower(
          HttpClient.newHttpClient(),
          "http://unused.invalid",
          "token",
          () -> PROJECT,
          "developer",
          names,
          new EventFollower.Listener() {
            @Override
            public void connected() {
              heard.add("connected");
            }

            @Override
            public void commit(String repoName, String branch) {
              heard.add("commit " + repoName + " " + branch);
            }

            @Override
            public void agentChanged() {
              heard.add("agent");
            }
          });

  static String envelope(String name, JsonObject payload) {
    // The payload travels as a JSON string inside the envelope.
    return new JsonObject().put("name", name).put("payload", payload.encode()).encode();
  }

  @Test
  void aCommitOfThisProjectIsHandedOn() {
    follower.frame(
        envelope(
            "SCMPublishCommit",
            new JsonObject().put("projectId", PROJECT).put("repoName", "qits-qits").put("branch", "main")));
    assertEquals(List.of("commit qits-qits main"), heard);
  }

  @Test
  void aCommitOfAnotherProjectIsDropped() {
    follower.frame(
        envelope(
            "SCMPublishCommit",
            new JsonObject().put("projectId", "another").put("repoName", "qits-qits").put("branch", "main")));
    assertEquals(List.of(), heard);
  }

  @Test
  void aCommitWithoutANameIsNamedByItsRepositoryId() {
    follower.frame(
        envelope("SCMPublishCommit", new JsonObject().put("repoId", "storage-1").put("branch", "main")));
    assertEquals(List.of("commit alpha main"), heard);
  }

  @Test
  void onlyThisAgentsChangeReachesTheKeeper() {
    follower.frame(
        envelope("ProjectAgentChanged", new JsonObject().put("projectId", PROJECT).put("agentName", "reviewer")));
    follower.frame(
        envelope("ProjectAgentChanged", new JsonObject().put("projectId", PROJECT).put("agentName", "developer")));
    assertEquals(List.of("agent"), heard);
  }

  @Test
  void theSseParserJoinsDataLinesUntilABlankLine() {
    SseParser parser = new SseParser();
    assertEquals(Optional.empty(), parser.accept(": keepalive"));
    assertEquals(Optional.empty(), parser.accept("id: 7"));
    assertEquals(Optional.empty(), parser.accept("data: {\"a\":"));
    assertEquals(Optional.empty(), parser.accept("data: 1}"));
    assertEquals(Optional.of(new SseParser.Event("7", null, "{\"a\":\n1}")), parser.accept(""));
  }
}
