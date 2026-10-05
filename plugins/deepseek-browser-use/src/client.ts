export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Params = { [key: string]: Json };
export interface Envelope extends Params { ok: boolean; }
/** 请求体可以是命令信封,也可以是 `POST /playwright/upload` 那样的裸文件字节 */
export type RequestBody = Params | Uint8Array;
export interface RequestOptions {
  /**
   * 跳过受管后端就绪检查
   *
   * <p>
   * 无 body 的 GET 以前也会走 `ensureBackend`,于是一条只读的 `dsb_health` 能把安装器/编译流程
   * 拉起来 —— 健康检查是「看一眼现状」,不该有副作用。
   */
  skipEnsure?: boolean;
}
export interface ClientOptions {
  baseUrl: string;
  timeoutMs: number;
  maxResponseBytes: number;
  ensureBackend?: (signal: AbortSignal) => Promise<void>;
}

/** No automatic retries: a lost HTTP response does not undo browser input. */
export class BrowserTransportError extends Error {
  readonly outcomeUnknown = true;
  constructor(message: string, options?: ErrorOptions) {
    super(`${message}. Browser operation outcome may be unknown; inspect fresh state before repeating an action.`, options);
    this.name = 'BrowserTransportError';
  }
}

export class BrowserClient {
  readonly baseUrl: string;
  constructor(readonly options: ClientOptions) {
    const url = new URL(options.baseUrl);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error('baseUrl must be an HTTP(S) service URL without credentials, query, or fragment');
    }
    this.baseUrl = url.href.replace(/\/$/, '');
  }

  async request(path: string, signal: AbortSignal, body?: RequestBody, options: RequestOptions = {}): Promise<Envelope> {
    signal.throwIfAborted();
    const method = body && !(body instanceof Uint8Array) ? String(body.method) : undefined;
    // Cleanup and existing job observation must not resurrect a stopped backend.
    if (!options.skipEnsure && !['close', 'cancel_job', 'get_job'].includes(String(method))) {
      await this.options.ensureBackend?.(signal);
    }
    const combined = AbortSignal.any([signal, AbortSignal.timeout(this.options.timeoutMs)]);
    const raw = body instanceof Uint8Array;
    try {
      const response = await fetch(this.baseUrl + path, {
        method: body ? 'POST' : 'GET', redirect: 'error', signal: combined,
        headers: { Accept: 'application/json',
          ...(raw ? { 'Content-Type': 'application/octet-stream' } : body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: raw ? Buffer.from(body as Uint8Array) : JSON.stringify(body) } : {}),
      });
      const reader = response.body?.getReader();
      if (!reader) throw new Error('Empty HTTP response');
      const chunks: Uint8Array[] = [];
      let size = 0;
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          size += value.length;
          if (size > this.options.maxResponseBytes) throw new Error('HTTP response exceeds maxResponseBytes');
          chunks.push(value);
        }
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const envelope: unknown = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope) || typeof (envelope as Envelope).ok !== 'boolean') {
        throw new Error('Invalid browser response envelope (expected ok:boolean)');
      }
      return envelope as Envelope;
    } catch (error) {
      throw new BrowserTransportError(error instanceof Error ? error.message : String(error), { cause: error });
    }
  }

  command(taskId: number, method: string, params: Params, signal: AbortSignal): Promise<Envelope> {
    return this.request('/playwright/command', signal, { id: taskId, method, params, responseMode: 'compact' });
  }
}

export function requireSuccess(result: Envelope): Envelope {
  if (!result.ok) throw new Error(`Browser command failed: ${JSON.stringify(result)}`);
  return result;
}

/** 只增补诊断字段的**类型化**信封合并:直接写 `{...result, data:{...}}` 会被 infer 成带可选 undefined 的新类型 */
export function augment(result: Envelope, extra: Params): Envelope {
  return { ...result, data: { ...((result.data && typeof result.data === 'object' && !Array.isArray(result.data) ? result.data : {}) as Params), ...extra } };
}
