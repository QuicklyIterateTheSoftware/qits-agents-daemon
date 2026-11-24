package eu.wohlben.qits.agentsdaemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Keeps the agent's checkout on every commit the platform takes: the wrapper and each submodule at
 * its own {@code origin/main}.
 *
 * <ul>
 *   <li><b>On an event</b> ({@code SCMPublishCommit} of this project, for repository {@code R}):
 *       {@code git fetch --prune origin} in {@code R}; when the branch is {@code main} and {@code
 *       R}'s HEAD is on {@code main}, {@code git merge --ff-only origin/main}. When {@code R} is the
 *       wrapper, a submodule it newly declares is materialised — and only that: an initialised
 *       submodule follows its own main, never the gitlink.
 *   <li><b>The backstop</b>, every 60 s: fetch every repository, then fast-forward every {@code
 *       main}. It is what catches a push whose event this daemon never heard.
 * </ul>
 *
 * <p><b>The guard.</b> It fast-forwards only a repository whose HEAD is on {@code main} and whose
 * {@code git status --porcelain --untracked-files=no} is empty — for the wrapper with {@code
 * --ignore-submodules=all}, because its submodules sit on their own mains ahead of the gitlinks and
 * that drift is expected. Anything else is skipped with {@code WARN MainFollower skipped <R>:
 * <paths>}, and a one-line detail is handed to the next report. It never runs {@code reset}, {@code
 * stash}, {@code checkout --}, {@code clean} or anything forced: the checkout is read-only to the
 * agent, and dirt in it is somebody's to look at, not this daemon's to remove.
 *
 * <p>Each move logs {@code INFO MainFollower moved <R> <old7>..<new7> (<event|timer>)}, which is the
 * line the live check reads.
 *
 * <p>Not thread-safe on purpose: every call runs on the one follower thread the daemon gives it.
 */
final class MainFollower {

  private static final Logger LOG = Logger.getLogger(MainFollower.class);

  /** What caused a move, as the log line names it. */
  enum Cause {
    EVENT("event"),
    TIMER("timer");

    final String label;

    Cause(String label) {
      this.label = label;
    }
  }

  /** The longest detail a report carries. */
  static final int DETAIL_LIMIT = 200;

  private final Git git;
  private final Checkout checkout;

  /** The last skip warned per repository, so a skip that persists warns once, not every minute. */
  private final Map<String, String> skipped = new ConcurrentHashMap<>();

  /** The detail the next report carries, taken once. */
  private final AtomicReference<String> detail = new AtomicReference<>();

  MainFollower(Git git, Checkout checkout) {
    this.git = git;
    this.checkout = checkout;
  }

  /**
   * An event: {@code branch} of repository {@code repoName} moved. Ignored unless {@code repoName}
   * is the wrapper or one of its initialised submodules.
   */
  void onCommit(String repoName, String branch) {
    if (repoName == null || !checkout.cloned()) {
      return;
    }
    Optional<Checkout.Repo> found = checkout.repo(repoName);
    if (found.isEmpty()) {
      return;
    }
    Checkout.Repo repo = found.get();
    if (!fetch(repo)) {
      return;
    }
    if (Checkout.MAIN.equals(branch)) {
      fastForward(repo, Cause.EVENT);
      if (repo.wrapper()) {
        materializeNew();
      }
    }
  }

  /** The backstop: fetch everything, then fast-forward every main. */
  void backstop() {
    if (!checkout.cloned()) {
      return;
    }
    Checkout.Repo wrapper = checkout.wrapperRepo();
    if (fetch(wrapper)) {
      fastForward(wrapper, Cause.TIMER);
    }
    materializeNew();
    for (Checkout.Repo repo : checkout.repos()) {
      if (!repo.wrapper() && fetch(repo)) {
        fastForward(repo, Cause.TIMER);
      }
    }
  }

  /** The detail for the next report, once; null when there is nothing to say. */
  String takeDetail() {
    return detail.getAndSet(null);
  }

