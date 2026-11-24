package eu.wohlben.qits.agentsdaemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Pushes the agent's branches for it, so nobody in the container ever runs {@code git push}.
 *
 * <p>Every cycle (10 s, as qits-workspace-daemon's {@code auto-push.poll-ms}), in the wrapper, in
 * every submodule, <em>and in every repository a worktree of theirs holds</em> (a submodule a
 * wrapper worktree initialised has its own object store under that worktree's git dir), it pushes
 * each local branch other than {@code main} whose tip origin lacks: {@code git push origin
 * <branch>:<branch>}, never {@code --force}. Branches are shared by every worktree of a repository,
 * so walking each repository once covers the branches its worktrees have checked out.
 *
 * <p>A refusal — a non-fast-forward, or the git host rejecting a commit subject — logs {@code WARN
 * OriginPush refused <R> <branch>: <first line>} and is retried only when the branch tip changes,
 * never with force. A failure that is not a refusal (the network, a lock) is tried again next cycle.
 * {@code main} and detached HEADs are never pushed.
 *
 * <p>Modelled on {@code OriginSync} in qits-workspace-daemon, without its per-agent seats: this
 * container holds one agent and pushes with its one credential, the git config's helper.
 */
final class OriginPush {

  private static final Logger LOG = Logger.getLogger(OriginPush.class);

  /** One repository: its common git dir (its identity), a directory to run git in, its name. */
  record Repository(Path commonDir, Path workDir, String name) {}

  /** What one push attempt came to. */
  enum Outcome {
    PUSHED,
    REFUSED,
    FAILED
  }

  private final Git git;
  private final Checkout checkout;

  /** Per repository and branch, the tip last pushed or refused: not tried again until it moves. */
  private final Map<String, String> handled = new ConcurrentHashMap<>();

  /** Per repository and branch, the last transient failure warned, so it warns once per message. */
  private final Map<String, String> failing = new ConcurrentHashMap<>();

  OriginPush(Git git, Checkout checkout) {
    this.git = git;
    this.checkout = checkout;
  }

  /** One cycle. Answers what it did, per {@code <repository> <branch>}. */
  Map<String, Outcome> cycle() {
    Map<String, Outcome> done = new LinkedHashMap<>();
    if (!checkout.cloned()) {
      return done;
    }
    for (Repository repo : repositories()) {
      for (String[] ref : branches(repo)) {
        String branch = ref[0];
        String tip = ref[1];
        if (Checkout.MAIN.equals(branch)) {
          continue;
        }
        String key = repo.commonDir() + "\u0000" + branch;
        if (tip.equals(handled.get(key))) {
          continue;
        }
        Git.Result ahead = git.in(repo.workDir(), "rev-list", "--count", tip, "--not", "--remotes=origin");
        if (ahead.ok() && "0".equals(ahead.line())) {
          handled.put(key, tip);
          continue;
        }
        Outcome outcome = push(repo, branch, tip, key);
        done.put(repo.name() + " " + branch, outcome);
      }
    }
    return done;
  }

  private Outcome push(Repository repo, String branch, String tip, String key) {
    String refspec = "refs/heads/" + branch + ":refs/heads/" + branch;
    Git.Result pushed = git.in(repo.workDir(), "push", "--porcelain", "origin", refspec);
    if (pushed.ok()) {
      handled.put(key, tip);
      failing.remove(key);
      LOG.infof("OriginPush pushed %s %s %s", repo.name(), branch, MainFollower.short7(tip));
      return Outcome.PUSHED;
    }
    String output = pushed.stderr() + "\n" + pushed.stdout();
    if (refused(output)) {
      handled.put(key, tip);
      failing.remove(key);
      LOG.warnf("OriginPush refused %s %s: %s", repo.name(), branch, firstLine(output));
      return Outcome.REFUSED;
    }
    String line = firstLine(output);
    if (!line.equals(failing.put(key, line))) {
      LOG.warnf("OriginPush could not push %s %s (tried again next cycle): %s", repo.name(), branch, line);
    }
    return Outcome.FAILED;
  }

  /** Every repository of the checkout and of its worktrees, once each by common git dir. */
  List<Repository> repositories() {
    Map<Path, Repository> found = new LinkedHashMap<>();
    List<Checkout.Repo> repos = checkout.repos();
    for (Checkout.Repo repo : repos) {
      add(found, repo.dir());
    }
    // A wrapper worktree whose submodules were initialised inside it holds repositories of its own.
    for (Path worktree : worktrees(checkout.root())) {
      for (Checkout.Submodule sub : checkout.declared()) {
        Path dir = worktree.resolve(sub.path());
        if (Checkout.initialised(dir)) {
          add(found, dir);
        }
      }
    }
    return new ArrayList<>(found.values());
  }

  private void add(Map<Path, Repository> found, Path dir) {
    Git.Result common = git.in(dir, "rev-parse", "--path-format=absolute", "--git-common-dir");
    if (!common.ok() || common.line().isEmpty()) {
      return;
    }
    Path commonDir = Path.of(common.line()).normalize();
    if (found.containsKey(commonDir)) {
      return;
    }
    Git.Result url = git.in(dir, "config", "--get", "remote.origin.url");
    if (!url.ok() || url.line().isEmpty()) {
      return; // nowhere to push to
    }
    found.put(commonDir, new Repository(commonDir, dir, Checkout.basename(url.line())));
  }

  /** The linked worktrees of {@code repository}, the main working tree left out. */
  private List<Path> worktrees(Path repository) {
    Git.Result listed = git.in(repository, "worktree", "list", "--porcelain");
    List<Path> out = new ArrayList<>();
    if (!listed.ok()) {
      return out;
    }
    Path main = repository.toAbsolutePath().normalize();
    for (String line : listed.stdout().split("\n")) {
      if (line.startsWith("worktree ")) {
        Path path = Path.of(line.substring("worktree ".length())).toAbsolutePath().normalize();
        if (!path.equals(main) && Files.isDirectory(path)) {
          out.add(path);
        }
      }
    }
    return out;
  }

  /** Each local branch with its tip: {@code [name, sha]}. */
  private List<String[]> branches(Repository repo) {
    Git.Result refs =
        git.in(
            repo.workDir(),
            "for-each-ref",
            "--format=%(refname:short)%09%(objectname)",
            "refs/heads");
    List<String[]> out = new ArrayList<>();
    if (!refs.ok()) {
      return out;
    }
    for (String line : refs.stdout().split("\n")) {
      int tab = line.indexOf('\t');
      if (tab > 0) {
        out.add(new String[] {line.substring(0, tab), line.substring(tab + 1).strip()});
      }
    }
    return out;
  }

  /** Whether the host answered no — as opposed to not answering. */
  static boolean refused(String output) {
    String o = output.toLowerCase(Locale.ROOT);
    return o.contains("[rejected]")
        || o.contains("[remote rejected]")
        || o.contains("non-fast-forward")
        || o.contains("fetch first")
        || o.contains("hook declined")
        || o.contains("denied");
  }

  /**
   * The line worth quoting: the host's own words ({@code remote: …}), else the rejection, else an
   * {@code error:}, else the first line at all.
   */
  static String firstLine(String output) {
    String rejection = null;
    String error = null;
    for (String raw : output.split("\n")) {
      String line = raw.strip().replace('\t', ' ');
      if (line.startsWith("hint:")) {
        continue;
      }
      if (line.startsWith("remote:") && line.length() > "remote:".length()) {
        return line;
      }
      if (rejection == null && (line.contains("[rejected]") || line.contains("[remote rejected]"))) {
        rejection = line;
      }
      if (error == null && (line.startsWith("error:") || line.startsWith("fatal:"))) {
        error = line;
      }
    }
    if (rejection != null) {
      return rejection;
    }
    return error != null ? error : Checkout.firstLine(output);
  }
}
