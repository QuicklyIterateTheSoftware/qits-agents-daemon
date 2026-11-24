package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Real git repositories in a temp dir: bare origins under {@code origins/} (the git base), a seed
 * clone of each under {@code seed/} that plays the platform's other pushers, and a wrapper
 * declaring two submodules at {@code components/c/alpha} and {@code components/c/beta} with the
 * relative {@code ../<name>.git} urls a real wrapper commits. {@link #provision} clones it the way
 * the daemon does, into {@code workspace/qits-qits}.
 */
final class GitFixture {

  static final String WRAPPER = "qits-qits";

  final Path dir;
  final Path origins;
  final Path seeds;
  final Git git;
  final Checkout checkout;

  GitFixture(Path dir) {
    this.dir = dir;
    this.origins = dir.resolve("origins");
    this.seeds = dir.resolve("seed");
    Path config = dir.resolve("gitconfig");
    write(
        config,
        """
        [user]
        \tname = Fixture
        \temail = fixture@example.test
        [init]
        \tdefaultBranch = main
        [protocol "file"]
        \tallow = always
        [advice]
        \tdetachedHead = false
        """);
    this.git =
        new Git(
            Map.of("GIT_CONFIG_GLOBAL", config.toString(), "GIT_CONFIG_NOSYSTEM", "1"),
            Duration.ofMinutes(1));
    this.checkout = new Checkout(git, dir.resolve("workspace").resolve(WRAPPER), WRAPPER, origins.toString());
  }

  /** The two submodules and the wrapper declaring them, pushed to their origins. */
  GitFixture seed() {
    repository("alpha");
    repository("beta");
    repository(WRAPPER);
    declare("alpha");
    declare("beta");
    return this;
  }

  /** A bare origin and a seed clone with one commit on main. */
  void repository(String name) {
    ok(git.run("init", "-q", "--bare", "-b", "main", origins.resolve(name).toString()));
    ok(git.run("clone", "-q", origins.resolve(name).toString(), seed(name).toString()));
    commitAndPush(name, "README.md", name + "\n", "initial " + name);
  }

  /** The wrapper's seed declares {@code name} at {@code components/c/<name>}, url {@code ../<name>.git}. */
  void declare(String name) {
    Path wrapper = seed(WRAPPER);
    String path = "components/c/" + name;
    ok(git.in(wrapper, "submodule", "add", "-q", "--name", name, origins.resolve(name).toString(), path));
    ok(git.in(wrapper, "config", "-f", ".gitmodules", "submodule." + name + ".url", "../" + name + ".git"));
    ok(git.in(wrapper, "config", "-f", ".gitmodules", "submodule." + name + ".ignore", "all"));
    ok(git.in(wrapper, "add", ".gitmodules", path));
    ok(git.in(wrapper, "commit", "-q", "-m", "declare " + name));
    ok(git.in(wrapper, "push", "-q", "origin", "main"));
  }

  /** A commit in {@code name}'s seed on its current branch, pushed. Answers its sha. */
  String commitAndPush(String name, String file, String content, String message) {
    Path seed = seed(name);
    write(seed.resolve(file), content);
    ok(git.in(seed, "add", file));
    ok(git.in(seed, "commit", "-q", "-m", message));
    String branch = git.in(seed, "symbolic-ref", "--short", "HEAD").line();
    ok(git.in(seed, "push", "-q", "origin", branch));
    return head(seed);
  }

  /** A commit on a new or existing branch of {@code name}'s seed, pushed; the seed goes back to main. */
  String commitOnBranch(String name, String branch, String file) {
    Path seed = seed(name);
    ok(git.in(seed, "switch", "-q", "-C", branch));
    String sha = commitAndPush(name, file, branch + "\n", "work on " + branch);
    ok(git.in(seed, "switch", "-q", "main"));
    return sha;
  }

  Path seed(String name) {
    return seeds.resolve(name);
  }

  Path root() {
    return checkout.root();
  }

  Path sub(String name) {
    return root().resolve("components/c/" + name);
  }

  void provision() {
    String failed = new Provisioner(git, checkout).provision();
    assertTrue(failed == null, () -> "provision failed: " + failed);
  }

  String head(Path repo) {
    return ok(git.in(repo, "rev-parse", "HEAD")).line();
  }

  String ref(Path repo, String ref) {
    Git.Result r = git.in(repo, "rev-parse", "--verify", "--quiet", ref);
    return r.ok() ? r.line() : null;
  }

  String branch(Path repo) {
    return git.in(repo, "symbolic-ref", "--quiet", "--short", "HEAD").line();
  }

  /** The tip of {@code branch} in {@code name}'s origin, or null. */
  String originRef(String name, String branch) {
    return ref(origins.resolve(name), "refs/heads/" + branch);
  }

  Git.Result ok(Git.Result result) {
    assertTrue(result.ok(), () -> "git failed: " + result.message());
    return result;
  }

  static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
