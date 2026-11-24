package eu.wohlben.qits.agentsdaemon.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What this jar was released with</b>: the {@code qits/agent} image tag of the release that
 * published this artifact.
 *
 * <p>qits-agents-service pins the agent image by depending on this jar, so its pom — not a
 * configuration entry — decides which image a project's long-running agent runs, and the
 * maintenance bump moves the pair like any internal library. The same arrangement as {@code
 * WorkspaceImage} in qits-workspace-daemon-protocol.
 *
 * <p><b>The value is this module's {@code ${project.version}}, resolved at build time</b> into
 * {@code agent-image.properties} beside this class. The release flow stamps the pom with the version
 * it is about to tag, and the same release pushes the image under that tag, so the jar's version and
 * the image tag are one string by construction.
 */
public final class AgentImage {

  /** The resource the build filters {@code ${project.version}} into, beside this class. */
  private static final String RESOURCE = "agent-image.properties";

  /**
   * The image repository the agent container is started from, unqualified: the reader supplies its
   * own registry host.
   */
  public static final String REPOSITORY = "qits/agent";

  /** The released version: the {@code qits/agent} tag of the release that published this jar. */
  public static final String VERSION = readVersion();

  private static String readVersion() {
    Properties p = new Properties();
    try (InputStream in = AgentImage.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            RESOURCE + " is not on the classpath beside " + AgentImage.class.getName());
      }
      p.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
    String version = p.getProperty("version", "");
    if (version.isBlank() || version.startsWith("$")) {
      // `$` catches resource filtering switched off: the literal `${project.version}` as a tag.
      throw new IllegalStateException(RESOURCE + " carries no resolved version: '" + version + "'");
    }
    return version;
  }

  private AgentImage() {}
}
