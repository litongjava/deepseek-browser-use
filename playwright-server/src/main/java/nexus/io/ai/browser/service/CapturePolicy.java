package nexus.io.ai.browser.service;

import java.util.function.BiFunction;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;

/** Shared automatic/manual capture circuit and bounded diagnostics; never replays page input. */
final class CapturePolicy {
  record Result(byte[] bytes, Kv data) { boolean ok() { return bytes != null; } }

  /**
   * 全页失败后回退截视口时,最少要有多少毫秒才值得再试一次
   *
   * <p>低于这个数就别试了:发出去的请求必然超时,只会让调用方多等一轮。
   */
  private static final long MIN_FALLBACK_MS = 300;

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
    long now = System.currentTimeMillis();
    if (inst.captureCooldownUntil > now && !force) {
      return new Result(null, blocked(inst).set("capture", diagnostic.set("stage", "circuit-open")));
    }
    // 冷却到期就是**半开**:必须在这里清零计数,否则「熔断过一次」之后有效阈值会退化成 1 ——
    // 下一次偶发超时就又把画面关掉 120 秒(实测:一条会话里反复「刚恢复又熔断」)。
    // 只有真的重新累计到 failThreshold 次失败,才值得再熔断。
    if (inst.captureCooldownUntil != 0 && inst.captureCooldownUntil <= now) {
      inst.captureCooldownUntil = 0;
      inst.captureFailures.set(0);
      inst.captureSpuriousFailures.set(0);
      diagnostic.set("halfOpen", true);
    }
    // 显式探测(force)失败时不能**延长**罚时:文档承诺「失败继续冷却」,而旧实现是用
    // captureCooldownMs() 重算,会把伪故障那 15 秒冷却直接放大成 120 秒 —— 探测反而把画面关得更久。
    long cooldownBefore = Math.max(0, inst.captureCooldownUntil - now);
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
          // 全页失败后要给视口回退留时间,所以只给一半预算 —— 但下限不能小于「半预算」,
          // 也不能超过总预算(旧实现是 Math.max(1, remaining/2):预算快用完时会给出 **1 毫秒** 的
          // 截图超时,日志里出现过 `Timeout 1ms exceeded`,那不是回退,是必然失败)。
          double half = remaining / 2;
          double timeout = fallback && actual.equals("fullPage")
              ? Math.min(remaining, Math.max(MIN_FALLBACK_MS, half))
              : remaining;
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
              && budget.remainingMs() >= 2 * MIN_FALLBACK_MS && !Thread.currentThread().isInterrupted()) {
            // 预算不够「再试一次还能有意义」时**不试**:剩下的时间只够发一个必然超时的请求,
            // 白白多等一轮还不如立刻把失败交回去(那 1 毫秒的截图就是这么来的)。
            actual = "viewport";
            continue;
          }
          inst.captureFailureReason = firstFailure;
          int failures = spurious ? inst.captureSpuriousFailures.incrementAndGet() : inst.captureFailures.incrementAndGet();
          if (failures >= PlaywrightService.captureFailThreshold() || force) {
            long cooldown = spurious ? PlaywrightService.captureSpuriousCooldownMs() : PlaywrightService.captureCooldownMs();
            long until = System.currentTimeMillis() + cooldown;
            if (cooldownBefore > 0) {
              // 冷却期内的显式探测失败:**保持原有冷却**(既不延长也不缩短)—— 文档承诺的就是
              // 「失败继续冷却」。旧实现用 captureCooldownMs() 重算,会把伪故障那 15 秒直接放大成 120 秒,
              // 于是「探一次看看恢复没有」反而把画面关得更久。
              until = now + cooldownBefore;
            }
            inst.captureCooldownUntil = until;
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
