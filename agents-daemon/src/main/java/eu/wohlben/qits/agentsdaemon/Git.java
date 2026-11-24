package eu.wohlben.qits.agentsdaemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} as a child process and captures what it said. Every git call this daemon makes
 * goes through here, so the one environment overlay (the tests' throw-away global config) and the
 * one timeout apply to all of them.
 *
 * <p>Never a shell: argv is passed as is. The child inherits this process's environment, which in
 * the container carries {@code GIT_CONFIG_GLOBAL=/etc/qits-gitconfig} and the {@code
 * QITS_GIT_AUTH_*} names its credential helper reads, so a fetch or a push authenticates as the
 * agent with no credential on any command line.
 */
public final class Git {

  /** One git invocation's outcome. */
  public record Result(int exit, String stdout, String stderr, boolean timedOut) {

    public boolean ok() {
      return exit == 0 && !timedOut;
    }

    /** The first line of stdout, trimmed; empty when there is none. */
    public String line() {
      String out = stdout.strip();
      int nl = out.indexOf('\n');
      return nl < 0 ? out : out.substring(0, nl).strip();
    }

    /** stderr, else stdout: what to quote when it failed. */
    public String message() {
      String err = stderr.strip();
      return err.isEmpty() ? stdout.strip() : err;
    }
  }

  static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final Map<String, String> environment;
  private final Duration timeout;

  /** Git with this process's environment and the default timeout. */
  public Git() {
    this(Map.of(), TIMEOUT);
  }

  /** Git with {@code environment} laid over this process's own. */
  public Git(Map<String, String> environment, Duration timeout) {
    this.environment = Map.copyOf(environment);
    this.timeout = timeout;
  }

  /** {@code git -C dir args…}. */
  public Result in(Path dir, String... args) {
    List<String> argv = new ArrayList<>(args.length + 3);
    argv.add("git");
    argv.add("-C");
    argv.add(dir.toString());
    argv.addAll(List.of(args));
    return run(argv);
  }

  /** {@code git args…}, in this process's working directory. */
  public Result run(String... args) {
    List<String> argv = new ArrayList<>(args.length + 1);
    argv.add("git");
    argv.addAll(List.of(args));
    return run(argv);
  }

  private Result run(List<String> argv) {
    ProcessBuilder builder = new ProcessBuilder(argv);
    builder.environment().putAll(environment);
    // Never prompt: a missing credential must fail, not hang a thread on a terminal nobody sees.
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new Result(127, "", "cannot start git: " + e.getMessage(), false);
    }
    CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(process.getInputStream()));
    CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new Result(-1, out.getNow(""), "timed out after " + timeout, true);
      }
      return new Result(process.exitValue(), out.join(), err.join(), false);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return new Result(-1, "", "interrupted", true);
    }
  }

  private static String drain(InputStream in) {
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
