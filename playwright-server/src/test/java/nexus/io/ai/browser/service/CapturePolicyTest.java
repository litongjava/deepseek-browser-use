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
}
