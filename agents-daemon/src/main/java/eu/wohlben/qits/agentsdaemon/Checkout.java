package eu.wohlben.qits.agentsdaemon;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The agent's checkout at {@code /workspace/<wrapper>}: the wrapper and the submodules its {@code
 * .gitmodules} declares, each a repository of its own on its own {@code main}.
 *
 * <p>Submodule urls are rewritten to the sibling {@code <gitBase>/<name>} before anything is
 * cloned, as {@code Provisioner.materializeSubmodules} does in qits-workspace-daemon: the committed
 * urls are relative and end in {@code .git}, which git resolves against the wrapper's origin and
 * qits-githost does not serve. Only the wrapper's own submodules are followed; a submodule's nested
 * ones are left as git leaves them.
 */
final class Checkout {

  /** One repository of the checkout. {@code path} is relative to the wrapper, empty for it. */
  record Repo(String name, String path, Path dir, boolean wrapper) {}

  /**
   * One {@code .gitmodules} entry: its entry name (the config key), its path, and its committed
   * url. The repository it is a checkout of is named by the url, which is how an event names it.
   */
  record Submodule(String entry, String path, String url) {

    String repoName() {
      return basename(url.isEmpty() ? entry : url);
    }
  }

  static final String MAIN = "main";

  private final Git git;
  private final Path root;
  private final String wrapper;
  private final String gitBase;

  Checkout(Git git, Path root, String wrapper, String gitBase) {
    this.git = git;
    this.root = root;
    this.wrapper = wrapper;
    this.gitBase = trimSlash(gitBase);
  }

  Path root() {
    return root;
  }

  String wrapperName() {
    return wrapper;
  }

  Repo wrapperRepo() {
    return new Repo(wrapper, "", root, true);
  }

  /** Whether the wrapper is cloned at all. */
  boolean cloned() {
    return Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
  }

  /** The wrapper's origin: {@code <gitBase>/<wrapper>}. */
  String wrapperUrl() {
    return gitBase + "/" + wrapper;
  }

  /** The url a submodule is fetched from: the sibling {@code <gitBase>/<repository name>}. */
  String siblingUrl(String committedUrl) {
    return gitBase + "/" + basename(committedUrl);
  }

  /** The wrapper, then every initialised submodule, in {@code .gitmodules} order. */
  List<Repo> repos() {
    List<Repo> repos = new ArrayList<>();
    repos.add(wrapperRepo());
    for (Submodule sub : declared()) {
      Path dir = root.resolve(sub.path());
      if (initialised(dir)) {
        repos.add(new Repo(sub.repoName(), sub.path(), dir, false));
      }
    }
    return repos;
  }

  /** The repository of the checkout called {@code name} (the wrapper's or a submodule's). */
  Optional<Repo> repo(String name) {
    return repos().stream().filter(repo -> repo.name().equals(name)).findFirst();
  }

  /** Every submodule the wrapper's {@code .gitmodules} declares and its tree carries a gitlink for. */
  List<Submodule> declared() {
    Path gitmodules = root.resolve(".gitmodules");
    if (!Files.isRegularFile(gitmodules)) {
      return List.of();
    }
    Git.Result listed =
        git.in(root, "config", "--file", ".gitmodules", "--get-regexp", "^submodule\\..*\\.path$");
    if (!listed.ok()) {
      return List.of();
    }
    List<Submodule> out = new ArrayList<>();
    for (String raw : listed.stdout().split("\n")) {
      String line = raw.strip();
      int space = line.indexOf(' ');
      if (space < 0) {
        continue;
      }
      String key = line.substring(0, space);
      String path = line.substring(space + 1).strip();
      if (!key.startsWith("submodule.") || !key.endsWith(".path") || path.isEmpty()) {
        continue;
      }
      String entry = key.substring("submodule.".length(), key.length() - ".path".length());
      // A path .gitmodules names but the tree does not carry is not a submodule of this commit.
      if (!git.in(root, "ls-files", "--error-unmatch", "--", path).ok()) {
        continue;
      }
      Git.Result url = git.in(root, "config", "--file", ".gitmodules", "submodule." + entry + ".url");
      out.add(new Submodule(entry, path, url.ok() ? url.line() : ""));
    }
    return out;
  }

  /**
   * Materialises every declared submodule that is not initialised yet: its url pointed at the
   * sibling, {@code git submodule update --init} for its path alone, then onto its own {@code main}
   * at {@code origin/main}. An initialised submodule is never touched here — it follows its own
   * main, never the gitlink. Answers the paths it materialised.
   */
  List<String> materializeNew(Consumer<String> warn) {
    List<String> done = new ArrayList<>();
    for (Submodule sub : declared()) {
      Path dir = root.resolve(sub.path());
      if (initialised(dir)) {
        continue;
      }
      if (!sub.url().isEmpty()) {
        git.in(root, "config", "submodule." + sub.entry() + ".url", siblingUrl(sub.url()));
      }
      Git.Result update = git.in(root, "submodule", "update", "--init", "--", sub.path());
      if (!update.ok()) {
        warn.accept("submodule " + sub.path() + " not materialised: " + firstLine(update.message()));
        continue;
      }
      onMain(dir, sub.path(), warn);
      done.add(sub.path());
    }
    return done;
  }

  /** {@code git switch -q main} (a local main tracking origin/main), then a fast-forward to it. */
  void onMain(Path dir, String where, Consumer<String> warn) {
    Git.Result switched = git.in(dir, "switch", "-q", MAIN);
    if (!switched.ok()) {
      warn.accept(where + " is not on main: " + firstLine(switched.message()));
      return;
    }
    Git.Result merged = git.in(dir, "merge", "-q", "--ff-only", "origin/" + MAIN);
    if (!merged.ok()) {
      warn.accept(where + " did not fast-forward to origin/main: " + firstLine(merged.message()));
    }
  }

  static boolean initialised(Path dir) {
    return Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
  }

  /** The repository name a url ends in: no trailing slash, no {@code .git}, no scp prefix. */
  static String basename(String url) {
    String u = url == null ? "" : url.strip();
    while (u.length() > 1 && u.endsWith("/")) {
      u = u.substring(0, u.length() - 1);
    }
    int slash = u.lastIndexOf('/');
    String last = slash >= 0 ? u.substring(slash + 1) : u;
    int colon = last.lastIndexOf(':');
    if (colon >= 0) {
      last = last.substring(colon + 1);
    }
    return last.endsWith(".git") ? last.substring(0, last.length() - 4) : last;
  }

  static String firstLine(String text) {
    for (String line : (text == null ? "" : text).split("\n")) {
      if (!line.isBlank()) {
        return line.strip();
      }
    }
    return "";
  }

  static String trimSlash(String base) {
    String out = base == null ? "" : base.strip();
    while (out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }
}
