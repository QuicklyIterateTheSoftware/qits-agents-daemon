package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The brief's substance, point by point, by key phrase — not its wording, which qits-2520 may
 * rewrite.
 */
class OverseerBriefTest {

  final String brief = OverseerBrief.render("qits", "developer", "qits-qits");

  @Test
  void oneTheCheckoutFollowsEveryCommitAndIsReadOnly() {
    assertTrue(brief.contains("long-running agent `developer` of project `qits`"), brief);
    assertTrue(brief.contains("`/workspace/qits-qits` follows every commit"), brief);
    assertTrue(brief.contains("own `origin/main`"), brief);
    assertTrue(brief.contains("read-only to you: never edit, commit or switch branches in it"), brief);
  }

  @Test
  void twoEachItemGetsWorktreesOutsideTheCheckout() {
    assertTrue(
        brief.contains(
            "git -C /workspace/qits-qits worktree add /workspace/worktrees/<qualified id>/qits-qits"
                + " -b <ticket|epic>/<qualified id> origin/main"),
        brief);
    assertTrue(
        brief.contains(
            "git -C /workspace/qits-qits/<submodule path> worktree add"
                + " /workspace/worktrees/<qualified id>/<repo> -b <ticket|epic>/<qualified id>"
                + " origin/main"),
        brief);
  }

  @Test
  void threeASubagentDoesTheWork() {
    assertTrue(brief.contains("Hand each item to a subagent"), brief);
    assertTrue(brief.contains("keep only the planning and the checking for yourself"), brief);
    assertTrue(brief.contains("Several items may run at once"), brief);
  }

  @Test
  void fourPushesAreMadeForTheAgent() {
    assertTrue(brief.contains("pushed for you within seconds"), brief);
    assertTrue(brief.contains("Nobody runs `git push`, and nobody force-pushes"), brief);
  }

  @Test
  void fiveWorktreesAreRemovedThroughTheirOwnRepository() {
    assertTrue(brief.contains("released and resolved"), brief);
    assertTrue(brief.contains("`git -C <repo> worktree remove <path>`"), brief);
  }

  @Test
  void itIsFiveShortParagraphs() {
    assertEquals(5, brief.split("\n\n").length, brief);
  }
}
