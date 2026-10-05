import { randomInt } from 'node:crypto';
import { BrowserClient, requireSuccess, type Envelope, type Params, type RequestBody } from './client.js';

export interface Owner { ctx: { effect(callback: () => () => Promise<void>, label?: string): () => Promise<void> }; }
export interface RunOptions {
  /**
   * 直接给出的 HTTP 请求体
   *
   * <p>
   * 给 `POST /playwright/upload` 这类**不是**命令信封的端点用:它们要的是裸文件字节,但仍必须
   * 排在同一条会话队列上,否则上传会与这个任务的浏览器动作并发。
   */
  body?: RequestBody;
  /** 这条请求不是任务命令(如上传暂存),跳过作业归属与 activeJob 拦截 */
  auxiliary?: boolean;
}
interface CaptureState { until: number; reason?: string; }
interface Entry {
  id: number; tail: Promise<unknown>; started: boolean; closed: boolean; abort: AbortController;
  cleanup: () => Promise<void>; jobs: Set<string>; activeJob?: string; batchUnknown?: boolean;
  capture?: CaptureState;
  /** 连续多少次回执说截图不可用(含插件自己短路返回的 CAPTURE_CIRCUIT_OPEN) */
  captureDegradedStreak: number;
}
export interface SessionOptions { browser: string; headless: boolean; }

/** 服务端换了进程(或任务被别的进程收走)时,实例查不到;这个消息是「自愈」的唯一判据 */
export function instanceMissing(result: Envelope): boolean {
  if (result.ok) return false;
  const text = `${String(result.msg ?? '')}${JSON.stringify(result.data ?? '')}`;
  return text.includes('没有找到对应的浏览器实例') || text.includes('本服务进程启动于');
}

function readCapture(entry: Entry, result: Envelope): void {
  const data = (result.data && typeof result.data === 'object' && !Array.isArray(result.data) ? result.data : undefined) as Params | undefined;
  if (data?.capture_degraded === true && typeof data.retryAfterMs === 'number' && Number.isFinite(data.retryAfterMs)) {
    // 熔断期由回执决定,插件不自己推算冷却时间;上限一小时只为挡住异常大的值
    entry.capture = { until: Date.now() + Math.min(Math.max(data.retryAfterMs, 0), 3600000),
      ...(typeof data.capture_note === 'string' ? { reason: data.capture_note } : {}) };
    entry.captureDegradedStreak += 1;
    return;
  }
  if (data?.capture_degraded === false) {
    entry.capture = undefined;
    entry.captureDegradedStreak = 0;
  }
}

/** Live owner identity, not a model-supplied task ID, controls resource access. */
export class BrowserSessions {
  private entries = new Map<Owner, Entry>();
  private disposed = false;
  constructor(readonly client: BrowserClient, readonly options: SessionOptions) {}

  private entry(owner: Owner): Entry {
    if (this.disposed) throw new Error('Browser plugin is disposed');
    const previous = this.entries.get(owner);
    if (previous) return previous;
    // 48 random bits: exact JSON/JavaScript integer, unlike server Snowflake IDs.
    const entry: Entry = { id: randomInt(1, 2 ** 48 - 1), tail: Promise.resolve(), started: false, closed: false,
      abort: new AbortController(), cleanup: async () => {}, jobs: new Set(), captureDegradedStreak: 0 };
    this.entries.set(owner, entry);
    entry.cleanup = owner.ctx.effect(() => async () => {
      entry.closed = true;
      entry.abort.abort(new Error('Browser session disposed'));
      await entry.tail.catch(() => {});
      // 从来没 start 成功的会话:服务端没有任何属于它的活,既不报「结果不明」也不必去关
      if (!entry.started) { this.entries.delete(owner); return; }
      if (entry.batchUnknown) throw new Error(`Task ${entry.id}: batch submission outcome unknown. Inspect service list_jobs and finish/cancel the batch before manually closing this task. Cleanup did not close potentially active work.`);
      const signal = AbortSignal.timeout(this.client.options.timeoutMs);
      if (entry.activeJob) {
        requireSuccess(await this.client.command(entry.id, 'cancel_job', { jobId: entry.activeJob }, signal));
        for (;;) {
          const status = requireSuccess(await this.client.command(entry.id, 'get_job', { jobId: entry.activeJob, includeResult: false }, signal));
          if ((status.data as Params)?.status !== 'running') break;
          await new Promise(resolve => setTimeout(resolve, 100));
          signal.throwIfAborted();
        }
      }
      requireSuccess(await this.client.command(entry.id, 'close', {}, signal));
      this.entries.delete(owner);
    }, 'deepseek-browser-use.session');
    return entry;
  }

