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

  /** Installs a {@code pre-receive} hook on {@code repo}'s origin that always refuses the push. */
  private void refuseAlways(String repo) {
    refuseUnlessMarked(repo, tmp.resolve("no-such-marker-" + repo));
  }

  /** Installs a {@code pre-receive} hook that refuses every push until {@code marker} exists. */
  private void refuseUnlessMarked(String repo, Path marker) {
    Path hook = fx.origins.resolve(repo).resolve("hooks/pre-receive");
    GitFixture.write(
        hook,
        "#!/bin/sh\n"
            + "if [ -f \"" + marker + "\" ]; then exit 0; fi\n"
            + "echo 'scope not yet granted' >&2\n"
            + "exit 1\n");
    assertTrue(hook.toFile().setExecutable(true), "hook must be executable");
  }

  @Test
  void aRefusedTipIsNotRetriedBeforeTheBackoff() {
    long[] now = {0L};
    push = new OriginPush(fx.git, fx.checkout, () -> now[0]);
    fx.ok(fx.git.in(fx.sub("alpha"), "switch", "-q", "-c", "ticket/qits-10"));
    commit(fx.sub("alpha"), "a.txt");
    refuseAlways("alpha");

    assertEquals(Map.of("alpha ticket/qits-10", OriginPush.Outcome.REFUSED), push.cycle());

    now[0] += OriginPush.REFUSAL_BACKOFF_INITIAL_MILLIS - 1;
    assertTrue(push.cycle().isEmpty(), "not retried before the backoff elapses");
  }

  @Test
  void aRefusedTipIsRetriedAfterTheBackoffAndPushedOnceAccepted() {
    long[] now = {0L};
    push = new OriginPush(fx.git, fx.checkout, () -> now[0]);
    fx.ok(fx.git.in(fx.sub("alpha"), "switch", "-q", "-c", "ticket/qits-11"));
    String tip = commit(fx.sub("alpha"), "a.txt");
    Path marker = tmp.resolve("accept-alpha");
    refuseUnlessMarked("alpha", marker);

    assertEquals(Map.of("alpha ticket/qits-11", OriginPush.Outcome.REFUSED), push.cycle());
    assertNull(fx.originRef("alpha", "ticket/qits-11"));

    now[0] += OriginPush.REFUSAL_BACKOFF_INITIAL_MILLIS;
    GitFixture.write(marker, "ok\n");
    assertEquals(Map.of("alpha ticket/qits-11", OriginPush.Outcome.PUSHED), push.cycle());
    assertEquals(tip, fx.originRef("alpha", "ticket/qits-11"));
  }

  @Test
  void theBackoffDoublesThenCapsAtTheMaximum() {
    long[] now = {0L};
    push = new OriginPush(fx.git, fx.checkout, () -> now[0]);
    fx.ok(fx.git.in(fx.sub("alpha"), "switch", "-q", "-c", "ticket/qits-12"));
    commit(fx.sub("alpha"), "a.txt");
    refuseAlways("alpha");

    assertEquals(Map.of("alpha ticket/qits-12", OriginPush.Outcome.REFUSED), push.cycle());

    long expected = OriginPush.REFUSAL_BACKOFF_INITIAL_MILLIS;
    while (expected < OriginPush.REFUSAL_BACKOFF_MAX_MILLIS) {
      now[0] += expected - 1;
      assertTrue(push.cycle().isEmpty(), "not retried before " + expected + "ms of backoff");
      now[0] += 1;
      assertEquals(Map.of("alpha ticket/qits-12", OriginPush.Outcome.REFUSED), push.cycle());
      expected = Math.min(expected * 2, OriginPush.REFUSAL_BACKOFF_MAX_MILLIS);
    }

    // backoff is now pinned at the cap: confirm it never grows past it.
    now[0] += OriginPush.REFUSAL_BACKOFF_MAX_MILLIS - 1;
    assertTrue(push.cycle().isEmpty(), "the capped backoff is not retried early");
    now[0] += 1;
    assertEquals(Map.of("alpha ticket/qits-12", OriginPush.Outcome.REFUSED), push.cycle());
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
