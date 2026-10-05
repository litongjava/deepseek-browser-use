package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;
import nexus.io.model.body.RespBodyVo;

public class CapturePolicyTest {
  private BrowserInstance instance() { return new BrowserInstance(42L, null, null, null, null); }
  private PlaywrightException timeout() { return new PlaywrightException("Timeout 30000ms exceeded"); }

  @Test public void automaticAndManualShareCircuitAndForceProbesOnlyOnce() {
    BrowserInstance inst = instance(); AtomicInteger calls = new AtomicInteger();
    for (int i=0; i<PlaywrightService.captureFailThreshold(); i++)
      CapturePolicy.run(inst,"viewport",false,false,1000,(mode,ms)->{calls.incrementAndGet();throw timeout();});
    int before=calls.get();
    CapturePolicy.Result blocked=CapturePolicy.run(inst,"fullPage",false,true,1000,(mode,ms)->{calls.incrementAndGet();return new byte[1];});
    assertFalse(blocked.ok()); assertEquals(before,calls.get());
    assertEquals("circuit-open",((Kv)blocked.data().get("capture")).get("stage"));
    assertTrue(((Number)blocked.data().get("retryAfterMs")).longValue()>0);
    CapturePolicy.Result failedProbe=CapturePolicy.run(inst,"fullPage",true,true,1000,(mode,ms)->{calls.incrementAndGet();throw new PlaywrightException("Object doesn't exist: response@1");});
    assertFalse(failedProbe.ok());assertEquals(before+1,calls.get());
    CapturePolicy.Result restored=CapturePolicy.run(inst,"viewport",true,false,1000,(mode,ms)->new byte[1]);
    assertTrue(restored.ok());assertEquals(0,inst.captureFailures.get());assertEquals(0,inst.captureCooldownUntil);
  }

  @Test public void fullPageFallbackIsExplicitAndUsesRemainingBudget() {
    BrowserInstance inst=instance();List<String> modes=new ArrayList<>();List<Double> timeouts=new ArrayList<>();
    CapturePolicy.Result result=CapturePolicy.run(inst,"fullPage",false,true,1000,(mode,ms)->{
      modes.add(mode);timeouts.add(ms);if(mode.equals("fullPage"))throw timeout();return new byte[1];});
    assertTrue(result.ok());assertEquals(List.of("fullPage","viewport"),modes);
    assertTrue(timeouts.get(0)<=500);assertTrue(timeouts.get(1)<=1000);
    assertEquals(false,result.data().get("fullPageCaptured"));assertEquals(true,result.data().get("fallbackUsed"));
    assertNotNull(result.data().get("warning"));assertNotNull(result.data().get("fallbackReason"));
    assertEquals(2,((Kv)result.data().get("capture")).getInt("attempts").intValue());
  }

  @Test public void ordinaryFailureDoesNotSilentlyFallBack() {
    AtomicInteger calls=new AtomicInteger();
    CapturePolicy.Result result=CapturePolicy.run(instance(),"fullPage",false,false,1000,(mode,ms)->{calls.incrementAndGet();throw timeout();});
    assertFalse(result.ok());assertEquals(1,calls.get());assertNull(result.data().get("fallbackUsed"));
  }

  @Test public void nestedRetryHelpersNeverMultiplyAttempts() {
    AtomicInteger calls=new AtomicInteger();
    try {
      ActionService.dispatchWithSpuriousRetry("screenshot",()->{
        PlaywrightService.spuriousRetry(()->{calls.incrementAndGet();throw new PlaywrightException("Object doesn't exist: response@1");});
        return RespBodyVo.ok();
      });fail("must fail after shared allowance");
    } catch(PlaywrightException expected) { assertEquals(3,calls.get()); }
    assertNull(RetryBudget.current());
  }

  @Test public void forceIsSingleProbeEvenThroughDispatcher() {
    AtomicInteger calls=new AtomicInteger();JSONObject params=new JSONObject();params.put("force",true);
    RespBodyVo result=ActionService.dispatchWithSpuriousRetry("screenshot",params,()->{
      CapturePolicy.Result capture=CapturePolicy.run(instance(),"viewport",true,false,1000,(mode,ms)->{calls.incrementAndGet();throw new PlaywrightException("Object doesn't exist: response@1");});
      RespBodyVo failure=RespBodyVo.fail(capture.data().getStr("screenshot_error"));failure.setData(capture.data());return failure;
    });assertFalse(result.isOk());assertEquals(1,calls.get());
  }