  /** 只读自查:熔断期与「批次提交结果不明」这类状态必须先让工具看见,否则模型只能反复盲试 */
  inspect(owner: Owner): Envelope {
    const entry = this.entry(owner);
    const capture = entry.capture && entry.capture.until > Date.now() ? entry.capture : undefined;
    return { ok: true, data: { id: entry.id, started: entry.started, batchUnknown: entry.batchUnknown === true,
      activeJob: entry.activeJob ?? null, captureDegradedStreak: entry.captureDegradedStreak,
      capture: capture ? { until: capture.until, retryAfterMs: capture.until - Date.now(), ...(capture.reason ? { reason: capture.reason } : {}) } : null } };
  }

  /**
   * 解除「批次提交结果不明」冻结
   *
   * <p>
   * 只由 **dsb_job({browserId, clearUnknown:true})** 在确认本任务所有作业都已到终态之后调用。
   * 不在这里自动判断:提交响应丢了就再也拿不到那次提交的 jobId,不可能诚实地把某个列出来的作业
   * 认成"就是我那一次",所以解冻必须是一次显式的、人/模型可见的决定。
   */
  clearBatchUnknown(owner: Owner): boolean {
    const entry = this.entries.get(owner);
    if (!entry || !entry.batchUnknown) return false;
    entry.batchUnknown = false;
    entry.activeJob = undefined;
    return true;
  }

  /** 截图熔断期内的任务:用于 dsb_screenshot 短路,不发 HTTP */
  captureBlock(owner: Owner): { retryAfterMs: number; reason?: string } | undefined {
    const entry = this.entry(owner);
    if (!entry.capture || entry.capture.until <= Date.now()) return undefined;
    return { retryAfterMs: entry.capture.until - Date.now(), ...(entry.capture.reason ? { reason: entry.capture.reason } : {}) };
  }

  /**
   * 记录一次「插件自己短路掉的截图」
   *
   * <p>
   * 短路也要计入连续降级次数:模型连撞三次熔断和使用 force 连失败三次是同一件事 —— 这个任务
   * 已经拿不到画面了,该换任务而不是继续在这里试。
   */
  noteCaptureBlocked(owner: Owner, retryAfterMs: number, reason?: string): number {
    const entry = this.entry(owner);
    entry.capture = { until: Date.now() + retryAfterMs, ...(reason ? { reason } : {}) };
    entry.captureDegradedStreak += 1;
    return entry.captureDegradedStreak;
  }

