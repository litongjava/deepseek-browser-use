import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, open, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Envelope, Params } from './client.js';

export type BackendAction = 'status' | 'prepare' | 'start' | 'update' | 'restart';
export interface BackendOptions {
  baseUrl: string; repoDir: string; repository: 'gitee' | 'github';
  autoUpdate: boolean; installDependencies: boolean; startupTimeoutMs: number;
}
export function defaultRepoDir(): string {
  return process.platform === 'win32' && existsSync('D:/')
    ? 'D:/project/project-litongjava/deepseek-browser-use' : join(homedir(), 'project/project-litongjava/deepseek-browser-use');
}
export function validateBackendOptions(options: BackendOptions): void {
  const url = new URL(options.baseUrl);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || url.pathname !== '/' || url.username || url.password || url.search || url.hash) {
    throw new Error('Managed backend requires a local HTTP root URL; disable backendAutoStart for remote services');
  }
  if (!isAbsolute(options.repoDir)) throw new Error('backendRepoDir must be absolute');
}

/** Starts a bounded-output installer process; no shell command interpolation. Logs survive Harness restarts. */
export async function runBackend(action: BackendAction, options: BackendOptions, progress: (text: string) => void): Promise<Params> {
  if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('Automatic backend installation currently supports Windows x64. Install Git, JDK 21 and Maven manually, then use an external baseUrl with backendAutoStart=false.');
  validateBackendOptions(options);
  const logRoot = join(homedir(), '.deepseek-browser-use', 'operations');
  await mkdir(logRoot, { recursive: true });
  const directory = await mkdtemp(join(logRoot, `${action}-`));
  const configPath = join(directory, 'config.json');
  const resultPath = join(directory, 'result.json');
  await writeFile(configPath, JSON.stringify({ ...options, resultPath }));
  progress(`Operation files: ${directory}\n`);
  const executable = join(process.env.SystemRoot ?? 'C:/Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe');
  const script = fileURLToPath(new URL('../scripts/backend.ps1', import.meta.url));
  // File descriptors, not pipes: a detached Windows Java child can retain inherited pipe
  // handles after PowerShell exits, otherwise Node's 'close' event waits for the server forever.
  const log = await open(join(directory, 'operation.log'), 'w+');
  let offset = 0;
  let reading = Promise.resolve();
  const poll = () => {
    reading = reading.then(async () => {
      const { size } = await log.stat();
      if (size <= offset) return;
      const start = Math.max(offset, size - 6000);
      const buffer = Buffer.alloc(size - start);
      const { bytesRead } = await log.read(buffer, 0, buffer.length, start);
      offset = start + bytesRead;
      progress(buffer.subarray(0, bytesRead).toString('utf8'));
    });
    void reading.catch(() => {});
  };
  let code: number | null;
  const timer = setInterval(poll, 500);
  try {
    const child = spawn(executable, ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script, '-ConfigPath', configPath, '-Action', action], {
      windowsHide: true, stdio: ['ignore', log.fd, log.fd], env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    });
    code = await new Promise<number | null>((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
    clearInterval(timer); poll(); await reading;
  } finally { clearInterval(timer); await reading.catch(() => {}); await log.close(); }
  let result: Params;
  try { result = JSON.parse((await readFile(resultPath, 'utf8')).replace(/^\uFEFF/, '')) as Params; }
  catch { throw new Error(`Backend ${action} exited ${code} without a result; inspect ${directory}`); }
  if (code !== 0 || result.ok !== true) throw new Error(`${String(result.error ?? `Backend exited ${code}`)} (logs: ${directory})`);
  return { ...result, operationDir: directory };
}

type Runner = typeof runBackend;
export class BackendManager {
  private pending?: Promise<Params>;
  private operation: Params = { status: 'idle' };
  constructor(readonly options: BackendOptions, private runner: Runner = runBackend) {}

  begin(action: BackendAction): Envelope {
    if (this.pending) return this.status();
    this.operation = { action, status: 'running', startedAt: new Date().toISOString(), logTail: '' };
    const task = Promise.resolve().then(() => this.runner(action, this.options, text => {
      this.operation.logTail = (String(this.operation.logTail) + text).slice(-6000);
    }));
    this.pending = task;
    void task.then(result => { this.operation = { ...this.operation, status: 'done', result }; }, error => {
      this.operation = { ...this.operation, status: 'failed', error: error instanceof Error ? error.message : String(error) };
    }).finally(() => { this.pending = undefined; });
    return this.status();
  }
  status(): Envelope { return { ok: this.operation.status !== 'failed', data: { ...this.operation } }; }

  async ensure(signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    if (this.pending && this.operation.action === 'restart') await this.wait(this.pending, signal);
    // Readiness check is intentionally independent of BrowserClient, avoiding recursion.
    try {
      const response = await fetch(this.options.baseUrl.replace(/\/$/, '') + '/playwright/health', { signal: AbortSignal.any([signal, AbortSignal.timeout(1500)]), redirect: 'error' });
      const result = await response.json() as { ok?: boolean; data?: { name?: string } };
      if (response.ok && result.ok && result.data?.name === 'playwright-server') return;
    } catch { signal.throwIfAborted(); }
    if (!this.pending) {
      // A failed install/build needs an explicit management retry; normal browser calls must not loop installers.
      if (this.operation.status === 'failed') throw new Error(`Backend setup failed: ${String(this.operation.error)}. Inspect dsb_backend status before retrying start.`);
      this.begin('start');
    }
    const pending = this.pending!;
    await this.wait(pending, signal);
    signal.throwIfAborted();
    // A pending update/prepare may have built but not started anything.
    if (this.operation.action !== 'start' && this.operation.action !== 'restart') {
      this.begin('start');
      await this.ensure(signal);
    }
  }

  private async wait(pending: Promise<Params>, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    await new Promise<void>((resolve, reject) => {
      const abort = () => reject(signal.reason);
      signal.addEventListener('abort', abort, { once: true });
      pending.then(() => resolve(), reject).finally(() => signal.removeEventListener('abort', abort));
    });
  }
}
