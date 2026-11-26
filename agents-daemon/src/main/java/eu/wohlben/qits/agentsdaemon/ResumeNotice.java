package eu.wohlben.qits.agentsdaemon;

/**
 * What a <em>resumed</em> session is told first: the session behind the transcript is a new harness
 * process, and the subagents the old one ran died with it. A resumed transcript still shows them
 * running, so without this the agent waits for reports that never come — the generation's recovery
 * prompt is a fresh session's, and a resumed one never gets it.
 *
 * <p>Platform-owned, like {@link OverseerBrief}: it describes this daemon's relaunches (a crash, an
 * OOM kill, a container recreated by a spec roll). {@code ResumeNoticeTest} pins its substance, not
 * its wording.
 */
final class ResumeNotice {

  private ResumeNotice() {}

  static String text() {
    return "Your session was restarted and resumed: every subagent you had running is gone and will"
        + " not report back. Run `qits work list --assigned-to-me`, and for each item read its"
        + " thread and its worktrees' branches, and hand it to a new subagent where it left off.";
  }
}
