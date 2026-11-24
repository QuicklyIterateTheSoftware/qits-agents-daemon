package eu.wohlben.qits.agentsdaemon;

import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Reads qits-events' SSE stream for the two events this daemon acts on, the way {@code
 * ReleaseWatcher} in qits-platform-access-monorepo reads it: reconnect with backoff (1, 2, 4 … 30
 * s, reset on connect), a stream silent for 60 s counts as dropped, and a full catch-up after every
 * connect — with the stream already open, so nothing falls between the two. The stream has no
 * replay, so the catch-up is what covers whatever happened while it was down.
 *
 * <ul>
 *   <li>{@code SCMPublishCommit} of this project → {@link Listener#commit} with the repository's
 *       name ({@code repoName}, or {@code repoId} resolved through qits-projects when the event
 *       carries no name) and the branch.
 *   <li>{@code ProjectAgentChanged} of this agent → {@link Listener#agentChanged}.
 * </ul>
 */
final class EventFollower {

  private static final Logger LOG = Logger.getLogger(EventFollower.class);

  static final String COMMIT = "SCMPublishCommit";
  static final String AGENT_CHANGED = "ProjectAgentChanged";

  static final Duration IDLE_LIMIT = Duration.ofSeconds(60);
  static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
  static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  /** What the frames are handed to. Called on the reader thread: hand off, never block. */
  interface Listener {
    void connected();

    void commit(String repoName, String branch);

    void agentChanged();
  }

  private final HttpClient http;
  private final URI stream;
  private final String bearer;
  private final Supplier<String> projectId;
  private final String agentName;
  private final RepositoryNames names;
  private final Listener listener;

  private volatile boolean stopped;
  private volatile InputStream current;
  private Thread reader;

  /**
   * @param eventsUrl qits-events' base, {@code https://events.qits.<domain>}
   * @param projectId this agent's project id, from {@code launch}; null until the first launch
   */
  EventFollower(
      HttpClient http,
      String eventsUrl,
      String token,
      Supplier<String> projectId,
      String agentName,
      RepositoryNames names,
      Listener listener) {
    this.http = http;
    this.stream =
        URI.create(
            Checkout.trimSlash(eventsUrl) + "/events/api/stream?names=" + COMMIT + "," + AGENT_CHANGED);
    this.bearer = "Bearer " + token;
    this.projectId = projectId;
    this.agentName = agentName;
    this.names = names;
    this.listener = listener;
  }

  void start() {
    reader = Thread.ofPlatform().daemon().name("agents-daemon-events").start(this::loop);
  }

  void stop() {
    stopped = true;
    close(current);
    if (reader != null) {
      reader.interrupt();
    }
  }

  private void loop() {
    ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("agents-daemon-events-watchdog").unstarted(r));
    Duration backoff = FIRST_BACKOFF;
    boolean wasConnected = false;
    try {
      while (!stopped) {
        String trouble;
        try {
          HttpRequest request =
              HttpRequest.newBuilder(stream)
                  .header("Authorization", bearer)
                  .header("Accept", "text/event-stream")
                  .GET()
                  .build();
          HttpResponse<InputStream> response =
              http.send(request, HttpResponse.BodyHandlers.ofInputStream());
          if (response.statusCode() != 200) {
            close(response.body());
            trouble = "the stream answered " + response.statusCode();
          } else {
            current = response.body();
            LOG.infof("%s %s", wasConnected ? "EventFollower connected again to" : "EventFollower connected to", stream);
            wasConnected = true;
            backoff = FIRST_BACKOFF;
            safely(listener::connected);
            trouble = read(response.body(), watchdog);
          }
        } catch (IOException e) {
          trouble = "the stream dropped (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } finally {
          current = null;
        }
        if (stopped) {
          return;
        }
        LOG.infof("EventFollower: %s; reconnecting in %d s", trouble, backoff.toSeconds());
        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
          return;
        }
        backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
      }
    } finally {
      watchdog.shutdownNow();
    }
  }

  private String read(InputStream body, ScheduledExecutorService watchdog) {
    SseParser parser = new SseParser();
    AtomicLong lastLine = new AtomicLong(System.nanoTime());
    long period = IDLE_LIMIT.toMillis() / 4;
    boolean[] idle = {false};
    ScheduledFuture<?> watch =
        watchdog.scheduleAtFixedRate(
            () -> {
              if (System.nanoTime() - lastLine.get() > IDLE_LIMIT.toNanos()) {
                idle[0] = true;
                close(body);
              }
            },
            period,
            period,
            TimeUnit.MILLISECONDS);
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (!stopped && (line = lines.readLine()) != null) {
        lastLine.set(System.nanoTime());
        Optional<SseParser.Event> event = parser.accept(line);
        if (event.isPresent()) {
          frame(event.get().data());
        }
      }
      return "the stream ended";
    } catch (IOException e) {
      return idle[0]
          ? "the stream sent nothing, not even a keepalive, for " + IDLE_LIMIT.toSeconds() + " s"
          : "the stream dropped (" + e.getMessage() + ")";
    } finally {
      watch.cancel(false);
    }
  }

  /** One frame: the EventCreated envelope, whose payload travels as a JSON string. */
  void frame(String data) {
    JsonObject envelope = parse(data);
    if (envelope == null) {
      return;
    }
    String name = envelope.getString("name");
    Object raw = envelope.getValue("payload");
    JsonObject payload =
        raw instanceof JsonObject object ? object : raw instanceof String text ? parse(text) : null;
    if (payload == null) {
      return;
    }
    String project = projectId.get();
    if (COMMIT.equals(name)) {
      if (project == null) {
        return; // before the first launch answer nothing tells this project's commits apart
      }
      String eventProject = text(payload, "projectId");
      if (eventProject != null && !eventProject.equals(project)) {
        return;
      }
      String repo = text(payload, "repoName");
      if (repo == null) {
        repo = names.nameOf(text(payload, "repoId"), project).orElse(null);
      } else if (eventProject == null) {
        return; // a name with no project cannot be told apart from another project's
      }
      String branch = text(payload, "branch");
      if (repo != null && branch != null) {
        String r = repo;
        safely(() -> listener.commit(r, branch));
      }
    } else if (AGENT_CHANGED.equals(name)) {
      if (agentName.equals(text(payload, "agentName"))
          && (project == null || project.equals(text(payload, "projectId")))) {
        safely(listener::agentChanged);
      }
    }
  }

  private static void safely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      LOG.warnf("EventFollower: a listener failed: %s", e.toString());
    }
  }

  private static JsonObject parse(String text) {
    try {
      return text == null ? null : new JsonObject(text);
    } catch (RuntimeException notJson) {
      return null;
    }
  }

  private static String text(JsonObject json, String field) {
    return json.getValue(field) instanceof String value && !value.isBlank() ? value : null;
  }

  private static void close(InputStream in) {
    if (in == null) {
      return;
    }
    try {
      in.close();
    } catch (IOException ignored) {
      // closing to unblock a reader; nothing to do
    }
  }
}
