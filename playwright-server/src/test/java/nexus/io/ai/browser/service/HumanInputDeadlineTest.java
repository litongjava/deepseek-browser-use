package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 人工待办过期时刻的优先级用例
 *
 * <p>
 * 这三个参数( {@code expiresAt} / {@code expiresInSeconds} / {@code timeoutSeconds} )说的是同一件事。
 * 之所以专门写用例,是因为「参数被静默忽略」那次事故就发生在这里:调用方按回执里<b>输出</b>的
 * {@code expiresInSeconds} 把参数写回来,却因为当时没有这个入参而落到默认值上,待办在人看到之前
 * 就过期了。优先级一旦被人无意改掉,这里会立刻挂。
 */
public class HumanInputDeadlineTest {

  private static final long NOW = 1_700_000_000_000L;

  /** 绝对时刻优先级最高:给了它就不看另外两个 */
  @Test
  public void expiresAtWins() {
    assertEquals(NOW + 111L, PlaywrightService.humanDeadline(NOW, 600, NOW + 111L, 900));
  }

  /** 回执里输出的那个名字必须真的能当入参用 */
  @Test
  public void expiresInSecondsIsHonored() {
    assertEquals(NOW + 3_600_000L, PlaywrightService.humanDeadline(NOW, null, null, 3600));
  }

  /** 两个相对时长都给时,以「回执里输出的那个名字」为准 */
  @Test
  public void expiresInSecondsBeatsTimeoutSeconds() {
    assertEquals(NOW + 3_600_000L, PlaywrightService.humanDeadline(NOW, 300, null, 3600));
  }

  /** 历史名字 timeoutSeconds 仍然有效,不能被这次改动弄坏 */
  @Test
  public void timeoutSecondsStillWorks() {
    assertEquals(NOW + 600_000L, PlaywrightService.humanDeadline(NOW, 600, null, null));
  }

  /**
   * 都不给时落到默认时长
   *
   * <p>
   * 这里写死 300 是有意的:它就是文档里写的默认值。默认值哪天被改,这条用例会跟着红一次,
   * 提醒把文档一起改掉——总比文档和代码各自说各话好。
   */
  @Test
  public void fallsBackToTheDocumentedDefault() {
    assertEquals(NOW + 300_000L, PlaywrightService.humanDeadline(NOW, null, null, null));
    assertEquals(NOW + 300_000L, PlaywrightService.humanDeadline(NOW, 0, null, 0));
    assertEquals(NOW + 300_000L, PlaywrightService.humanDeadline(NOW, -5, null, -5));
  }
}
