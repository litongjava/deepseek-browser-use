package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * 命令级 wall-clock 兜底(见 {@code ActionService.awaitWithLimit})
 *
 * <p>
 * 背景:实测 2026-10-05 在真服务上,{@code execute_js} 里一个永不 resolve 的 Promise 让请求挂过
 * 120 秒仍无响应 —— 那时这条命令**没有任何上限**,调用方只能靠 HTTP 超时放弃,而放弃不会取消
 * 服务端已经在跑的东西。这一层就是「一定有答复」的最后一道闸。
 */
public class CommandTimeoutTest {

  @Test
  public void slowCommandIsCutOffWithUncertainOutcome() {
    AtomicBoolean finished = new AtomicBoolean(false);
    RespBodyVo result = ActionService.awaitWithLimit(120, "execute_js", () -> {
      try {
        Thread.sleep(2000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return RespBodyVo.fail("被中断");
      }
      finished.set(true);
      return RespBodyVo.ok(Kv.by("late", true));
    });
    assertFalse("超时必须回一句失败,而不是一直等", result.isOk());
    Kv data = (Kv) result.getData();
    assertEquals(ActionError.COMMAND_TIMEOUT, data.getStr("errorCode"));
    // 已经开始执行 → 结果未知、不可重试:重发可能重复执行副作用
    assertEquals(Boolean.TRUE, data.get("started"));
    assertEquals(Boolean.FALSE, data.get("retryable"));
    assertEquals(Boolean.TRUE, data.get("outcomeUnknown"));
    assertEquals(120L, ((Number) data.get("commandTimeoutMs")).longValue());
    assertTrue(data.getStr("note").contains("不要直接重发"));
    // 关键:底层还在跑,但**调用方已经拿到结论**了 —— 这正是「不挂死」的含义
    assertFalse("调用方不该等到底层跑完", finished.get());
  }

  @Test
  public void fastCommandPassesThroughUntouched() {
    RespBodyVo ok = RespBodyVo.ok(Kv.by("result", 42));
    RespBodyVo result = ActionService.awaitWithLimit(5000, "get_url", () -> ok);
    assertTrue(result.isOk());
    assertEquals(ok.getData(), result.getData());
  }

  @Test
  public void zeroLimitMeansNoBackstop() {
    // 0 = 关闭兜底:此时必须**原样同步执行**,不能偷偷换成超时语义
    RespBodyVo result = ActionService.awaitWithLimit(0, "get_url", () -> RespBodyVo.ok(Kv.by("sync", true)));
    assertTrue(result.isOk());
    assertEquals(Boolean.TRUE, ((Kv) result.getData()).get("sync"));
  }

  @Test
  public void queuedButNotStartedIsRetryable() {
    // 池被卡满时,后面的命令会在队列里等到过期 —— 它**根本没开始执行**,重发是安全的。
    // 这条与上面「结果未知」必须分开:把没跑过的命令说成「可能已生效」会让调用方白白放弃它。
    RespBodyVo queued = ActionService.commandTimeoutFailure("click_element_by_index", 90000, false);
    Kv queuedData = (Kv) queued.getData();
    assertEquals(ActionError.COMMAND_TIMEOUT, queuedData.getStr("errorCode"));
    assertEquals(Boolean.FALSE, queuedData.get("started"));
    assertEquals(Boolean.TRUE, queuedData.get("retryable"));
    assertNull(queuedData.get("outcomeUnknown"));
    assertTrue(queuedData.getStr("note").contains("重发是安全的"));
  }
}
