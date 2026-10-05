package nexus.io.ai.browser.service;

import java.util.function.BiFunction;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;

/** Shared automatic/manual capture circuit and bounded diagnostics; never replays page input. */
final class CapturePolicy {
  record Result(byte[] bytes, Kv data) { boolean ok() { return bytes != null; } }

  static Kv blocked(BrowserInstance inst) {
    long remaining = Math.max(0, inst.captureCooldownUntil - System.currentTimeMillis());
    return Kv.by("capture_degraded", true).set("errorCode", "CAPTURE_CIRCUIT_OPEN").set("retryAfterMs", remaining)
        .set("capture_note", "截图不可用(熔断中)。这次没有画面留档，不能只凭文本断言页面正常；"
            + "可继续文本取证，需要视觉确认时使用 request_human_input，或明确 force=true 探测一次。")
        .set("screenshot_error", inst.captureFailureReason);
  }

  static Result run(BrowserInstance inst, String mode, boolean force, boolean fallback,
      Integer timeoutMs, BiFunction<String, Double, byte[]> shot) {
    int limit = timeoutMs == null ? (int) PlaywrightService.captureTimeoutMs() : timeoutMs;
    if (limit < 1 || limit > 120000) throw new IllegalArgumentException("timeoutMs must be between 1 and 120000");
    Kv diagnostic = Kv.by("requestedMode", mode).set("actualMode", mode).set("attempts", 0)
        .set("timeoutMs", limit).set("stage", "preflight").set("elapsedMs", 0L);
    if (!force && inst.captureCooldownUntil > System.currentTimeMillis()) {
      return new Result(null, blocked(inst).set("capture", diagnostic.set("stage", "circuit-open")));
    }
    try (RetryBudget budget = RetryBudget.open(limit)) {
      int attempts = 0;
      String actual = mode;
      String firstFailure = null;
      for (;;) {
        try {
          long remaining = budget.remainingMs();
          if (remaining <= 0) throw new PlaywrightException("Screenshot total budget exhausted");
          diagnostic.set("stage", "screenshot").set("attempts", ++attempts).set("actualMode", actual);
          // Reserve time for an explicitly requested viewport fallback; never grant a fresh timeout.
          double timeout = fallback && actual.equals("fullPage") ? Math.max(1, remaining / 2) : remaining;
          byte[] bytes = shot.apply(actual, timeout);
          inst.captureFailures.set(0); inst.captureSpuriousFailures.set(0);
          inst.captureCooldownUntil = 0; inst.captureFailureReason = null;
          diagnostic.set("stage", "complete").set("elapsedMs", budget.elapsedMs()).set("retries", budget.retries());
          Kv data = Kv.by("capture", diagnostic).set("capture_degraded", false);
          if (!actual.equals(mode)) data.set("fullPageCaptured", false).set("fallbackUsed", true)
              .set("warning", "全页截图失败，仅取得视口截图；不可用作完整页面证据。").set("fallbackReason", firstFailure);
          else if (mode.equals("fullPage")) data.set("fullPageCaptured", true);
          return new Result(bytes, data);
        } catch (PlaywrightException error) {
          String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
          if (reason.length() > 500) reason = reason.substring(0, 500);
          if (firstFailure == null) firstFailure = reason;
          boolean spurious = ActionError.isSpuriousDispatch(reason);
          if (spurious && !force && budget.retry()) {
            try { Thread.sleep(120); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (!Thread.currentThread().isInterrupted()) continue;
          }
          if (!spurious && fallback && actual.equals("fullPage") && !force
              && budget.remainingMs() > 0 && !Thread.currentThread().isInterrupted()) {
            actual = "viewport";
            continue;
          }
          inst.captureFailureReason = firstFailure;
          int failures = spurious ? inst.captureSpuriousFailures.incrementAndGet() : inst.captureFailures.incrementAndGet();
          if (failures >= PlaywrightService.captureFailThreshold() || force) {
            inst.captureCooldownUntil = System.currentTimeMillis() + (spurious ? 15000 : PlaywrightService.captureCooldownMs());
          }
          diagnostic.set("elapsedMs", budget.elapsedMs()).set("retries", budget.retries())
              .set("errorCode", spurious ? ActionError.SPURIOUS_DISPATCH : "CAPTURE_FAILED");
          Kv data = Kv.by("screenshot_error", reason).set("errorCode", spurious ? ActionError.SPURIOUS_DISPATCH : "CAPTURE_FAILED").set("capture", diagnostic);
          if (inst.captureCooldownUntil > System.currentTimeMillis()) data.set(blocked(inst));
          return new Result(null, data);
        }
      }
    }
  }
}
