import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, readdir, rm, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { BackendManager, keepReleases, pruneReleases, runBackend, validateBackendOptions } from '../dist/backend.js';
import { BrowserClient } from '../dist/client.js';

const execute = promisify(execFile);
const options = { baseUrl: 'http://127.0.0.1:10059', repoDir: resolve('test-backend'), repository: 'gitee', autoUpdate: true, installDependencies: false, startupTimeoutMs: 1000 };
const tick = () => new Promise(r => setTimeout(r, 10));
async function healthyServer(t, healthy = true) {
  const s = createServer((_req, res) => res.end(JSON.stringify({ ok: healthy, data: { name: 'playwright-server' } })));
  s.listen(0, '127.0.0.1'); await once(s, 'listening');
  t.after(() => { s.closeAllConnections(); return new Promise(r => s.close(r)); });
  return `http://127.0.0.1:${s.address().port}`;
}
test('managed endpoints and repository paths must be local and explicit', () => {
  validateBackendOptions(options);
  for (const baseUrl of ['https://127.0.0.1', 'http://example.com', 'http://localhost/api', 'http://localhost?x=1', 'http://user:pass@localhost']) {
    assert.throws(() => validateBackendOptions({ ...options, baseUrl }), /local HTTP/);
  }
  assert.throws(() => validateBackendOptions({ ...options, repoDir: 'relative' }), /absolute/);
});
test('backend operations are single-flight with bounded progress and retained failures', async () => {
  let finish, calls = 0;
  const manager = new BackendManager(options, async (_action, _options, progress) => {
    calls++; progress('x'.repeat(10000)); await new Promise(r => { finish = r; }); throw new Error('dirty checkout');
  });
  assert.equal(manager.begin('update').data.status, 'running');
  manager.begin('start'); await tick();
  assert.equal(calls, 1);
  assert.equal(manager.status().data.logTail.length, 6000);
  finish(); await tick();
  assert.equal(manager.status().ok, false);
  assert.match(manager.status().data.error, /dirty checkout/);
});
test('healthy service is reused without running installers', async t => {
  const baseUrl = await healthyServer(t);
  const manager = new BackendManager({ ...options, baseUrl }, async () => { throw new Error('should not run'); });
  await manager.ensure(new AbortController().signal);
  assert.equal(manager.status().data.status, 'idle');
});
test('failed preparation is not repeatedly triggered by browser calls', async t => {
  const baseUrl = await healthyServer(t, false);
  let calls = 0;
  const manager = new BackendManager({ ...options, baseUrl }, async () => { calls++; throw new Error('build failed'); });
  await assert.rejects(manager.ensure(new AbortController().signal), /build failed/);
  await tick();
  await assert.rejects(manager.ensure(new AbortController().signal), /setup failed/);
  assert.equal(calls, 1);
  manager.begin('start'); await tick(); assert.equal(calls, 2);
});
test('cancelled caller stops waiting but does not cancel an installation shared by other callers', async t => {
  const baseUrl = await healthyServer(t, false);
  let finish;
  const manager = new BackendManager({ ...options, baseUrl }, async () => { await new Promise(r => { finish = r; }); return { ok: true }; });
  manager.begin('start'); await tick();
  const controller = new AbortController();
  const waiting = manager.ensure(controller.signal);
  setTimeout(() => controller.abort(new Error('caller cancelled')), 20);
  await assert.rejects(waiting, /caller cancelled/);
  assert.equal(manager.status().data.status, 'running');
  finish(); await tick(); assert.equal(manager.status().data.status, 'done');
});
test('browser readiness failure occurs before the command is sent', async t => {
  const baseUrl = await healthyServer(t);
  const client = new BrowserClient({ baseUrl, timeoutMs: 1000, maxResponseBytes: 1024, ensureBackend: async () => { throw new Error('dependency missing'); } });
  await assert.rejects(client.command(1, 'click_element_by_index', { index: 0 }, new AbortController().signal), /dependency missing/);
});
test('cleanup and job observation never invoke the backend installer', async t => {
  const baseUrl = await healthyServer(t);
  const client = new BrowserClient({ baseUrl, timeoutMs: 1000, maxResponseBytes: 1024, ensureBackend: async () => { throw new Error('must not resurrect backend'); } });
  for (const method of ['close', 'cancel_job', 'get_job']) {
    const result = await client.command(1, method, {}, new AbortController().signal);
    assert.equal(result.ok, true);
  }
});
test('Windows script parses and status runs without Git, Java installation or checkout mutation', { skip: process.platform !== 'win32' }, async t => {
  const baseUrl = await healthyServer(t);
  const directory = await mkdtemp(join(tmpdir(), 'dsb-backend-test-'));
  t.after(async () => {
    const absolute = resolve(directory);
    assert(absolute.startsWith(resolve(tmpdir()) + '\\'));
    await rm(absolute, { recursive: true, force: true });
  });
  const resultPath = join(directory, 'result.json');
  const configPath = join(directory, 'config.json');
  await writeFile(configPath, JSON.stringify({ ...options, baseUrl, repoDir: join(directory, 'not-cloned'), resultPath }));
  const script = fileURLToPath(new URL('../scripts/backend.ps1', import.meta.url));
  await execute('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script, '-ConfigPath', configPath, '-Action', 'status'], { windowsHide: true, timeout: 20000 });
  const result = JSON.parse((await readFile(resultPath, 'utf8')).replace(/^\uFEFF/, ''));
  assert.equal(result.ok, true);
  assert.equal(result.healthy, true);
  assert.equal(result.owned, false);
  const inspected = await runBackend('status', { ...options, baseUrl, repoDir: join(directory, 'not-cloned') }, () => {});
  assert.equal(inspected.healthy, true);
  assert.equal(inspected.owned, false);
  await assert.rejects(runBackend('restart', { ...options, baseUrl, repoDir: join(directory, 'not-cloned') }, () => {}), /not owned/);
});

test('Windows prepare refuses a dirty checkout before fetching or compiling', { skip: process.platform !== 'win32' }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'dsb-dirty-test-'));
  t.after(async () => {
    const absolute = resolve(directory); assert(absolute.startsWith(resolve(tmpdir()) + '\\'));
    await rm(absolute, { recursive: true, force: true });
  });
  await execute('git', ['init', directory], { windowsHide: true });
  await execute('git', ['-C', directory, 'remote', 'add', 'origin', 'https://gitee.com/ppnt/deepseek-browser-use.git'], { windowsHide: true });
  await writeFile(join(directory, 'keep.txt'), 'local work');
  const baseUrl = await healthyServer(t, false);
  let log = '';
  await assert.rejects(runBackend('prepare', { ...options, baseUrl, repoDir: directory }, text => { log += text; }), /local changes/);
  assert.equal(await readFile(join(directory, 'keep.txt'), 'utf8'), 'local work');
  assert(!log.includes(' fetch origin'));
  assert(!log.includes('clean package'));
});

