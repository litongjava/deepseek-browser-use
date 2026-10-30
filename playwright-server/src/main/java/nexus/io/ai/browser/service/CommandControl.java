package nexus.io.ai.browser.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import nexus.io.tio.utils.snowflake.SnowflakeIdUtils;
import com.jfinal.kit.Kv;

/** In-memory command ownership; diagnostics never call the browser event loop. */
public final class CommandControl {
  private static final ThreadLocal<CommandControl> CURRENT = new ThreadLocal<>();
  private static final LinkedHashMap<Long, CommandControl> ACTIVE = new LinkedHashMap<>();
  private static final LinkedHashMap<Long, Kv> LAST = new LinkedHashMap<>();
  private final Long taskId;
  private final String commandId = Long.toString(SnowflakeIdUtils.id());
  private final String method;
  private final long started = System.nanoTime();
  private final List<Future<?>> futures = new ArrayList<>();
  private volatile String stage = "queued";
  private volatile String status = "running";
  private volatile boolean cancelRequested;
  private volatile boolean dispatched;
  private volatile boolean observationCompleted;
  private volatile Boolean actionExecuted = false;
  private int pending;
  private boolean callerDone;

  private CommandControl(Long taskId, String method) {
    this.taskId = taskId;
    this.method = method;
  }

  static synchronized CommandControl claim(Long taskId, String method) {
    if (ACTIVE.containsKey(taskId)) {
      return null;
    }
    CommandControl control = new CommandControl(taskId, method);
    ACTIVE.put(taskId, control);
    return control;
  }

  static CommandControl current() { return CURRENT.get(); }
  static void bind(CommandControl control) {
    if (control == null) { CURRENT.remove(); } else { CURRENT.set(control); }
  }

  static void checkpoint(String stage) {
    CommandControl control = current();
    if (Thread.currentThread().isInterrupted() || (control != null && control.cancelRequested)) {
      throw new CancellationException("Command cancellation requested before " + stage);
    }
    if (control != null) { control.stage = stage; }
  }

  static void dispatch() {
    checkpoint("dispatch");
    CommandControl control = current();
    if (control != null) {
      synchronized (control) {
        if (control.cancelRequested) { throw new CancellationException("Command cancelled before dispatch"); }
        control.dispatched = true;
        control.actionExecuted = null;
      }
    }
  }

  /**
   * Reverts the dispatch mark when the executor reports that it refused the command before any side effect.
   */
  static void notStarted() {
    CommandControl control = current();
    if (control != null) {
      synchronized (control) {
        control.dispatched = false;
        control.actionExecuted = false;
      }
    }
  }

  static void actionCompleted() {
    CommandControl control = current();
    if (control != null) { control.actionExecuted = true; }
    checkpoint("observation");
  }

  synchronized void retain() { pending++; }
  void release() {
    synchronized (this) { pending--; }
    retire();
  }
  void watch(Future<?> future) {
    boolean cancel;
    synchronized (this) {
      futures.add(future);
      cancel = cancelRequested;
    }
    if (cancel) { future.cancel(true); }
  }
  synchronized void unwatch(Future<?> future) { futures.remove(future); }

  void finish(boolean success) {
    synchronized (this) {
      callerDone = true;
      if (!cancelRequested) {
        status = success ? "completed" : "failed";
        observationCompleted = success;
      }
    }
    retire();
  }

  private void retire() {
    synchronized (CommandControl.class) {
      synchronized (this) {
        if (!callerDone || pending != 0) { return; }
        if (cancelRequested) { status = "cancelled"; }
        Kv result = describe();
        LAST.put(taskId, result);
        while (LAST.size() > 100) { LAST.remove(LAST.keySet().iterator().next()); }
        ACTIVE.remove(taskId, this);
      }
    }
  }

  void cancel() {
    List<Future<?>> running;
    synchronized (this) {
      cancelRequested = true;
      status = "cancelling";
      running = new ArrayList<>(futures);
    }
    for (Future<?> future : running) { future.cancel(true); }
  }

  synchronized Kv describe() {
    return Kv.by("commandId", commandId).set("taskId", taskId).set("method", method)
        .set("stage", stage).set("status", status).set("elapsedMs", (System.nanoTime() - started) / 1_000_000)
        .set("dispatchStarted", dispatched).set("actionExecuted", actionExecuted)
        .set("actionStatus", actionExecuted == null ? "unknown" : actionExecuted ? "completed" : "not-started")
        .set("observationCompleted", observationCompleted).set("cancelRequested", cancelRequested)
        .set("cancelled", cancelRequested && pending == 0).set("workerRunning", pending > 0)
        .set("retrySafe", !dispatched).set("note", dispatched && cancelRequested
            ? "Browser work already dispatched may still complete; observe before repeating. Task remains reserved until workers exit."
            : "Cancellation prevents pending dispatch; diagnostics do not access the browser.");
  }

  public static synchronized Kv diagnostics(Long taskId) {
    CommandControl active = ACTIVE.get(taskId);
    return Kv.by("taskId", taskId).set("active", active == null ? null : active.describe()).set("last", LAST.get(taskId));
  }

  public static Kv cancel(Long taskId, String commandId) {
    CommandControl active;
    synchronized (CommandControl.class) { active = ACTIVE.get(taskId); }
    if (active == null || !active.commandId.equals(commandId)) {
      return Kv.by("accepted", false).set("reason", "No matching active command");
    }
    active.cancel();
    return active.describe().set("accepted", true);
  }
}
