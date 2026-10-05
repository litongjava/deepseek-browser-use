package nexus.io.ai.browser.service;

import java.util.function.LongSupplier;

/** One retry allowance across command and nested Playwright helpers. No action is made retry-safe here. */
final class RetryBudget implements AutoCloseable {
  private static final ThreadLocal<RetryBudget> CURRENT = new ThreadLocal<>();
  private final RetryBudget previous;
  private final LongSupplier nanoTime;
  private final long started;
  private final long deadline;
  private int retries;

  private RetryBudget(long timeoutMs, LongSupplier nanoTime) {
    this.previous = CURRENT.get();
    this.nanoTime = nanoTime;
    this.started = nanoTime.getAsLong();
    this.deadline = started + Math.max(1, timeoutMs) * 1_000_000L;
    CURRENT.set(this);
  }

  static RetryBudget open(long timeoutMs) { return open(timeoutMs, System::nanoTime); }
  static RetryBudget open(long timeoutMs, LongSupplier clock) { return new RetryBudget(timeoutMs, clock); }
  static RetryBudget current() { return CURRENT.get(); }
  long remainingMs() {
    long nanos = deadline - nanoTime.getAsLong();
    long remaining = nanos <= 0 ? 0 : (nanos + 999_999L) / 1_000_000L;
    return previous == null ? remaining : Math.min(remaining, previous.remainingMs());
  }
  long elapsedMs() { return Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000L); }
  int retries() { return previous == null ? retries : previous.retries(); }
  boolean retry() {
    if (Thread.currentThread().isInterrupted() || remainingMs() <= 120) return false;
    if (previous != null) return previous.retry();
    if (retries >= 2) return false;
    retries++;
    return true;
  }
  @Override public void close() {
    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
  }
}
