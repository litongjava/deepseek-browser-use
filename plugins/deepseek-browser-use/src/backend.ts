import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, open, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
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
  // 成功过一次 prepare/start/update/restart 之后顺手清理旧产物;status 是只读操作,不碰磁盘。
  // 清理失败不影响操作结果(见 pruneReleases 里的 skipped)。
  let releases: Params | undefined;
  if (action !== 'status') {
    try { releases = await pruneReleases(options.repoDir, keepReleases(), await activeRelease(options.repoDir)); }
    catch (error) { releases = { error: error instanceof Error ? error.message : String(error) }; }
    progress(`release 保留策略: ${JSON.stringify(releases)}\n`);
  }
  return { ...result, operationDir: directory, ...(releases ? { releases } : {}) };
}

type Runner = typeof runBackend;

/** 默认保留多少个 commit 的产物;可以用 DSB_BACKEND_RELEASES 覆盖 */
export const DEFAULT_KEEP_RELEASES = 3;
const RELEASES_ENV = 'DSB_BACKEND_RELEASES';

/** 保留几个 release:环境变量显式给出的正整数优先,否则用默认值 */
export function keepReleases(env: NodeJS.ProcessEnv = process.env): number {
  const configured = Number.parseInt(String(env[RELEASES_ENV] ?? ''), 10);
  return Number.isInteger(configured) && configured >= 1 ? configured : DEFAULT_KEEP_RELEASES;
}

/**
 * release 目录保留策略:只留最近 N 个 commit 的 jar
 *
 * <p>
 * <b>为什么需要它</b>:每次源码更新都会在 `.dsb-backend/releases/&lt;commit&gt;/backend.jar` 下留一份
 * 几十 MB 的产物,长期跑下来这个目录只涨不消。但也不能无脑全删 —— 正在运行的后端可能还占着某一份
 * jar(Windows 上还会锁文件),所以策略是「按 mtime 留最近的 N 个」,并且**从不删 `active` 里那一个**,
 * 即使它的 mtime 因为某种原因最旧。
 *
 * <p>
 * 返回删掉了哪些(以及为什么没删),让调用方能把它写进操作日志 —— 静默删文件是不可接受的。
 */
export async function pruneReleases(repoDir: string, keep: number, active?: string): Promise<Params> {
  const releases = join(repoDir, '.dsb-backend', 'releases');
  let names: string[];
  try {
    const entries = await readdir(releases, { withFileTypes: true });
    names = entries.filter(entry => entry.isDirectory()).map(entry => entry.name);
  } catch {
    // 还没构建过任何 release:不是错误
    return { dir: releases, keep, scanned: 0, deleted: [], skipped: [] };
  }
  const described = await Promise.all(names.map(async name => {
    const directory = join(releases, name);
    try { return { name, directory, mtime: (await stat(join(directory, 'backend.jar'))).mtimeMs }; }
    catch { return { name, directory, mtime: (await stat(directory)).mtimeMs }; }
  }));
  // 新的在前;同 mtime 时按名字定序,避免同一次操作的结果不稳定
  described.sort((left, right) => right.mtime - left.mtime || right.name.localeCompare(left.name));
  const deleted: string[] = [];
  // 运行中那一份**不占保留名额**:它必须活,而且不能因为「占了一个位置」把另一个本来该留的挤掉。
  // 阈值只算一次 —— 两个循环里都去读 skipped.length 的话,第一个循环会把自己的结果算进去(踩过)。
  const activeName = described.some(entry => entry.name === active) ? active : undefined;
  const keepCandidates = Math.max(keep - (activeName ? 1 : 0), 0);
  const skipped: string[] = activeName ? [activeName] : [];
  const candidates = described.filter(entry => entry.name !== activeName);
  for (const entry of candidates.slice(0, keepCandidates)) skipped.push(entry.name);
  for (const entry of candidates.slice(keepCandidates)) {    try {
      await rm(entry.directory, { recursive: true, force: true });
      deleted.push(entry.name);
    } catch (error) {
      // 删不掉多半是正在被运行中的后端占用:记下来,下次操作再试,不能让清理失败毁掉一次成功的构建
      skipped.push(`${entry.name}(${error instanceof Error ? error.message : String(error)})`);
    }
  }
  return { dir: releases, keep, scanned: described.length, active: active ?? null, deleted, skipped };
}

/** 从后端写出的状态文件里读出当前 release(commit 名),用于把「正在跑的那份」排除在清理之外 */
async function activeRelease(repoDir: string): Promise<string | undefined> {
  try {
    const state = JSON.parse(await readFile(join(repoDir, '.dsb-backend', 'state.json'), 'utf8')) as { commit?: string };
    return typeof state.commit === 'string' ? state.commit : undefined;
  } catch { return undefined; }
}

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
