package eu.wohlben.qits.agentsdaemon;

import eu.wohlben.qits.agents.AgentCommands;
import eu.wohlben.qits.agents.AgentLaunchService;
import eu.wohlben.qits.commands.AgentSessionRef;
import eu.wohlben.qits.commands.Command;
import eu.wohlben.qits.commands.CommandRegistry;
import eu.wohlben.qits.commands.CommandStatus;
import eu.wohlben.qits.commands.CommandStore;
import eu.wohlben.qits.commands.GuardedInput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link AgentKeeper.Harness} over the harness library, as qits-projects-daemon wires it. */
final class LibraryHarness implements AgentKeeper.Harness {

  private final AgentLaunchService launch;
  private final ProjectAgentDefaults defaults;
  private final CommandStore store;
  private final CommandRegistry registry;
  private final AgentCommands commands;
  private final String claudeMount;

  LibraryHarness(
      AgentLaunchService launch,
      ProjectAgentDefaults defaults,
      CommandStore store,
      CommandRegistry registry,
      AgentCommands commands,
      String claudeMount) {
    this.launch = launch;
    this.defaults = defaults;
    this.store = store;
    this.registry = registry;
    this.commands = commands;
    this.claudeMount = claudeMount;
  }

  @Override
  public synchronized String launch(AgentKeeper.Plan plan) {
    defaults.use(plan.configuration());
    return launch.launch(plan.request()).id();
  }

  @Override
  public boolean running(String commandId) {
    return store.find(commandId).map(c -> c.status() == CommandStatus.RUNNING).orElse(false)
        && registry.isRunning(commandId);
  }

  @Override
  public Optional<Integer> exitCode(String commandId) {
    return store.find(commandId).map(Command::exitCode);
  }

  @Override
  public Optional<String> sessionId(String commandId) {
    return store.find(commandId).map(Command::currentSession).map(AgentSessionRef::sessionId);
  }

  @Override
  public boolean transcriptExists(String sessionId) {
    Path root = Path.of(claudeMount, ".claude", "projects");
    if (!Files.isDirectory(root)) {
      return false;
    }
    String name = sessionId + ".jsonl";
    try (Stream<Path> walk = Files.walk(root, 3)) {
      return walk.anyMatch(p -> p.getFileName().toString().equals(name) && Files.isRegularFile(p));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean terminate(String commandId) {
    return registry.terminate(commandId);
  }

  @Override
  public GuardedInput type(String commandId, String text) {
    return commands.sendKeystrokesUnlessDraft(commandId, text);
  }
}
