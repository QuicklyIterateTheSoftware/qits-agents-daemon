package eu.wohlben.qits.agentsdaemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The notice's substance, point by point, by key phrase — not its wording. */
class ResumeNoticeTest {

  final String notice = ResumeNotice.text();

  @Test
  void oneTheSessionWasRestartedAndResumed() {
    assertTrue(notice.contains("restarted and resumed"), notice);
  }

  @Test
  void twoEverySubagentIsGoneAndWillNotReportBack() {
    assertTrue(notice.contains("every subagent you had running is gone"), notice);
    assertTrue(notice.contains("will not report back"), notice);
  }

  @Test
  void threeTheWorkIsListedAndEachItemReadAndHandedOnWhereItLeftOff() {
    assertTrue(notice.contains("`qits work list --assigned-to-me`"), notice);
    assertTrue(notice.contains("read its thread and its worktrees' branches"), notice);
    assertTrue(notice.contains("hand it to a new subagent where it left off"), notice);
  }

  @Test
  void itIsOneParagraph() {
    assertTrue(!notice.contains("\n"), notice);
  }
}
