import { randomInt } from 'node:crypto';
import { BrowserClient, requireSuccess, type Envelope, type Params } from './client.js';

export interface Owner { ctx: { effect(callback: () => () => Promise<void>, label?: string): () => Promise<void> }; }
interface Entry { id: number; tail: Promise<unknown>; started: boolean; closed: boolean; abort: AbortController; cleanup: () => Promise<void>; jobs: Set<string>; activeJob?: string; batchUnknown?: boolean; }
export interface SessionOptions { browser: string; headless: boolean; }

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
      abort: new AbortController(), cleanup: async () => {}, jobs: new Set() };
    this.entries.set(owner, entry);
    entry.cleanup = owner.ctx.effect(() => async () => {
      entry.closed = true;
      entry.abort.abort(new Error('Browser session disposed'));
      await entry.tail.catch(() => {});
      if (entry.batchUnknown) throw new Error(`Task ${entry.id}: batch submission outcome unknown. Inspect service list_jobs and finish/cancel the batch before manually closing this task. Cleanup did not close potentially active work.`);
      if (entry.started) {
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
      }
      this.entries.delete(owner);
    }, 'deepseek-browser-use.session');
    return entry;
  }

  run(owner: Owner, method: string, params: Params, signal: AbortSignal): Promise<Envelope> {
    signal.throwIfAborted();
    const entry = this.entry(owner);
    const combined = AbortSignal.any([signal, entry.abort.signal]);
    const operation = entry.tail.then(async (): Promise<Envelope> => {
      combined.throwIfAborted();
      if (entry.closed) throw new Error('Browser session is closed');
      if (entry.batchUnknown) throw new Error(`Task ${entry.id}: batch submission outcome unknown; inspect service list_jobs before manually recovering. This Session will not submit more browser actions.`);
      if (method === 'get_job' || method === 'cancel_job') {
        if (typeof params.jobId !== 'string' || !entry.jobs.has(params.jobId)) throw new Error('Job does not belong to this Session');
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
        return { ok: true, data: { id: entry.id, reused: true } };
      }
      let result: Envelope;
      try { result = await this.client.command(entry.id, method, params, combined); }
      catch (error) {
        if (method === 'commands') entry.batchUnknown = true;
        throw error;
      }
      const data = result.data as Params | undefined;
      if (method === 'commands' && result.ok) {
        if (typeof data?.jobId !== 'string') {
          entry.batchUnknown = true;
          throw new Error(`Task ${entry.id}: async batch response missing jobId; inspect service before resubmitting`);
        }
        entry.jobs.add(data.jobId);
        entry.activeJob = data.jobId;
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
