package eu.wohlben.qits.agentsdaemon;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.logging.Logger;

/**
 * Puts the wrapper checkout on the work volume the first time this container starts.
 *
 * <p>When {@code /workspace/<wrapper>/.git} is absent: {@code git clone <gitBase>/<wrapper>}, every
 * submodule url rewritten to its sibling and initialised, and every submodule switched onto its own
 * {@code main} at {@code origin/main}. The volume ({@code agent-<agentId>}) is kept across
 * container restarts, so on every later start the clone exists and this does nothing: the
 * MainFollower's catch-up brings it current.
 *
 * <p>A failed clone leaves nothing behind that the next attempt trips over: the target directory is
 * only ever created by {@code git clone} itself, which removes it again when it fails.
 */
final class Provisioner {

  private static final Logger LOG = Logger.getLogger(Provisioner.class);

  private final Git git;
  private final Checkout checkout;

  Provisioner(Git git, Checkout checkout) {
    this.git = git;
    this.checkout = checkout;
  }

  /** Answers null once the checkout exists, else why it does not. */
  String provision() {
    if (checkout.cloned()) {
      LOG.infof("Provisioner: %s is already cloned; the follower catches it up", checkout.root());
      return null;
    }
    Path root = checkout.root();
    try {
      Files.createDirectories(root.getParent());
    } catch (java.io.IOException e) {
      return "cannot create " + root.getParent() + ": " + e.getMessage();
    }
    LOG.infof("Provisioner: cloning %s into %s", checkout.wrapperUrl(), root);
    Git.Result cloned = git.run("clone", "-q", checkout.wrapperUrl(), root.toString());
    if (!cloned.ok()) {
      return "git clone " + checkout.wrapperUrl() + " failed: " + Checkout.firstLine(cloned.message());
    }
    var materialised = checkout.materializeNew(LOG::warn);
    LOG.infof(
        "Provisioner: cloned %s with %d submodule(s), each on its own main",
        checkout.wrapperName(), materialised.size());
    return null;
  }
}