  run(owner: Owner, method: string, params: Params, signal: AbortSignal, options: RunOptions = {}): Promise<Envelope> {
    signal.throwIfAborted();
    const entry = this.entry(owner);
    const combined = AbortSignal.any([signal, entry.abort.signal]);
    const operation = entry.tail.then(async (): Promise<Envelope> => {
      combined.throwIfAborted();
      if (entry.closed) throw new Error('Browser session is closed');
      if (options.body && options.auxiliary) {
        // 辅助端点(上传暂存):不启动任务、不排队等作业,只借用同一条串行队列
        return this.client.request(method, combined, options.body);
      }
      if (entry.batchUnknown && !['get_job', 'cancel_job', 'list_jobs'].includes(method)) {
        // 只剩一条只读出路:用 dsb_job({browserId}) 把本任务的作业列出来,再 get/cancel。
        // 以前这里是个死胡同:任何命令都被挡,包括「看看那批活到底跑成什么样」。
        throw new Error(`Task ${entry.id}: batch submission outcome unknown; this Session will not submit more browser actions. `
          + `Recover with the restricted read-only path: dsb_job({browserId:${entry.id}}) to list this task's jobs, then `
          + `dsb_job({jobId, cancel:true}) to stop it; once the job reaches a terminal status the Session unfreezes.`);
      }
      if (method === 'get_job' || method === 'cancel_job') {
        if (typeof params.jobId !== 'string' || !entry.jobs.has(params.jobId)) throw new Error('Job does not belong to this Session');
      } else if (method === 'list_jobs') {
        // 受限自查:仍只读,不接受任何新的浏览器动作
      } else if (entry.activeJob) {
        throw new Error(`Browser batch ${entry.activeJob} is pending. Use dsb_job until it finishes before issuing other commands.`);
      }
      if (method === 'close' && !entry.started) return { ok: true, data: { id: entry.id, closed: true } };
      if (!entry.started) {
        // Remember ownership before HTTP, so timeout during start is still cleaned up.
        entry.started = true;
        const started = await this.client.command(entry.id, 'start', { ...this.options }, combined);
        if (!started.ok) entry.started = false;
        requireSuccess(started);
        if (method === 'start') return started;
      } else if (method === 'start') {
        // 复用之前先做一次便宜的活性校验:服务端换过进程时,这个 id 其实已经不存在了,
        // 直接回 reused:true 会让后续每条命令都失败一次才轮到模型自己发现,并误以为「刚启动过」。
        const alive = await this.client.command(entry.id, 'get_url', {}, combined);
        if (alive.ok) return { ok: true, data: { id: entry.id, reused: true, url: (alive.data as Params | undefined)?.url ?? null } };
        if (!instanceMissing(alive)) return alive;
        entry.started = false;
        entry.activeJob = undefined;
        entry.capture = undefined;
        const restarted = await this.client.command(entry.id, 'start', { ...this.options }, combined);
        if (!restarted.ok) { entry.started = false; return restarted; }
        entry.started = true;
        return { ...restarted, data: { ...(restarted.data as Params ?? {}), recoveredFromMissingInstance: true } };
      }
      let result: Envelope;
      try { result = await this.client.command(entry.id, method, params, combined); }
      catch (error) {
        if (method === 'commands') entry.batchUnknown = true;
        throw error;
      }
      readCapture(entry, result);
      const data = result.data as Params | undefined;
      if (method === 'list_jobs' && result.ok && entry.batchUnknown) {
        // 结果不明时唯一的自救路径:只把**本任务**(browserId 与 entry.id 相同)的作业登记进来,
        // 之后才允许对它 get/cancel。别的任务的作业一律不认,免得这条只读通道变成跨会话入口。
        const jobs = Array.isArray(data?.jobs) ? data!.jobs as Params[] : [];
        for (const job of jobs) {
          if (typeof job.jobId === 'string' && Number(job.browserId) === entry.id) entry.jobs.add(job.jobId);
        }
      }
      if (method === 'commands' && result.ok) {
        if (typeof data?.jobId !== 'string') {
          entry.batchUnknown = true;
          throw new Error(`Task ${entry.id}: async batch response missing jobId; inspect service before resubmitting. `
            + `Recover with dsb_job({browserId:${entry.id}}) then dsb_job({jobId, cancel:true}) instead of resubmitting the batch.`);
        }
        entry.jobs.add(data.jobId);
        entry.activeJob = data.jobId;
      }
      if (method === 'cancel_job' && result.ok && typeof params.jobId === 'string' && params.jobId === entry.activeJob) {
        // 取消成功后必须立刻放开这个会话:否则整会话被 "Browser batch … is pending" 挡住,
        // 而模型已经取消过了,只能靠再查一次 dsb_job 才解得开 —— 实测踩过。
        entry.activeJob = undefined;
      }
      if (method === 'get_job' && result.ok && data && ['done', 'failed', 'cancelled'].includes(String(data.status)) && params.jobId === entry.activeJob) {
        entry.activeJob = undefined;
      }
      if (method === 'close' && result.ok) entry.started = false;
      return result;
    });
    entry.tail = operation.catch(() => {});
    return operation;
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    const results = await Promise.allSettled([...this.entries.values()].map(entry => entry.cleanup()));
    const errors = results.filter(r => r.status === 'rejected').map(r => r.reason);
    if (errors.length) throw new AggregateError(errors, 'Browser session cleanup failed');
  }
}
