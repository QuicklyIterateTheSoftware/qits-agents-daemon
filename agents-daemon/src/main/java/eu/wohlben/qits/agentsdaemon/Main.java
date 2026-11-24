package eu.wohlben.qits.agentsdaemon;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/** The container's entrypoint: start the daemon, then live until the container stops. */
@QuarkusMain
public class Main {

  public static void main(String... args) {
    Quarkus.run(DaemonApplication.class, args);
  }

  public static class DaemonApplication implements QuarkusApplication {

    @Inject AgentDaemon daemon;

    @Override
    public int run(String... args) {
      daemon.start();
      Quarkus.waitForExit();
      return 0;
    }
  }
}
