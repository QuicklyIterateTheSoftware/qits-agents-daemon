package eu.wohlben.qits.agentsdaemon;

/**
 * The platform-owned half of the agent's appended system prompt: how this container works, in
 * short imperative paragraphs. The project's own half — the declaration's {@code brief} — follows
 * it, after a blank line.
 *
 * <p>The text is the platform's, not the project's, because it describes this daemon: a checkout
 * that follows every commit, worktrees outside it, pushes made for the agent. qits-2520 may reword
 * it; {@code OverseerBriefTest} pins its substance point by point, not its wording.
 */
final class OverseerBrief {

  private OverseerBrief() {}

  static String render(String project, String name, String wrapper) {
    String checkout = "/workspace/" + wrapper;
    return String.join(
        "\n\n",
        "You are the long-running agent `"
            + name
            + "` of project `"
            + project
            + "`. Your checkout at `"
            + checkout
            + "` follows every commit on the platform: the wrapper and each submodule sit at their"
            + " own `origin/main` and move as soon as anything lands. It is read-only to you: never"
            + " edit, commit or switch branches in it.",
        "For each work item you take, create its worktrees outside the checkout. The wrapper's:"
            + " `git -C "
            + checkout
            + " worktree add /workspace/worktrees/<qualified id>/"
            + wrapper
            + " -b <ticket|epic>/<qualified id> origin/main`. For each submodule the item touches:"
            + " `git -C "
            + checkout
            + "/<submodule path> worktree add /workspace/worktrees/<qualified id>/<repo> -b"
            + " <ticket|epic>/<qualified id> origin/main`.",
        "Hand each item to a subagent, giving it those worktree paths and the item's qualified id,"
            + " and keep only the planning and the checking for yourself. Several items may run at"
            + " once, each in its own worktrees.",
        "Commits on those branches are pushed for you within seconds. Nobody runs `git push`, and"
            + " nobody force-pushes.",
        "When an item's work is released and resolved, remove its worktrees through the repository"
            + " that made each one: `git -C <repo> worktree remove <path>`.");
  }
}