  private boolean fetch(Checkout.Repo repo) {
    Git.Result fetched = git.in(repo.dir(), "fetch", "--prune", "--quiet", "origin");
    if (!fetched.ok()) {
      warnOnce(repo, "fetch", "fetch failed: " + Checkout.firstLine(fetched.message()));
      return false;
    }
    return true;
  }

  /** Fast-forwards {@code repo}'s main to origin/main when the guard lets it. */
  void fastForward(Checkout.Repo repo, Cause cause) {
    Git.Result head = git.in(repo.dir(), "symbolic-ref", "--quiet", "--short", "HEAD");
    if (!head.ok() || !Checkout.MAIN.equals(head.line())) {
      String on = head.ok() && !head.line().isEmpty() ? head.line() : "a detached HEAD";
      skip(repo, "HEAD is on " + on + ", not main", "checkout skipped: " + where(repo) + " (not on main)");
      return;
    }
    List<String> args = new ArrayList<>(List.of("status", "--porcelain", "--untracked-files=no"));
    if (repo.wrapper()) {
      args.add("--ignore-submodules=all");
    }
    Git.Result status = git.in(repo.dir(), args.toArray(String[]::new));
    if (!status.ok()) {
      skip(repo, "status failed: " + Checkout.firstLine(status.message()), null);
      return;
    }
    List<String> dirty = paths(status.stdout());
    if (!dirty.isEmpty()) {
      skip(
          repo,
          String.join(", ", dirty),
          "checkout skipped: " + where(repo) + " (tracked changes)");
      return;
    }
    Git.Result old = git.in(repo.dir(), "rev-parse", "--verify", "--quiet", "HEAD");
    Git.Result target = git.in(repo.dir(), "rev-parse", "--verify", "--quiet", "origin/" + Checkout.MAIN);
    if (!old.ok() || !target.ok()) {
      skip(repo, "no origin/main to follow", null);
      return;
    }
    skipped.remove(repo.name());
    if (old.line().equals(target.line())) {
      return;
    }
    Git.Result merged = git.in(repo.dir(), "merge", "-q", "--ff-only", "origin/" + Checkout.MAIN);
    if (!merged.ok()) {
      skip(
          repo,
          "main does not fast-forward to origin/main (" + Checkout.firstLine(merged.message()) + ")",
          "checkout skipped: " + where(repo) + " (main diverged from origin/main)");
      return;
    }
    LOG.infof(
        "MainFollower moved %s %s..%s (%s)",
        repo.name(), short7(old.line()), short7(target.line()), cause.label);
  }

  private void materializeNew() {
    for (String path : checkout.materializeNew(message -> LOG.warnf("MainFollower: %s", message))) {
      LOG.infof("MainFollower materialised %s", path);
    }
  }

  private void skip(Checkout.Repo repo, String why, String reportDetail) {
    if (reportDetail != null) {
      detail.set(reportDetail.length() > DETAIL_LIMIT ? reportDetail.substring(0, DETAIL_LIMIT) : reportDetail);
    }
    if (why.equals(skipped.put(repo.name(), why))) {
      return;
    }
    LOG.warnf("MainFollower skipped %s: %s", repo.name(), why);
  }

  private void warnOnce(Checkout.Repo repo, String what, String message) {
    String key = repo.name() + "\u0000" + what;
    if (message.equals(skipped.put(key, message))) {
      return;
    }
    LOG.warnf("MainFollower %s: %s", repo.name(), message);
  }

  /** The paths a porcelain status names: {@code XY path} or {@code XY from -> to}. */
  static List<String> paths(String porcelain) {
    List<String> out = new ArrayList<>();
    for (String line : porcelain.split("\n")) {
      if (line.length() < 4) {
        continue;
      }
      String path = line.substring(3);
      int arrow = path.indexOf(" -> ");
      out.add(arrow < 0 ? path : path.substring(arrow + 4));
    }
    return out;
  }

  private static String where(Checkout.Repo repo) {
    return repo.wrapper() ? repo.name() : repo.path();
  }

  static String short7(String sha) {
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }
}
