package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The follower against real repositories: bare origins, a wrapper with two submodules. */
class MainFollowerTest {

  @TempDir Path tmp;

  GitFixture fx;
  MainFollower follower;

  @BeforeEach
  void provisioned() {
    fx = new GitFixture(tmp).seed();
    fx.provision();
    follower = new MainFollower(fx.git, fx.checkout);
  }

  @Test
  void theProvisionPutsEverySubmoduleOnItsOwnMainAtTheSiblingUrl() {
    assertEquals("main", fx.branch(fx.root()));
    for (String name : new String[] {"alpha", "beta"}) {
      assertEquals("main", fx.branch(fx.sub(name)), name + " is on main");
      assertEquals(fx.originRef(name, "main"), fx.head(fx.sub(name)));
      assertEquals(
          fx.origins.resolve(name).toString(),
          fx.git.in(fx.sub(name), "config", "remote.origin.url").line(),
          name + "'s origin is the sibling, not ../" + name + ".git");
    }
  }

  @Test
  void aPushToASubmodulesMainFastForwardsIt() {
    String before = fx.head(fx.sub("alpha"));
    String pushed = fx.commitAndPush("alpha", "a.txt", "one\n", "a change");

    follower.onCommit("alpha", "main");

    assertEquals(pushed, fx.head(fx.sub("alpha")));
    assertNotEquals(before, pushed);
    assertEquals("main", fx.branch(fx.sub("alpha")));
    assertNull(follower.takeDetail());
  }

  @Test
  void aPushToABranchFetchesItButLeavesMainAlone() {
    String main = fx.head(fx.sub("alpha"));
    String branch = fx.commitOnBranch("alpha", "ticket/qits-1", "b.txt");
    fx.commitAndPush("alpha", "m.txt", "main moved too\n", "main moves"); // origin/main moves as well

    follower.onCommit("alpha", "ticket/qits-1");

    assertEquals(branch, fx.ref(fx.sub("alpha"), "refs/remotes/origin/ticket/qits-1"), "fetched");
    assertEquals(main, fx.head(fx.sub("alpha")), "main is left where it was");
  }

  @Test
  void aTrackedEditMakesItSkipAndLeavesTheFileUntouched() {
    Path readme = fx.sub("alpha").resolve("README.md");
    GitFixture.write(readme, "somebody's unfinished work\n");
    String before = fx.head(fx.sub("alpha"));
    fx.commitAndPush("alpha", "a.txt", "one\n", "a change");

    follower.onCommit("alpha", "main");

    assertEquals(before, fx.head(fx.sub("alpha")), "not moved");
    assertEquals("somebody's unfinished work\n", GitFixture.read(readme), "not touched");
    assertEquals("checkout skipped: components/c/alpha (tracked changes)", follower.takeDetail());
    assertNull(follower.takeDetail(), "the detail is taken once");
  }

  @Test
  void theWrapperFastForwardsAlthoughItsSubmodulesAreAheadOfTheGitlinks() {
    fx.commitAndPush("alpha", "a.txt", "one\n", "alpha moves");
    follower.onCommit("alpha", "main");
    String wrapperNow = fx.commitAndPush(GitFixture.WRAPPER, "NOTES.md", "notes\n", "wrapper moves");

    follower.onCommit(GitFixture.WRAPPER, "main");

    assertEquals(wrapperNow, fx.head(fx.root()));
    assertEquals(fx.originRef("alpha", "main"), fx.head(fx.sub("alpha")), "alpha stays on its own main");
    assertNull(follower.takeDetail());
  }

  @Test
  void aNewlyDeclaredSubmoduleIsMaterialised() {
    fx.repository("gamma");
    fx.declare("gamma");

    follower.onCommit(GitFixture.WRAPPER, "main");

    Path gamma = fx.root().resolve("components/c/gamma");
    assertTrue(Files.exists(gamma.resolve(".git")), "gamma is checked out");
    assertEquals("main", fx.branch(gamma));
    assertEquals(fx.originRef("gamma", "main"), fx.head(gamma));
    assertNotNull(fx.checkout.repo("gamma").orElse(null), "and followed from now on");
  }

  @Test
  void theBackstopCatchesAPushThatNoEventAnnounced() {
    String pushed = fx.commitAndPush("beta", "b.txt", "two\n", "a silent change");

    follower.backstop();

    assertEquals(pushed, fx.head(fx.sub("beta")));
  }

  @Test
  void aSubmoduleOffMainIsSkippedNotSwitched() {
    fx.ok(fx.git.in(fx.sub("beta"), "switch", "-q", "-c", "ticket/qits-2"));
    fx.commitAndPush("beta", "b.txt", "two\n", "a change");

    follower.onCommit("beta", "main");

    assertEquals("ticket/qits-2", fx.branch(fx.sub("beta")));
    assertEquals("checkout skipped: components/c/beta (not on main)", follower.takeDetail());
  }

  @Test
  void anEventForAnotherRepositoryIsIgnored() {
    follower.onCommit("somebody-elses-repo", "main");
    assertNull(follower.takeDetail());
  }
}