// ---------- P2-2:release 目录保留策略 ----------

/** 伪造一个 releases 目录:每个 commit 一个子目录,里面一份 backend.jar,mtime 依次递增 */
async function fakeReleases(t, names) {
  const repo = await mkdtemp(join(tmpdir(), 'dsb-releases-'));
  t.after(async () => {
    const absolute = resolve(repo);
    assert(absolute.startsWith(resolve(tmpdir()) + '\\'), '只删临时目录');
    await rm(absolute, { recursive: true, force: true });
  });
  const dir = join(repo, '.dsb-backend', 'releases');
  await mkdir(dir, { recursive: true });
  for (const [position, name] of names.entries()) {
    await mkdir(join(dir, name), { recursive: true });
    const artifact = join(dir, name, 'backend.jar');
    await writeFile(artifact, `jar-${name}`);
    // mtime 必须是可控的:保留策略按「最近」排序,不能靠写入顺序碰运气
    const when = new Date(Date.UTC(2026, 0, 1 + position));
    await utimes(artifact, when, when);
    await utimes(join(dir, name), when, when);
  }
  return { repo, dir };
}
test('release retention keeps the most recent commits and drops older ones', async t => {
  const names = ['aaaaaaaaaaaa', 'bbbbbbbbbbbb', 'cccccccccccc', 'dddddddddddd', 'eeeeeeeeeeee'];
  const { repo, dir } = await fakeReleases(t, names);
  const result = await pruneReleases(repo, 3);
  assert.equal(result.keep, 3);
  assert.equal(result.scanned, 5);
  // 最近三个(按 mtime 递增写入的是 c/d/e)留下,a/b 删掉
  assert.deepEqual(result.deleted.sort(), ['aaaaaaaaaaaa', 'bbbbbbbbbbbb']);
  assert.deepEqual((await readdir(dir)).sort(), ['cccccccccccc', 'dddddddddddd', 'eeeeeeeeeeee']);
  assert.equal(await readFile(join(dir, 'eeeeeeeeeeee', 'backend.jar'), 'utf8'), 'jar-eeeeeeeeeeee');
});
test('release retention never deletes the release the running backend uses', async t => {
  const names = ['aaaaaaaaaaaa', 'bbbbbbbbbbbb', 'cccccccccccc', 'dddddddddddd', 'eeeeeeeeeeee'];
  const { repo, dir } = await fakeReleases(t, names);
  // a 最旧但正是运行中那一份:必须留,并且要从剩下的里补足 keep 个
  const result = await pruneReleases(repo, 3, 'aaaaaaaaaaaa');
  assert.equal(result.active, 'aaaaaaaaaaaa');
  assert(!result.deleted.includes('aaaaaaaaaaaa'));
  assert.deepEqual(result.deleted.sort(), ['bbbbbbbbbbbb', 'cccccccccccc']);
  assert.deepEqual((await readdir(dir)).sort(), ['aaaaaaaaaaaa', 'dddddddddddd', 'eeeeeeeeeeee']);
});
test('release retention is a no-op before the first build and honours the env override', async t => {
  const repo = await mkdtemp(join(tmpdir(), 'dsb-releases-empty-'));
  t.after(async () => {
    const absolute = resolve(repo); assert(absolute.startsWith(resolve(tmpdir()) + '\\'));
    await rm(absolute, { recursive: true, force: true });
  });
  const empty = await pruneReleases(repo, 3);
  assert.equal(empty.scanned, 0);
  assert.deepEqual(empty.deleted, []);
  assert.equal(keepReleases({}), 3);
  assert.equal(keepReleases({ DSB_BACKEND_RELEASES: '5' }), 5);
  assert.equal(keepReleases({ DSB_BACKEND_RELEASES: '0' }), 3, '非法值回退到默认,不能退化成删光');
  assert.equal(keepReleases({ DSB_BACKEND_RELEASES: 'nonsense' }), 3);
});
test('release retention also drops the jar file left inside an old release directory', async t => {
  const { repo, dir } = await fakeReleases(t, ['oldoldoldold', 'newnewnewnew']);
  await writeFile(join(dir, 'oldoldoldold', 'extra.log'), 'stale');
  await pruneReleases(repo, 1);
  assert.deepEqual(await readdir(dir), ['newnewnewnew']);
});