  @Test public void monotonicDeadlineAndNestedBudgetAreShared() {
    AtomicLong now=new AtomicLong();
    try(RetryBudget outer=RetryBudget.open(500,now::get)) {
      assertTrue(outer.retry());
      try(RetryBudget inner=RetryBudget.open(1000,now::get)) {
        assertEquals(500,inner.remainingMs());assertTrue(inner.retry());assertFalse(inner.retry());
        now.set(501_000_000L);assertEquals(0,inner.remainingMs());assertFalse(inner.retry());
      }
      assertSame(outer,RetryBudget.current());assertEquals(2,outer.retries());
    }assertNull(RetryBudget.current());
  }

  /**
   * 冷却到期 = 半开:计数要清零,否则有效阈值退化成「1 次失败」
   *
   * <p>旧实现只在**成功**时清零,于是一旦熔断过,恢复后的第一次偶发超时(计数还是 3)立刻又熔断
   * 120 秒 —— 实测表现就是「怎么试都没图,只能重开任务」。修好之后:熔断→到期→**重新累计**
   * failThreshold 次才再熔断。
   */
  @Test public void expiredCooldownIsHalfOpenAndResetsCounters() {
    BrowserInstance inst=instance();
    for(int i=0;i<PlaywrightService.captureFailThreshold();i++)
      CapturePolicy.run(inst,"viewport",false,false,1000,(m,ms)->{throw timeout();});
    assertTrue("到阈值就该熔断",inst.captureCooldownUntil>System.currentTimeMillis());
    int failuresBefore=inst.captureFailures.get();
    assertTrue(failuresBefore>=PlaywrightService.captureFailThreshold());

    // 把冷却推到过去,再失败**一次**:这一次必须只是重新累计(不熔断)
    inst.captureCooldownUntil=System.currentTimeMillis()-1;
    CapturePolicy.Result again=CapturePolicy.run(inst,"viewport",false,false,1000,(m,ms)->{throw timeout();});
    assertFalse(again.ok());
    assertEquals("半开时计数要清零后再累计",1,inst.captureFailures.get());
    assertTrue("只失败一次不该又熔断",inst.captureCooldownUntil<=System.currentTimeMillis());
    assertEquals(Boolean.TRUE,((Kv)again.data().get("capture")).get("halfOpen"));
  }

  /**
   * 冷却期内的显式探测失败:**保持原有冷却**,既不延长也不缩短
   *
   * <p>文档承诺的是「失败继续冷却」;旧实现用 captureCooldownMs() 重算,会把伪故障那 15 秒
   * 直接放大成 120 秒 —— 于是「探一次看看恢复没有」反而把画面关得更久。
   */
  @Test public void forcedProbeFailureKeepsExistingCooldown() {
    BrowserInstance inst=instance();
    long shortCooldown=System.currentTimeMillis()+PlaywrightService.captureSpuriousCooldownMs();
    inst.captureCooldownUntil=shortCooldown;
    inst.captureFailureReason="之前失败过";
    CapturePolicy.run(inst,"viewport",true,false,1000,(m,ms)->{throw new PlaywrightException("Object doesn't exist: response@1");});
    long after=inst.captureCooldownUntil;
    assertTrue("冷却不该被延长到 120 秒,实际剩余 "+(after-System.currentTimeMillis())+"ms",
        after-System.currentTimeMillis()<=PlaywrightService.captureSpuriousCooldownMs()+50);
    assertTrue("也不该被缩短到 0(那等于探测失败反而放开了)",after>System.currentTimeMillis());
  }

  /** 预算快用完时不该再发一个「必然超时」的回退请求(旧实现会给出 1 毫秒的截图超时) */
  @Test public void viewportFallbackIsSkippedWhenBudgetIsTooSmall() {
    BrowserInstance inst=instance();AtomicInteger calls=new AtomicInteger();List<Double> timeouts=new ArrayList<>();
    CapturePolicy.Result result=CapturePolicy.run(inst,"fullPage",false,true,500,(m,ms)->{
      calls.incrementAndGet();timeouts.add(ms);throw timeout();});
    assertFalse(result.ok());
    assertEquals("只试了一次,没有用剩余 1 毫秒再试一次",1,calls.get());
    assertTrue("全页那一次拿到的也不能是 1 毫秒级:"+timeouts,timeouts.get(0)>=250);
    assertNull("没有回退就不该写 fallbackUsed",result.data().get("fallbackUsed"));
  }
}
