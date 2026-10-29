package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.jfinal.kit.Kv;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.model.body.RespBodyVo;

public class CommandReliabilityTest {
  @Test public void failedPreconditionNeverDispatchesAction() {
    AtomicInteger actions = new AtomicInteger();
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo executeJs(Long id, String body, String file, JSONObject vars, String frame, Integer timeout) {
        if ("action".equals(body)) { actions.incrementAndGet(); }
        return RespBodyVo.ok(Kv.by("result", "mock"));
      }
    };
    RespBodyVo result = new ActionService(stub).execute(90019L, "execute_js", JSONObject.of("body", "action",
        "precondition", JSONObject.of("js", "environment", "equals", "real")));
    assertFalse(result.isOk());
    assertEquals(0, actions.get());
    assertFalse(((Kv) result.getData()).getBoolean("actionExecuted"));
    assertEquals("mock", ((Kv) ((Kv) result.getData()).get("preconditionResult")).get("actual"));
  }

  @Test public void failedExpectationDoesNotRepeatActionAndStaleSnapshotDoesNotDispatch() {
    AtomicInteger actions = new AtomicInteger();
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo executeJs(Long id, String body, String file, JSONObject vars, String frame, Integer timeout) {
        if ("action".equals(body)) { actions.incrementAndGet(); }
        return RespBodyVo.ok(Kv.by("result", false));
      }
    };
    ActionService service = new ActionService(stub);
    RespBodyVo failed = service.execute(90004L, "execute_js", JSONObject.of("body", "action",
        "expect", JSONObject.of("js", "false", "truthy", true, "timeoutMs", 100)));
    assertFalse(failed.isOk());
    assertTrue(((Kv) failed.getData()).getBoolean("actionSucceeded"));
    assertEquals(1, actions.get());
    RespBodyVo stale = service.execute(90004L, "execute_js", JSONObject.of("body", "action", "snapshotVersion", 1));
    assertFalse(stale.isOk());
    assertEquals("STALE_SNAPSHOT", ((Kv) stale.getData()).getStr("errorCode"));
    assertEquals(1, actions.get());
  }

  @Test public void asyncBatchOwnsTaskAcrossSteps() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger actions = new AtomicInteger();
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo getTitle(Long id) {
        actions.incrementAndGet();
        entered.countDown();
        try { release.await(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return RespBodyVo.ok(Kv.by("title", "ready"));
      }
    };
    ActionService service = new ActionService(stub);
    RespBodyVo submitted = service.execute(90005L, "commands", JSONObject.of("async", true,
        "commands", JSONArray.of(JSONObject.of("get_title", new JSONObject()), JSONObject.of("get_title", new JSONObject()))));
    assertTrue(submitted.isOk());
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertNotNull(CommandControl.diagnostics(90005L).get("active"));
      assertFalse(service.execute(90005L, "get_title", new JSONObject()).isOk());
    } finally {
      release.countDown();
    }
    String jobId = ((Kv) submitted.getData()).getStr("jobId");
    JobRegistry.Job job = JobRegistry.get(jobId);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (job.result == null && System.nanoTime() < deadline) { Thread.sleep(10); }
    assertNotNull(job.result);
    assertTrue(job.result.getMsg(), job.result.isOk());
    assertEquals(2, actions.get());
  }

  @Test public void millisecondWaitAliasAndConflictsAreExplicit() {
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo waitForText(Long id, String text, Double seconds) {
        return RespBodyVo.ok(Kv.by("seconds", seconds));
      }
    };
    RespBodyVo defaults = CommandTable.get("wait_for_text").run(stub, 1L, JSONObject.of("text", "ready"));
    assertNull(((Kv) defaults.getData()).get("seconds"));
    RespBodyVo response = CommandTable.get("wait_for_text").run(stub, 1L,
        JSONObject.of("text", "ready", "timeoutMs", 125));
    assertEquals(0.125, ((Number) ((Kv) response.getData()).get("seconds")).doubleValue(), 0.0001);
    try {
      CommandTable.get("wait_for_text").run(stub, 1L,
          JSONObject.of("text", "ready", "timeoutMs", 100, "timeoutSeconds", 4));
      fail("Conflicting units must not be ignored");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("disagree"));
    }
  }

  @Test public void assertionPollingNeverRepeatsActionAndRetainsReadback() {
    AtomicInteger actions = new AtomicInteger();
    AtomicInteger observations = new AtomicInteger();
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo executeJs(Long id, String body, String file, JSONObject vars, String frame, Integer timeout) {
        Object value = switch (body) {
          case "action" -> actions.incrementAndGet();
          case "condition" -> observations.incrementAndGet() >= 3;
          default -> "saved";
        };
        return RespBodyVo.ok(Kv.by("result", value));
      }
      @Override public RespBodyVo executeJs(Long id, String body) {
        return executeJs(id, body, null, null, null, null);
      }
    };
    RespBodyVo response = new ActionService(stub).execute(90001L, "execute_js",
        JSONObject.of("body", "action", "expect", JSONObject.of("js", "condition", "truthy", true, "timeoutMs", 2000), "readback", "read"));
    assertTrue(response.getMsg(), response.isOk());
    Kv data = (Kv) response.getData();
    assertEquals(1, actions.get());
    assertEquals(3, observations.get());
    assertTrue(((Kv) data.get("expectResult")).getBoolean("passed"));
    assertEquals("saved", ((Kv) data.get("readback")).getStr("result"));
    assertTrue(((Kv) data.get("execution")).getBoolean("actionExecuted"));
  }

  @Test public void cancellationKeepsTaskReservedUntilUninterruptibleWorkerExits() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    AtomicReference<RespBodyVo> result = new AtomicReference<>();
    PlaywrightService stub = new PlaywrightService() {
      @Override public RespBodyVo getTitle(Long id) {
        entered.countDown();
        boolean waiting = true;
        while (waiting) {
          try { waiting = !release.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException ignored) { /* Simulate an uninterruptible browser call. */ }
        }
        exited.countDown();
        return RespBodyVo.ok(Kv.by("title", "late"));
      }
    };
    ActionService service = new ActionService(stub);
    Thread caller = new Thread(() -> result.set(service.execute(90002L, "get_title", JSONObject.of("commandTimeoutMs", 5000))));
    caller.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Kv active = (Kv) CommandControl.diagnostics(90002L).get("active");
      assertFalse(CommandControl.cancel(90002L, "stale-command-id").getBoolean("accepted"));
      assertFalse(((Kv) CommandControl.diagnostics(90002L).get("active")).getBoolean("cancelRequested"));
      RespBodyVo cancelled = service.execute(90002L, "cancel_command", JSONObject.of("commandId", active.getStr("commandId")));
      assertTrue(((Kv) cancelled.getData()).getBoolean("accepted"));
      caller.join(2000);
      assertFalse(caller.isAlive());
      assertFalse(result.get().isOk());
      assertEquals("COMMAND_CANCELLED", ((Kv) result.get().getData()).getStr("errorCode"));
      assertFalse(service.execute(90002L, "get_title", new JSONObject()).isOk());
      Kv diagnostic = (Kv) service.execute(90002L, "get_diagnostics", new JSONObject()).getData();
      assertTrue(((Kv) diagnostic.get("active")).getBoolean("workerRunning"));
      assertNull(((Kv) diagnostic.get("active")).get("actionExecuted"));
    } finally {
      release.countDown();
      assertTrue(exited.await(2, TimeUnit.SECONDS));
      caller.join(2000);
    }
  }

  @Test public void cancellationBeforeDispatchNeverCallsBody() {
    CommandControl control = CommandControl.claim(90003L, "click_element_by_index");
    AtomicInteger actions = new AtomicInteger();
    CommandControl.bind(control);
    try {
      control.cancel();
      RespBodyVo result = ActionService.awaitWithLimit(1000, "click_element_by_index", () -> {
        actions.incrementAndGet();
        return RespBodyVo.ok();
      });
      assertFalse(result.isOk());
      assertEquals(0, actions.get());
      assertEquals(Boolean.FALSE, control.describe().get("actionExecuted"));
    } finally {
      control.finish(false);
      CommandControl.bind(null);
    }
  }
}
