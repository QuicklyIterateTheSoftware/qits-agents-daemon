package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The push loop against real repositories. */
class OriginPushTest {

  @TempDir Path tmp;

  GitFixture fx;
  OriginPush push;

  @BeforeEach
  void provisioned() {
    fx = new GitFixture(tmp).seed();
    fx.provision();
    push = new OriginPush(fx.git, fx.checkout);
  }

  private String commit(Path repo, String file) {
    GitFixture.write(repo.resolve(file), file + "\n");
    fx.ok(fx.git.in(repo, "add", file));
    fx.ok(fx.git.in(repo, "commit", "-q", "-m", "add " + file));
    return fx.head(repo);
  }

  @Test
  void aLocalBranchIsPushed() {
    fx.ok(fx.git.in(fx.sub("alpha"), "switch", "-q", "-c", "ticket/qits-1"));
    String tip = commit(fx.sub("alpha"), "a.txt");

    Map<String, OriginPush.Outcome> done = push.cycle();

    assertEquals(Map.of("alpha ticket/qits-1", OriginPush.Outcome.PUSHED), done);
    assertEquals(tip, fx.originRef("alpha", "ticket/qits-1"));
    assertTrue(push.cycle().isEmpty(), "an unchanged tip is not pushed again");
  }

  @Test
  void aBranchOfAWorktreeOutsideTheCheckoutIsPushed() {
    Path worktree = tmp.resolve("worktrees/qits-3/alpha");
    fx.ok(fx.git.in(fx.sub("alpha"), "worktree", "add", "-q", worktree.toString(), "-b", "ticket/qits-3", "origin/main"));
    String tip = commit(worktree, "w.txt");
    Path wrapperTree = tmp.resolve("worktrees/qits-3/" + GitFixture.WRAPPER);
    fx.ok(fx.git.in(fx.root(), "worktree", "add", "-q", wrapperTree.toString(), "-b", "ticket/qits-3", "origin/main"));
    String wrapperTip = commit(wrapperTree, "w.txt");

    push.cycle();

    assertEquals(tip, fx.originRef("alpha", "ticket/qits-3"));
    assertEquals(wrapperTip, fx.originRef(GitFixture.WRAPPER, "ticket/qits-3"));
  }

  @Test
  void mainIsNeverPushed() {
    String origin = fx.originRef("alpha", "main");
    commit(fx.sub("alpha"), "local.txt");

    assertTrue(push.cycle().isEmpty());
    assertEquals(origin, fx.originRef("alpha", "main"));
  }

  @Test
  void aDetachedHeadIsNeverPushed() {
    fx.ok(fx.git.in(fx.sub("beta"), "switch", "-q", "--detach"));
    String detached = commit(fx.sub("beta"), "d.txt");

    assertTrue(push.cycle().isEmpty());
    String refs = fx.git.in(fx.origins.resolve("beta"), "for-each-ref", "--contains", detached).stdout();
    assertTrue(refs.isBlank(), "origin holds no ref with the detached commit: " + refs);
  }

  @Test
  void aDivergedBranchIsRefusedNotForced() {
    fx.ok(fx.git.in(fx.sub("alpha"), "switch", "-q", "-c", "ticket/qits-4"));
    commit(fx.sub("alpha"), "a.txt");
    push.cycle();
    // Somebody else moves the pushed branch on, from origin's tip.
    Path seed = fx.seed("alpha");
    fx.ok(fx.git.in(seed, "fetch", "-q", "origin"));
    fx.ok(fx.git.in(seed, "switch", "-q", "-C", "ticket/qits-4", "origin/ticket/qits-4"));
    String theirs = fx.commitAndPush("alpha", "theirs.txt", "theirs\n", "their change");
    fx.ok(fx.git.in(seed, "switch", "-q", "main"));
    // Ours moves on too, so the two have diverged.
    commit(fx.sub("alpha"), "ours.txt");

    Map<String, OriginPush.Outcome> done = push.cycle();

    assertEquals(Map.of("alpha ticket/qits-4", OriginPush.Outcome.REFUSED), done);
    assertEquals(theirs, fx.originRef("alpha", "ticket/qits-4"), "origin keeps its commit");
    assertTrue(push.cycle().isEmpty(), "not tried again until the tip moves");
    commit(fx.sub("alpha"), "more.txt");
    assertEquals(Map.of("alpha ticket/qits-4", OriginPush.Outcome.REFUSED), push.cycle(), "a new tip is tried");
  }

  @Test
  void aBranchOriginAlreadyHoldsIsNotPushed() {
    fx.ok(fx.git.in(fx.sub("beta"), "switch", "-q", "-c", "ticket/qits-5", "origin/main"));
    assertTrue(push.cycle().isEmpty());
    assertNull(fx.originRef("beta", "ticket/qits-5"));
  }

  @Test
  void theRefusalQuotesTheHostsOwnLine() {
    String output =
        "To https://githost.example/git/qits/alpha\n"
            + "remote: commit abc1234: subject must name a ticket\n"
            + " ! [remote rejected] ticket/x -> ticket/x (pre-receive hook declined)\n";
    assertTrue(OriginPush.refused(output));
    assertEquals("remote: commit abc1234: subject must name a ticket", OriginPush.firstLine(output));
  }
}
