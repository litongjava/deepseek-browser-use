export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Params = { [key: string]: Json };
export interface Envelope extends Params { ok: boolean; }
export interface ClientOptions {
  baseUrl: string;
  timeoutMs: number;
  maxResponseBytes: number;
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

  async request(path: string, signal: AbortSignal, body?: Params): Promise<Envelope> {
    signal.throwIfAborted();
    const combined = AbortSignal.any([signal, AbortSignal.timeout(this.options.timeoutMs)]);
    try {
      const response = await fetch(this.baseUrl + path, {
        method: body ? 'POST' : 'GET', redirect: 'error', signal: combined,
        headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}),
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
