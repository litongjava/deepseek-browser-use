package nexus.io.ai.browser.service;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;

public class ResponseCorrelationTest {
  private Request request() {
    return (Request) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Request.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "method" -> "POST";
          case "url" -> "http://localhost/query";
          case "resourceType" -> "fetch";
          case "postData" -> "same-body";
          default -> throw new AssertionError(method.getName());
        });
  }

  private Response response(Request request, java.util.function.Supplier<String> body) {
    return (Response) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Response.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "request" -> request;
          case "status" -> 200;
          case "text" -> body.get();
          default -> throw new AssertionError(method.getName());
        });
  }

  @Test public void captureRunsOnCallerThreadAndOnlyOnce() throws Exception {
    Thread caller = Thread.currentThread();
    AtomicReference<Thread> reader = new AtomicReference<>();
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch read = new CountDownLatch(1);
    BrowserInstance.RecordedResponse recorded = new BrowserInstance.RecordedResponse(response(request(), () -> {
      reads.incrementAndGet();
      reader.set(Thread.currentThread());
      read.countDown();
      return "x".repeat(ResponseBodyCache.MAX_CHARS + 1);
    }), 0, Kv.by("resourceType", "fetch"));
    ResponseBodyCache.capture(recorded);
    assertTrue(read.await(2, TimeUnit.SECONDS));
    assertSame("响应体读取不能在后台线程驱动 Playwright", caller, reader.get());
    assertTrue(recorded.bodyCaptured);
    assertTrue(recorded.bodyTruncated);
    assertEquals(ResponseBodyCache.MAX_CHARS, recorded.body.length());
    ResponseBodyCache.capture(recorded);
    assertEquals(1, reads.get());
  }

  @Test public void cacheFailureIsReportedWithoutEscapingCallback() {
    BrowserInstance.RecordedResponse recorded = new BrowserInstance.RecordedResponse(response(request(), () -> {
      throw new PlaywrightException("body unavailable");
    }), 0, Kv.by("resourceType", "xhr"));
    ResponseBodyCache.capture(recorded);
    assertTrue(recorded.bodyCaptured);
    assertNull(recorded.body);
    assertTrue(recorded.bodyCaptureError.contains("body unavailable"));
  }

  @Test public void missingCorrelationIsExplicitAndNeverStealsSameUrlRequest() {
    BrowserInstance inst = new BrowserInstance(1, null, null, null, null);
    Request pending = request();
    Kv entry = Kv.by("requestId", "original").set("url", pending.url()).set("method", pending.method());
    inst.requests.add(entry);
    inst.requestIndex.put(pending, entry);
    inst.inflight.incrementAndGet();
    Response orphan = response(request(), () -> "orphan");
    BrowserInstance.RecordedResponse missed = PlaywrightService.recordResponse(inst, orphan);
    assertEquals(true, missed.request.get("correlationMissed"));
    assertEquals("none", missed.request.getStr("correlatedBy"));
    assertNotEquals("original", missed.request.getStr("requestId"));
    assertEquals(1, inst.inflight.get());
    assertNull(entry.get("status"));
    assertSame(missed, PlaywrightService.recordResponse(inst, orphan));
    assertEquals(2, inst.requests.size());
    assertEquals(1, inst.recentResponses.size());

    Response actual = response(pending, () -> "actual");
    BrowserInstance.RecordedResponse matched = PlaywrightService.recordResponse(inst, actual);
    assertEquals("original", matched.request.getStr("requestId"));
    assertEquals(false, matched.request.get("correlationMissed"));
    assertEquals(200, entry.getInt("status").intValue());
    assertNotNull(entry.get("respondedAt"));
    assertEquals(0, inst.inflight.get());
    assertSame(matched, PlaywrightService.recordResponse(inst, actual));
    assertEquals(0, inst.inflight.get());
    assertEquals(2, inst.requests.size());
  }
}
