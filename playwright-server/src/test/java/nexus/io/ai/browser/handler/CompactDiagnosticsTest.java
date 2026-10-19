package nexus.io.ai.browser.handler;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.jfinal.kit.Kv;
import nexus.io.model.body.RespBodyVo;

public class CompactDiagnosticsTest {
  private Kv screenshot() {
    return Kv.by("path", "data/1/shot.png").set("url", "/data/1/shot.png").set("size", 85297)
        .set("inline", false).set("base64Omitted", true).set("note", "默认不返回 base64")
        .set("execution", Kv.by("status", "completed").set("actionStatus", "completed")
            .set("observationCompleted", true).set("workerRunning", false))
        .set("retryBudget", Kv.by("retries", 0).set("attempts", 1))
        .set("capture", Kv.by("stage", "complete").set("retries", 0))
        .set("capture_degraded", false);
  }

  @Test public void successfulScreenshotIsSmallWithoutMutatingOriginal() {
    Kv original = screenshot();
    Map<?, ?> result = (Map<?, ?>) ResponseFormatter.project("screenshot", original, false);
    assertEquals(3, result.size());
    assertEquals(original.get("path"), result.get("path"));
    assertEquals(original.get("url"), result.get("url"));
    assertEquals(original.get("size"), result.get("size"));
    assertTrue(original.containsKey("execution"));
    assertSame(original, ResponseFormatter.project("screenshot", original, true));
  }

  @Test public void failuresAndDegradedObservationsKeepDiagnostics() {
    Kv data = screenshot();
    RespBodyVo failed = RespBodyVo.fail("capture failed");
    failed.setData(data);
    assertSame(data, ResponseFormatter.format(failed,
        """
        {"method":"screenshot","responseMode":"compact"}
        """).getData());
    for (Kv flags : List.of(Kv.by("actionStatus", "unknown"), Kv.by("observationComplete", false),
        Kv.by("fallbackUsed", true), Kv.by("capture_degraded", true))) {
      Kv exceptional = screenshot().set(flags);
      assertSame(exceptional, ResponseFormatter.project("screenshot", exceptional, false));
    }
    Kv pending = screenshot().set("execution", Kv.by("actionStatus", "unknown").set("retrySafe", false));
    Map<?, ?> projected = (Map<?, ?>) ResponseFormatter.project("screenshot", pending, false);
    assertSame(pending.get("execution"), projected.get("execution"));
  }

  @Test public void retriesInlineImagesAndUserPayloadRemainAvailable() {
    Kv data = screenshot().set("retryBudget", Kv.by("retries", 1))
        .set("capture", Kv.by("stage", "complete").set("retries", 1));
    Map<?, ?> result = (Map<?, ?>) ResponseFormatter.project("screenshot", data, false);
    assertSame(data.get("retryBudget"), result.get("retryBudget"));
    assertSame(data.get("capture"), result.get("capture"));
    Kv inline = Kv.by("inline", true).set("base64", "aGVsbG8=");
    assertEquals(inline, ResponseFormatter.project("screenshot", inline, false));
    Kv script = Kv.by("result", data);
    assertSame(data, ((Map<?, ?>) ResponseFormatter.project("execute_js", script, false)).get("result"));
    Kv failedStep = Kv.by("command", "screenshot").set("ok", false).set("data", data);
    Kv batch = Kv.by("results", List.of(failedStep));
    Map<?, ?> projected = (Map<?, ?>) ResponseFormatter.project("commands", batch, false);
    Map<?, ?> step = (Map<?, ?>) ((List<?>) projected.get("results")).get(0);
    assertSame(data, step.get("data"));
  }
}
