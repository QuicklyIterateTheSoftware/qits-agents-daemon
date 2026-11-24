package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>What this daemon asks qits-projects-service, and why</b>: a repository's name and project by
 * its id, {@code getRepository} — {@code GET /projects/api/repositories/{repositoryId}} — for an
 * {@code SCMPublishCommit} that carries only {@code repoId} ({@link RepositoryNames}). Recorded in
 * the state {@value #STATE}. The pact is {@code
 * pacts/qits-agents-daemon_qits-projects-service.json}.
 */
class ProjectsServicePactTest {

  static final GoldenMasters PROJECTS = GoldenMasters.of("qits-projects-service", "qits-projects");

  static final String STATE = "a repository exists";

  static final GoldenInteraction REPOSITORY =
      GoldenInteraction.of(Trigger.event("SCMPublishCommit").from("MainFollower"), STATE, "getRepository")
          .consumes("repository.name", "repository.projectId");

  static final ConsumerPact PACT =
      ConsumerPact.of(AgentsServicePactTest.CONSUMER, PROJECTS, REPOSITORY);

  @Test
  void aCommitWithoutANameIsNamedByItsRepositoryId() {
    PACT.run(
        REPOSITORY,
        (url, recorded) -> {
          var repository = PROJECTS.json(STATE, REPOSITORY.operationId()).path("repository");
          RepositoryNames names = new RepositoryNames(HttpAgentsDoors.client(), url, "a-token");
          Optional<String> name =
              names.nameOf(recorded.params().get("repositoryId"), repository.path("projectId").asText());
          assertEquals(Optional.of(repository.path("name").asText()), name);
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(1, PACT.recorded().size(), "the row is recorded: " + PACT.unrecorded());
  }
}
