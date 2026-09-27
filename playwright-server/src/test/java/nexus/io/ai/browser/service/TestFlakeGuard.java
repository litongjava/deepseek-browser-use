package nexus.io.ai.browser.service;

import com.microsoft.playwright.PlaywrightException;

/**
 * 测试基础设施的「伪故障」防护
 *
 * <p>
 * 生产代码对 Playwright 事件泵投递的伪故障({@code Object doesn't exist: request@… / response@…})有识别与
 * 有限重发(见 {@link ActionError#isSpuriousDispatch})，但**测试自己的裸 Playwright 调用没有**：用例里的
 * {@code page.navigate(..)} / {@code page.evaluate(..)} 一旦撞上它就直接报错，表现成"随机挂一条用例"。
 * 实测这类抖动会随机器负载出现（同一条用例单独跑三次全过、混在全量里偶尔挂）。
 *
 * <p>
 * 所以这里补上同一层防护：**只**对这类伪故障重发，其它异常原样抛出 —— 断言失败、元素不可见之类的问题绝不能
 * 被重试掩盖，否则测试就失去了分辨力。
 */
final class TestFlakeGuard {

  /** 含首次在内最多试几次 */
  private static final int MAX_ATTEMPTS = 4;

  /** 两次重发之间的间隔:伪故障是"消息泵里正在派发的那一条"引起的,挪开一点就够 */
  private static final long RETRY_DELAY_MS = 150;

  private TestFlakeGuard() {
  }

  /**
   * 跑一段可能撞伪故障的测试代码，撞上就重发
   *
   * @param what 出问题时打印的说明（便于看出是哪一步）
   * @param call 真正要做的事
   */
  static void retry(String what, Runnable call) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        call.run();
        return;
      } catch (PlaywrightException e) {
        if (!ActionError.isSpuriousDispatch(e.getMessage())) {
          throw e;
        }
        last = e;
        sleep();
      }
    }
    throw new IllegalStateException(
        what + " 连续 " + MAX_ATTEMPTS + " 次都撞上 Playwright 事件分发的伪故障（对象已释放）: "
            + (last == null ? "未知" : last.getMessage()),
        last);
  }

  /** 同上,带返回值 */
  static <T> T retry(String what, java.util.function.Supplier<T> call) {
    final Object[] holder = new Object[1];
    retry(what, () -> holder[0] = call.get());
    @SuppressWarnings("unchecked")
    T result = (T) holder[0];
    return result;
  }

  private static void sleep() {
    try {
      Thread.sleep(RETRY_DELAY_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
