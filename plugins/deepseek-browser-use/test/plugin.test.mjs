import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BrowserClient, BrowserTransportError } from '../dist/client.js';
import { BrowserSessions } from '../dist/sessions.js';
import { createTools } from '../dist/tools.js';
import { commandNames } from '../dist/commands.js';
import { classify, recoveryProbe, wrapScript } from '../dist/script.js';
import { renderEnvelope } from '../dist/render.js';
import { Config, apply } from '../dist/index.js';
import { Context } from '@deepseek-ai/cordis';
import { ToolRuntime, renderToolsSdk } from '@deepseek-ai/dsh-tools';

const signal = () => new AbortController().signal;
function owner() {
  const cleanups = [];
  return { id: Math.random(), ctx: { effect(fn) { const clean = fn(); cleanups.push(clean); return clean; } }, cleanups };
}
async function server(t, handler) {
  const calls = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const bytes = chunks.length ? Buffer.concat(chunks) : undefined;
    // 上传暂存要的是裸字节,命令信封才是 JSON:两者都要能在断言里读到原始形态
    let body = bytes;
    if (bytes && (req.headers['content-type'] ?? '').includes('application/json')) body = JSON.parse(bytes.toString());
    calls.push({ path: req.url, body, bytes, contentType: req.headers['content-type'] });
    const result = await handler(body, req, res);
    if (!res.writableEnded) res.end(JSON.stringify(result ?? { ok: true, data: {} }));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  const client = new BrowserClient({ baseUrl: `http://127.0.0.1:${server.address().port}`, timeoutMs: 2000, maxResponseBytes: 1e6 });
  return { client, calls };
}
function fixture(client) {
  const a = owner(); const b = owner();
  const sessions = new BrowserSessions(client, { browser: 'chrome', headless: true });
  const ctx = { agents: { get(id) { return [a, b].find(x => x.id === id); } }, get() {} };
  const tools = createTools(ctx, client, sessions, { maxTextChars: 1000, maxUploadBytes: 1024 });
  const call = (name, args = {}, agent = a, s = signal()) => tools.find(t => t.name === `dsb_${name}`).execute(args, { agent, signal: s });
  return { a, b, ctx, sessions, tools, call };
}

test('HTTP preserves Unicode, compact envelope and business failure; no implicit retry', async t => {
  const { client, calls } = await server(t, body => ({ ok: false, msg: '业务失败', data: body.params }));
  const result = await client.command(101, 'input_text', { text: '中文 "引号"\n&%`$' }, signal());
  assert.equal(result.ok, false);
  assert.equal(result.data.text, '中文 "引号"\n&%`$');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.responseMode, 'compact');
});
test('malformed, non-2xx, redirects and over-limit responses fail as uncertain transport', async t => {
  for (const kind of ['malformed', 'http', 'redirect', 'large']) await t.test(kind, async t => {
    const { client, calls } = await server(t, (_body, _req, res) => {
      if (kind === 'http') res.statusCode = 503;
      if (kind === 'redirect') { res.statusCode = 302; res.setHeader('Location', '/elsewhere'); }
      if (kind === 'malformed') res.end('not-json');
      if (kind === 'large') res.end('x'.repeat(2048));
      return { ok: true };
    });
    client.options.maxResponseBytes = 1024;
    await assert.rejects(client.command(101, 'click_element_by_index', { index: 0 }, signal()), BrowserTransportError);
    assert.equal(calls.length, 1);
  });
});
test('deadline and caller cancellation do not retry browser actions', async t => {
  const { client, calls } = await server(t, async () => { await new Promise(r => setTimeout(r, 100)); return { ok: true }; });
  client.options.timeoutMs = 20;
  await assert.rejects(client.command(101, 'click_element_by_index', { index: 0 }, signal()), /outcome may be unknown/);
  assert.equal(calls.length, 1);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(client.command(101, 'click_element_by_index', {}, controller.signal));
  assert.equal(calls.length, 1);
});
test('one lazy start per owner, serial operations, independent task IDs and scoped cleanup', async t => {
  let active = 0, max = 0;
  const { client, calls } = await server(t, async body => {
    if (body.method === 'execute_js') { active++; max = Math.max(max, active); await new Promise(r => setTimeout(r, 20)); active--; }
    return { ok: true, data: { id: body.id } };
  });
  const f = fixture(client);
  await Promise.all([f.call('evaluate', { body: '1' }), f.call('evaluate', { body: '2' })]);
  assert.equal(max, 1);
  assert.equal(calls.filter(c => c.body.method === 'start').length, 1);
  await f.call('state', {}, f.b);
  const ids = calls.filter(c => c.body.method === 'start').map(c => c.body.id);
  assert.notEqual(ids[0], ids[1]);
  assert(ids.every(Number.isSafeInteger));
  await f.sessions.dispose();
  assert.deepEqual(calls.filter(c => c.body.method === 'close').map(c => c.body.id).sort(), ids.sort());
  assert(!calls.some(c => c.body.method === 'shutdown'));
  await assert.rejects(f.call('state'), /disposed/);
});
test('queued cancellation prevents action and does not poison the following call', async t => {
  const { client, calls } = await server(t, async body => {
    if (body.method === 'execute_js') await new Promise(r => setTimeout(r, 40));
    return { ok: true };
  });
  const f = fixture(client);
  await f.call('start');
  const first = f.call('evaluate', { body: '1' });
  const ctrl = new AbortController();
  const second = f.call('click', { index: 1 }, f.a, ctrl.signal);
  ctrl.abort();
  await assert.rejects(second);
  await first;
  await f.call('state');
  assert(!calls.some(c => c.body.method === 'click_element_by_index'));
  await f.sessions.dispose();
});
test('tool validation blocks task injection, global commands, aliases and nested batch bypasses', async t => {
  const { client, calls } = await server(t, () => ({ ok: true }));
  const f = fixture(client);
  for (const method of ['shutdown', 'shut_down', 'cleanup', 'commands', 'run_recipe', 'get_job', 'start', 'close']) {
    await assert.rejects(f.call('command', { method, params: {} }), /not an exposed/);
    await assert.rejects(f.call('batch', { commands: [{ method, params: {} }] }), /not an exposed/);
  }
  await assert.rejects(f.call('command', { method: 'get_url', id: 888 }));
  await assert.rejects(f.call('click', { index: 0, selector: 'button' }), /exactly one/);
  await assert.rejects(f.call('click', { index: 0, frame: '1' }), /require selector/);
  await assert.rejects(f.call('state', {}, owner()), /exact live/);
  assert.equal(calls.length, 0);
});
test('async jobs retain session ownership and prevent interleaved actions until terminal observation', async t => {
  const { client, calls } = await server(t, body => {
    if (body.method === 'commands') return { ok: true, data: { jobId: 'job-1', status: 'running' } };
    if (body.method === 'get_job') return { ok: true, data: { jobId: 'job-1', status: 'done', ok: false, data: { results: [{ error: 'failure preserved' }] } } };
    return { ok: true };
  });
  const f = fixture(client);
  const batch = await f.call('batch', { commands: [{ method: 'get_url' }] });
  assert.equal(batch.data.jobId, 'job-1');
  await assert.rejects(f.call('click', { index: 0 }), /pending/);
  await assert.rejects(f.call('job', { jobId: 'job-1' }, f.b), /does not belong/);
  const result = await f.call('job', { jobId: 'job-1' });
  assert.equal(result.data.ok, false);
  await f.call('state');
  assert.deepEqual(calls.find(c => c.body.method === 'commands').body.params.commands, [{ get_url: {} }]);
  await f.sessions.dispose();
});
test('active jobs are cancelled and drained before session close', async t => {
  const { client, calls } = await server(t, body => {
    if (body.method === 'commands') return { ok: true, data: { jobId: 'job-1' } };
    if (body.method === 'get_job') return { ok: true, data: { status: 'cancelled' } };
    return { ok: true };
  });
  const f = fixture(client);
  await f.call('batch', { commands: [{ method: 'get_title' }] });
  await f.sessions.dispose();
  assert.deepEqual(calls.slice(-3).map(c => c.body.method), ['cancel_job', 'get_job', 'close']);
});
test('lost async submission quarantines the owner instead of repeating or closing active work', async t => {
  const { client, calls } = await server(t, (body, _req, res) => {
    if (body.method === 'commands') res.end('truncated-response');
    return { ok: true };
  });
  const f = fixture(client);
  await assert.rejects(f.call('batch', { commands: [{ method: 'click_element_by_index', params: { index: 0 } }] }), /outcome may be unknown/);
  await assert.rejects(f.call('click', { index: 0 }), /submission outcome unknown/);
  await assert.rejects(f.sessions.dispose(), /cleanup failed/);
  assert.deepEqual(calls.map(c => c.body.method), ['start', 'commands']);
});
test('file upload stages bytes through /playwright/upload and hands over the service path', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsb-plugin-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, '中文.txt'); await writeFile(path, '文件内容');
  const { client, calls } = await server(t, body => body?.method === 'upload_file'
    ? { ok: true, data: { uploaded: true } }
    : { ok: true, data: { path: 'C:/svc/upload/中文.txt', relativePath: '中文.txt', sha256: 'abc' } });
  const f = fixture(client);
  const result = await f.call('upload', { selector: 'input[type=file]', localPath: path });
  const staged = calls.find(c => c.path?.startsWith('/playwright/upload'));
  assert.ok(staged, 'must use the staging endpoint');
  assert.equal(staged.body.toString(), '文件内容');
  assert.equal(staged.contentType, 'application/octet-stream');
  assert.match(decodeURIComponent(staged.path), /filename=中文\.txt/);
  const args = calls.find(c => c.body?.method === 'upload_file').body.params;
  assert.equal(args.filename, undefined);
  assert.equal(args.contentBase64, undefined);
  assert.equal(args.path, 'C:/svc/upload/中文.txt');
  assert.equal(result.data.staged, true);
  await writeFile(path, 'x'.repeat(1025));
  await assert.rejects(f.call('upload', { index: 0, localPath: path }), /maxUploadBytes/);
  await f.sessions.dispose();
});
test('upload falls back to inline base64 when the service has no staging endpoint', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsb-plugin-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, 'fallback.bin'); await writeFile(path, 'payload');
  const { client, calls } = await server(t, (body, req, res) => {
    // 老服务没有 /playwright/upload:这个路径根本不存在(真服务是 404,不是 ok:false)
    if (req.url.startsWith('/playwright/upload')) { res.statusCode = 404; res.end('not found'); return { ok: false }; }
    return { ok: true };
  });
  const f = fixture(client);
  const result = await f.call('upload', { index: 0, localPath: path });
  const args = calls.find(c => c.body?.method === 'upload_file').body.params;
  assert.equal(Buffer.from(args.contentBase64, 'base64').toString(), 'payload');
  assert.equal(result.data.staged, false);
  assert.match(String(result.data.stagingError), /404/);
  await f.sessions.dispose();
});
test('image mode requires model capability and stores image bytes outside canonical JSON', async t => {
  const { client } = await server(t, body => ({ ok: true, data: body.method === 'screenshot' ? { base64: 'aW1hZ2U=', size: 5 } : {} }));
  const f = fixture(client);
  await assert.rejects(f.call('screenshot', { view: true }), /attachment service/);
  f.a.options = { provider: 'p', model: 'm' }; f.a.session = { requestHeader() {} };
  const ref = { id: 'image-id', mediaType: 'image/png', width: 1, height: 1, byteSize: 5 };
  f.ctx.get = name => name === 'attachments' ? { async saveImage(input) { assert.equal(Buffer.from(input.data).toString(), 'image'); return ref; } }
    : { async resolveModelInfo() { return { inputModalities: ['image'] }; } };
  const result = await f.call('screenshot', { view: true });
  assert.equal(result.data.base64, undefined);
  assert.deepEqual(result.attachment, ref);
  const rendered = f.tools.find(t => t.name === 'dsb_screenshot').output.render({}, result);
  assert.equal(rendered[1].type, 'image');
  await f.sessions.dispose();
});

test('render keeps envelope diagnostics and gives each big text its own budget in valid JSON', async t => {
  const { client } = await server(t, () => ({ ok: true, data: { text: 'x'.repeat(4000) } }));
  const f = fixture(client);
  const result = await f.call('state');
  const rendered = f.tools.find(t => t.name === 'dsb_state').output.render({}, result);
  assert.equal(result.data.text.length, 4000, 'canonical value stays complete for programmatic access');
  const projected = JSON.parse(rendered[0].text);
  // P0-1:以前是 JSON.stringify(result).slice(...) —— 信封里 data 在前,这些关键字段会被整段切掉
  assert.equal(projected.ok, true);
  assert.equal(projected.data.textTruncated, true);
  assert.equal(projected.data.textChars, 4000);
  assert.equal(projected.data.text.length, 1000);
  assert.equal(projected.code, undefined);
  assert.ok(!rendered[0].text.includes('Display truncated'), 'no marker may be appended outside the JSON');
  await f.sessions.dispose();
});
test('render always prints small fields even when data.text dwarfs the budget', async t => {
  // 复现「半截 JSON」:完全照 get_browser_state 的字段顺序,data 在前、msg/code 在后
  const envelope = { ok: false, data: { text: 'y'.repeat(5000), state_file: 'data/7/12.txt', indicesUsable: true,
    snapshotConsistent: false, snapshotIssues: ['tab detached'], capture_degraded: true, capture_note: '熔断中', retryAfterMs: 15000,
    retryBudget: { attempts: 1, commandAttempts: 3 }, elementsTruncated: true, pageAppearsBlank: false },
  code: 1, msg: '业务失败', errorCode: 'CAPTURE_CIRCUIT_OPEN', warning: '视口图', screenshot: '/data/7/12.png' };
  const { projected } = renderEnvelope(envelope, { maxChars: 100 });
  assert.equal(projected.ok, false);
  assert.equal(projected.code, 1);
  assert.equal(projected.msg, '业务失败');
  assert.equal(projected.errorCode, 'CAPTURE_CIRCUIT_OPEN');
  assert.equal(projected.warning, '视口图');
  assert.equal(projected.data.state_file, 'data/7/12.txt');
  assert.equal(projected.data.indicesUsable, true);
  assert.equal(projected.data.snapshotConsistent, false);
  assert.deepEqual(projected.data.snapshotIssues, ['tab detached']);
  assert.equal(projected.data.capture_degraded, true);
  assert.equal(projected.data.capture_note, '熔断中');
  assert.equal(projected.data.retryAfterMs, 15000);
  assert.deepEqual(projected.data.retryBudget, { attempts: 1, commandAttempts: 3 });
  assert.equal(projected.data.textTruncated, true);
  assert.equal(projected.data.textChars, 5000);
  assert.equal(projected.data.text.length, 100);
  assert.equal(projected.screenshot, '/data/7/12.png');
  assert.ok(Array.isArray(projected.notes) && projected.notes.some(note => note.includes('text')));
});
test('render omits oversized fields by name instead of cutting the JSON tail', async t => {
  const envelope = { ok: true, data: { text: 'z'.repeat(300), elements: 'e'.repeat(3000), results: [
    { ok: true, data: { text: 'a'.repeat(500) }, method: 'get_element_text' },
    { ok: false, errorCode: 'NO_ELEMENT', data: { text: 'b'.repeat(500), html: 'h'.repeat(3000) } },
  ] } };
  const { text, projected } = renderEnvelope(envelope, { maxChars: 100 });
  assert.deepEqual(JSON.parse(text), projected);
  assert.deepEqual(projected.data.omitted, ['elements']);
  assert.equal(projected.ok, true);
  assert.deepEqual(projected.data.results[0].data.textTruncated, true);
  assert.equal(projected.data.results[0].data.text.length, 100);
  assert.equal(projected.data.results[0].data.textChars, 500);
  assert.equal(projected.data.results[1].ok, false);
  assert.equal(projected.data.results[1].errorCode, 'NO_ELEMENT');
  assert.deepEqual(projected.data.results[1].data.omitted, ['html']);
});
test('select projects only the requested paths and still reports ok', async t => {
  const envelope = { ok: true, data: { text: 't'.repeat(900), results: [{ data: { text: 'inner' } }] } };
  const flat = renderEnvelope(envelope, { maxChars: 100, select: 'data.text' });
  assert.deepEqual(JSON.parse(flat.text), { ok: true, 'data.text': 't'.repeat(900) });
  const nested = renderEnvelope(envelope, { maxChars: 100, select: 'data.results.0.data.text' });
  assert.deepEqual(JSON.parse(nested.text), { ok: true, 'data.results.0.data.text': 'inner' });
  const missing = renderEnvelope(envelope, { maxChars: 100, select: 'data.nope' });
  assert.deepEqual(JSON.parse(missing.text), { ok: true, 'data.nope': null });
  const bracket = renderEnvelope(envelope, { maxChars: 100, select: 'data.results[].data.text' });
  assert.deepEqual(JSON.parse(bracket.text), { ok: true, 'data.results[].data.text': 'inner' });
});
test('select is accepted by dsb_state/command/job/screenshot and projects only that path', async t => {
  const { client, calls } = await server(t, body => {
    if (body?.method === 'commands') return { ok: true, data: { jobId: 'job-1', status: 'running' } };
    if (body?.method === 'screenshot') return { ok: true, data: { path: '/shot.png' } };
    if (body?.method === 'get_browser_state') return { ok: true, data: { text: 'page text' } };
    if (body?.method === 'get_job') return { ok: true, data: { jobId: 'job-1', status: 'done', url: 'https://example.com' } };
    return { ok: true, data: { url: 'https://example.com' } };
  });
  const f = fixture(client);
  for (const [name, args, path] of [['state', {}, 'data.text'], ['command', { method: 'get_url' }, 'data.url'],
    ['screenshot', { force: true }, 'data.path']]) {
    const tool = f.tools.find(t => t.name === `dsb_${name}`);
    const result = await f.call(name, args);
    const rendered = tool.output.render({ ...args, select: path }, result);
    assert.deepEqual(Object.keys(JSON.parse(rendered[0].text)), ['ok', path], `${name} select`);
  }
  const jobTool = f.tools.find(t => t.name === 'dsb_job');
  for (const name of ['state', 'command', 'job', 'screenshot']) {
    assert.equal(f.tools.find(t => t.name === `dsb_${name}`).parameters.properties.select?.type, 'string', `${name} must expose select`);
  }
  await f.call('batch', { commands: [{ method: 'get_url' }] });
  const job = await f.call('job', { jobId: 'job-1' });
  const renderedJob = jobTool.output.render({ jobId: 'job-1', select: 'data.url' }, job);
  assert.deepEqual(JSON.parse(renderedJob[0].text), { ok: true, 'data.url': 'https://example.com' });
  assert.ok(calls.some(call => call.body?.method === 'get_job'));
});

test('dsb_job does not inline a huge batch result unless includeResult is requested', async t => {
  const big = { results: Array.from({ length: 200 }, (_v, i) => ({ method: 'get_element_text', data: { text: `step-${i}-`.padEnd(80, 'x') } })) };
  const { client, calls } = await server(t, body => body?.method === 'commands'
    ? { ok: true, data: { jobId: 'job-1', status: 'running' } }
    // 服务端 describe(withResult):默认(includeResult 未显式给 false)就带上全量 data ——
    // 这正是要防的场景:200 步 × 全量 data 被插件默认拉回来
    : { ok: true, data: { jobId: body.params.jobId, status: 'done', steps: 200, ok: true, data: big } });
  const f = fixture(client);
  await f.call('batch', { commands: [{ method: 'get_url' }] });
  const lean = await f.call('job', { jobId: 'job-1' });
  assert.equal(lean.data.hasResult, true);
  assert.equal(lean.data.data, undefined);
  assert.equal(lean.data.steps, 200);
  assert.equal(lean.data.resultChars > 4000, true);
  assert.match(lean.data.hint, /includeResult/);
  const full = await f.call('job', { jobId: 'job-1', includeResult: true });
  assert.deepEqual(full.data.data, big);
  assert.deepEqual(calls.filter(c => c.body?.method === 'get_job').map(c => c.body.params.includeResult), [false, true]);
  const under = await server(t, body => body?.method === 'commands'
    ? { ok: true, data: { jobId: 'job-2', status: 'running' } }
    : { ok: true, data: { jobId: body.params.jobId, status: 'done', steps: 2, data: { results: [1, 2] } } });
  const g = fixture(under.client);
  await g.call('batch', { commands: [{ method: 'get_url' }] });
  const small = await g.call('job', { jobId: 'job-2' });
  assert.deepEqual(small.data.data, { results: [1, 2] });
});

test('snapshot command list stays aligned with Java registry', async () => {
  const java = await readFile(new URL('../../../playwright-server/src/main/java/nexus/io/ai/browser/actions/registry/CommandTable.java', import.meta.url), 'utf8');
  assert.deepEqual(commandNames, [...java.matchAll(/put\("([a-z_]+)"/g)].map(m => m[1]));
});
test('config validates defaults and plugin registers native tools with disposal', () => {
  assert.equal(Config({}).timeoutMs, 60000);
  assert.throws(() => Config({ timeoutMs: -1 }));
  const registered = []; const effects = [];
  apply({ effect(fn) { effects.push(fn()); }, tools: { register(tool) { registered.push(tool); } } });
  assert.equal(registered.length, 15);
  assert.equal(effects.length, 1);
  assert(registered.every(t => t.name.startsWith('dsb_') && t.output.schema && t.parameters));
});

test('real Harness ToolRuntime registers and dispatches the plugin tools', async t => {
  const { client } = await server(t, body => ({ ok: true, data: { text: body?.method ?? 'health' } }));
  const f = fixture(client);
  const ctx = new Context();
  ctx.provide('systemPrompt', { tools() {} });
  const runtime = new ToolRuntime(ctx);
  const disposers = f.tools.map(tool => runtime.register(tool));
  const sdk = renderToolsSdk(f.tools.map(tool => ({ name: tool.name, description: tool.description, parameters: tool.parameters, output: tool.output.schema })));
  assert.match(sdk, /dsb_command/);
  assert.match(sdk, /dsb_batch/);
  const result = await runtime.execute({ name: 'dsb_state', arguments: {}, callId: 'test-1', agent: f.a, signal: signal() });
  assert.equal(result.isError, false, JSON.stringify(result));
  assert.equal(result.value.data.text, 'get_browser_state');
  const invalid = await runtime.execute({ name: 'dsb_click', arguments: { index: -1 }, callId: 'test-2', agent: f.a, signal: signal() });
  assert.equal(invalid.isError, true);
  disposers.forEach(dispose => dispose());
  assert.equal(runtime.schemas().length, 0);
  await f.sessions.dispose();
});


test('screenshot forwards capture policy and preserves partial evidence diagnostics', async t => {
  const { client, calls } = await server(t, body => ({ ok: true, data: body.method === 'screenshot'
    ? { fallbackUsed: true, fullPageCaptured: false, warning: 'viewport only', capture: { actualMode: 'viewport', attempts: 2 } } : {} }));
  const f = fixture(client);
  const result = await f.call('screenshot', { fullPage: true, fallbackToViewport: true, timeoutMs: 8000, force: false });
  assert.equal(result.data.fullPageCaptured, false);
  assert.equal(result.data.capture.actualMode, 'viewport');
  const shot = calls.find(call => call.body.method === 'screenshot').body.params;
  assert.deepEqual(shot, { fullPage: true, fallbackToViewport: true, timeoutMs: 8000, force: false, inline: false });
  const before = calls.length;
  await assert.rejects(f.call('screenshot', { timeoutMs: 0 }));
  await assert.rejects(f.call('screenshot', { timeoutMs: 120001 }));
  assert.equal(calls.length, before);
  await f.sessions.dispose();
});

// ---------- P0-2:execute_js 的三种语法形状与结果回捞 ----------

/** 与服务端 normalizeScript 同一套判据的参照实现:用来断言「包装没有改变谁被调用」 */
const FUNCTION_LIKE = /(=>|\bfunction\b|^\s*(async\s*)?\(|^\s*(async\s+)?[A-Za-z_$][\w$]*\s*=>)/;
const RETURN_STATEMENT = /(^|[;{}\n])\s*return[\s;(]/;
function serverSideScript(body, vars = {}) {
  // 复刻 PlaywrightService.applyVars + normalizeScript,用来验证包装不破坏 {{VAR}} 语义
  let script = body;
  for (const [key, value] of Object.entries(vars)) {
    const encoded = JSON.stringify(value);
    script = script.split(`"{{${key}}}"`).join(encoded).split(`{{${key}}}`).join(encoded);
  }
  script = script.trim();
  if (FUNCTION_LIKE.test(script) || !RETURN_STATEMENT.test(script)) return script;
  return `() => {${script}}`;
}
/** 在 Node 里执行服务端那一步求值:表达式是函数就先调用,是 Promise 就等它(与 CDP/Playwright 同语义) */
async function evaluateLikePlaywright(script) {
  // eslint-disable-next-line no-new-func
  const value = new Function(`return (${script});`)();
  return typeof value === 'function' ? await value() : await value;
}
function pageStore(token) {
  return new Function(`return globalThis.__dsbRecovery[String.fromCharCode(123,123)+'${token}'+String.fromCharCode(125,125)];`)();
}

test('wrapScript preserves every body shape, returns the value and stores the same value', async () => {
  const cases = [
    ['函数式 body', '() => 6 * 7', 42, {}],
    ['函数体带 return', '() => { return 6 * 7; }', 42, {}],
    ['async 函数式 body', 'async () => 42', 42, {}],
    ['async 函数体带 await', 'async () => { const x = await Promise.resolve(40); return x + 2; }', 42, {}],
    ['纯表达式', '1 + 2 + 3', 6, {}],
    ['对象字面量表达式', '({a: 1, b: [2, 3]})', { a: 1, b: [2, 3] }, {}],
    ['多行语句片段', 'const a = 40;\nreturn a + 2;', 42, {}],
    ['单行语句片段', 'const a = 40; return a + 2;', 42, {}],
    ['只有 return', 'return 42;', 42, {}],
    ['语句片段里 await', 'const x = await Promise.resolve(41);\nreturn x + 1;', 42, {}],
    ['return 一个 async 调用', 'return (async () => 42)();', 42, {}],
    ['带 {{VAR}} 的函数式 body', '() => "{{who}}" + "!"', '世界!', { who: '世界' }],
    ['带 {{VAR}} 的语句片段', 'const n = {{n}};\nreturn n * 2;', 84, { n: 42 }],
  ];
  for (const [label, body, expected, vars] of cases) {
    const token = `tok${label.length}`;
    // 服务端先做变量替换、再按它自己的判据决定要不要补包装:客户端包装必须原样进入这一步
    const served = serverSideScript(wrapScript(body, token), vars);
    assert.equal(served.includes('{{who}}') || served.includes('{{n}}'), false, `${label}: 变量必须被替换,而不是被包装破坏`);
    const value = await evaluateLikePlaywright(served);
    assert.deepEqual(value, expected, `${label}: 求值结果`);
    // 页内副本必须与求值结果一致 —— 伪故障回捞读的就是它
    const store = pageStore(token);
    assert.equal(store?.ok, true, `${label}: 必须落副本`);
    assert.deepEqual(store.value, expected, `${label}: 副本值`);
  }
});
test('wrapScript keeps ${VAR} template syntax and introduces no replaceable {{ key }} literal of its own', () => {
  const body = '() => `raw ${1 + 1}`';
  const wrapped = wrapScript(body, 'abc123');
  assert.ok(wrapped.includes('`raw ${1 + 1}`'), '模板字符串原样保留');
  // 包装层不能新增任何 {{key}} 字面量:applyVars 会对整段脚本替换,同名变量会把包装自己替换坏。
  // 调用方 body 里本来就有的 {{VAR}} 不算(见上一条用例),所以这里只看「包装新增的部分」。
  const wrapperOnly = wrapped.replace(body, '');
  assert.equal(/\{\{[^}]*\}\}/.test(wrapperOnly), false, '包装层不得出现 {{key}} 字面量');
  assert.ok(wrapperOnly.includes('String.fromCharCode(123,123)'), '页内键是运行时拼出来的');
  assert.equal(wrapperOnly.includes('{{'), false, '包装层连孤立的 {{ 都不该有');
  // 探针同样是只读的:它只能读副本,不能再执行调用方的脚本
  const probe = recoveryProbe('abc123');
  assert.ok(probe.includes('abc123'));
  assert.equal(/\{\{[^}]*\}\}/.test(probe), false);
  assert.equal(probe.includes('__dsbRecovery'), true);
});
test('wrapScript reports a thrown script error without losing the original failure', async () => {
  const served = serverSideScript(wrapScript('() => { throw new Error("boom"); }', 'tokerr'), {});
  await assert.rejects(evaluateLikePlaywright(served), /boom/);
  const store = pageStore('tokerr');
  assert.equal(store.ok, false);
  assert.equal(store.error.message, 'boom');
});
test('classify marks spurious dispatch as outcome-unknown and escalates an exhausted budget', () => {
  assert.deepEqual(classify({ ok: true, data: {} }), { ok: true, data: {} });
  const plain = classify({ ok: false, msg: '执行 JavaScript 失败：Object doesn\'t exist: response@…', data: { errorCode: 'SPURIOUS_DISPATCH' } });
  assert.equal(plain.data.spurious, true);
  assert.equal(plain.data.outcomeUnknown, true);
  assert.equal(plain.data.executedLikely, true);
  assert.equal(plain.data.retryExhausted, undefined);
  assert.equal(plain.data.recovery.some(text => text.includes('urlAfter')), true);
  assert.equal(plain.data.recovery.some(text => text.includes('retryOnSpurious')), true);
  const flag = classify({ ok: false, data: { spuriousDispatch: true } });
  assert.equal(flag.data.spurious, true, 'spuriousDispatch:true 也算');
  const exhausted = classify({ ok: false, data: { spuriousDispatch: true, retryBudget: { attempts: 3, commandAttempts: 3 } } });
  assert.equal(exhausted.data.retryExhausted, true);
  assert.equal(exhausted.data.recovery.some(text => text.includes('别原地连点')), true);
  // 非伪故障的失败不能被贴错标签
  const real = classify({ ok: false, msg: '业务失败', data: { errorCode: 'NO_ELEMENT' } });
  assert.equal(real.data.spurious, undefined);
  assert.equal(real.data.outcomeUnknown, undefined);
});
test('evaluate forwards vars/bodyFile/retryOnSpurious and recovers a spurious result from the page copy', async t => {
  const { client, calls } = await server(t, body => {
    if (body.method !== 'execute_js') return { ok: true, data: {} };
    if (calls.filter(call => call.body?.method === 'execute_js').length === 1) {
      return { ok: false, msg: '执行 JavaScript 失败：Object doesn\'t exist: response@…',
        data: { errorCode: 'SPURIOUS_DISPATCH', retryBudget: { attempts: 1, commandAttempts: 1 } } };
    }
    return { ok: true, data: { result: { found: true, outcome: { ok: true, value: 42 } } } };
  });
  const f = fixture(client);
  // 包装过的脚本里带一个随机 token,回捞探针要能从包装里读出同一个 token
  const result = await f.call('evaluate', { body: '() => 42', retryOnSpurious: true, frame: '1' });
  assert.equal(result.ok, true);
  assert.equal(result.data.result, 42);
  assert.equal(result.data.recoveredFromSpurious, true);
  assert.match(String(result.data.originalError), /Object doesn't exist/);
  const first = calls.find(call => call.body?.method === 'execute_js').body.params;
  assert.equal(first.retryOnSpurious, true);
  assert.equal(first.frame, '1');
  assert.match(first.body, /__dsbRecovery/);
  const token = /'([0-9a-f]{12})'/.exec(first.body)[1];
  const probe = calls.filter(call => call.body?.method === 'execute_js')[1].body.params;
  assert.equal(probe.retryOnSpurious, true);
  assert.ok(probe.body.includes(token), '探针必须读回同一个 token 的副本');
  await f.sessions.dispose();
});
test('evaluate reports a spurious failure unambiguously when the page copy is empty', async t => {
  const { client, calls } = await server(t, body => body.method !== 'execute_js' ? { ok: true, data: {} }
    : calls.filter(call => call.body?.method === 'execute_js').length === 1
      ? { ok: false, msg: '执行 JavaScript 失败：Object doesn\'t exist: response@…', data: { spuriousDispatch: true } }
      : { ok: true, data: { result: { found: false } } });
  const f = fixture(client);
  const result = await f.call('evaluate', { body: 'document.title' });
  assert.equal(result.ok, false);
  assert.equal(result.data.spurious, true);
  assert.equal(result.data.recoveredFromSpurious, false);
  await f.sessions.dispose();
});
test('evaluate raw:true bypasses both the wrapper and the recovery probe', async t => {
  const { client, calls } = await server(t, body => body.method === 'start' || body.method === 'close'
    ? { ok: true, data: { id: body.id } }
    : { ok: false, msg: '执行 JavaScript 失败：Object doesn\'t exist: response@…', data: { errorCode: 'SPURIOUS_DISPATCH' } });
  const f = fixture(client);
  const result = await f.call('evaluate', { body: '() => 42', raw: true });
  assert.equal(result.ok, false);
  assert.equal(result.data.spurious, true);
  assert.equal(result.data.recoveredFromSpurious, undefined);
  assert.equal(calls.filter(call => call.body?.method === 'execute_js').length, 1);
  const sent = calls.find(call => call.body?.method === 'execute_js').body.params;
  assert.equal(sent.body, '() => 42', 'raw 时脚本必须原样送达');
  assert.equal(sent.retryOnSpurious, undefined);
  await f.sessions.dispose();
});
test('evaluate accepts bodyFile or body but not both, and asJob routes through the async channel', async t => {
  const { client, calls } = await server(t, body => body.method === 'commands'
    ? { ok: true, data: { jobId: 'job-9', status: 'running' } } : { ok: true, data: {} });
  const f = fixture(client);
  await assert.rejects(f.call('evaluate', {}), /exactly one of body or bodyFile/);
  await assert.rejects(f.call('evaluate', { body: '1', bodyFile: 'a.js' }), /exactly one of body or bodyFile/);
  await assert.rejects(f.call('evaluate', { body: '1', bogus: true }));
  const result = await f.call('evaluate', { bodyFile: 'probe.js', vars: { n: 3 }, asJob: true });
  assert.equal(result.data.jobId, 'job-9');
  const submitted = calls.find(call => call.body?.method === 'commands').body.params;
  assert.equal(submitted.async, true);
  assert.deepEqual(submitted.commands[0].execute_js.bodyFile, 'probe.js');
  assert.deepEqual(submitted.commands[0].execute_js.vars, { n: 3 });
  assert.equal(submitted.commands[0].execute_js.body, undefined, 'bodyFile 走文件通道时不带 body');
  await f.sessions.dispose();
});

// ---------- P0-3:截图熔断感知、短路与恢复指引 ----------

test('screenshot short-circuits while the capture circuit is open and respects force', async t => {
  let shots = 0;
  const { client, calls } = await server(t, body => {
    if (body.method !== 'screenshot') return { ok: true, data: {} };
    shots += 1;
    return shots === 1
      ? { ok: false, data: { capture_degraded: true, retryAfterMs: 30000, capture_note: '截图不可用(熔断中)', errorCode: 'CAPTURE_FAILED' } }
      : { ok: true, data: { screenshot: '/data/1/2.png', capture_degraded: false } };
  });
  const f = fixture(client);
  await f.call('screenshot', {});
  assert.equal(shots, 1);
  const blocked = await f.call('screenshot', {});
  assert.equal(blocked.ok, false);
  assert.equal(blocked.errorCode, 'CAPTURE_CIRCUIT_OPEN');
  assert.equal(blocked.capture_degraded, true);
  assert.equal(blocked.retryAfterMs > 0, true);
  assert.match(blocked.hint, /force:true/);
  assert.equal(shots, 1, '熔断期内不得发 HTTP');
  assert.equal(calls.filter(call => call.body?.method === 'screenshot').length, 1);
  // force:true 是一次探测,未显式给预算时压到 5000ms
  const probe = await f.call('screenshot', { force: true });
  assert.equal(probe.ok, true);
  assert.equal(calls.filter(call => call.body?.method === 'screenshot')[1].body.params.timeoutMs, 5000);
  assert.equal(calls.filter(call => call.body?.method === 'screenshot')[1].body.params.force, true);
  // 显式预算不被覆盖
  await f.call('screenshot', { force: true, timeoutMs: 9000 });
  assert.equal(calls.filter(call => call.body?.method === 'screenshot')[2].body.params.timeoutMs, 9000);
  const after = await f.call('screenshot', {});
  assert.equal(after.ok, true, 'capture_degraded:false 之后熔断必须被清掉');
  await f.sessions.dispose();
});
test('a sustained capture circuit adds the dsb_close/dsb_start recovery hint only after three hits', async t => {
  const { client, calls } = await server(t, body => {
    if (body.method === 'start' || body.method === 'close') return { ok: true, data: { id: body.id } };
    return { ok: false, data: { capture_degraded: true, retryAfterMs: 30000 } };
  });
  const f = fixture(client);
  const first = await f.call('screenshot', {});
  assert.equal(first.ok, false, '第一次是服务端回执,不是插件短路');
  assert.equal(first.recovery, undefined);
  const second = await f.call('screenshot', {});
  assert.equal(second.errorCode, 'CAPTURE_CIRCUIT_OPEN', '第二次起插件自己短路,不再发 HTTP');
  assert.equal(second.recovery, undefined);
  const third = await f.call('screenshot', {});
  assert.deepEqual(third.recovery, ['dsb_close', 'dsb_start'], '连续 3 次才提示换任务,避免噪音');
  assert.equal(f.sessions.inspect(f.a).data.captureDegradedStreak, 3);
  assert.equal(calls.filter(call => call.body?.method === 'screenshot').length, 1);
  await f.sessions.dispose();
});

// ---------- P1 ----------

test('cancel_job releases the session immediately instead of requiring another dsb_job', async t => {
  const { client, calls } = await server(t, body => {
    if (body.method === 'commands') return { ok: true, data: { jobId: 'job-1', status: 'running' } };
    if (body.method === 'cancel_job') return { ok: true, data: { jobId: 'job-1', cancelRequested: true, status: 'running' } };
    return { ok: true };
  });
  const f = fixture(client);
  await f.call('batch', { commands: [{ method: 'get_url' }] });
  await assert.rejects(f.call('click', { index: 0 }), /pending/);
  await f.call('job', { jobId: 'job-1', cancel: true });
  // 回归:以前 activeJob 不会被清掉,这一条会被 "Browser batch … is pending" 挡住
  await f.call('click', { index: 1 });
  assert.equal(calls.filter(call => call.body?.method === 'click_element_by_index').length, 1);
  await f.sessions.dispose();
});
test('dsb_start self-heals when the service no longer knows this task', async t => {
  let starts = 0;
  const { client, calls } = await server(t, body => {
    if (body.method === 'start') { starts += 1; return { ok: true, data: { id: body.id, browser: 'chrome' } }; }
    if (body.method === 'get_url' && starts === 1) {
      return { ok: false, msg: `没有找到对应的浏览器实例：${body.id}`, data: { hint: '本服务进程启动于 2026-10-05 10:00:00,重启后任务不会恢复' } };
    }
    if (body.method === 'get_url') return { ok: true, data: { url: 'https://example.com' } };
    return { ok: true, data: {} };
  });
  const f = fixture(client);
  const first = await f.call('start');
  assert.equal(first.data.reused, undefined);
  // 服务端换了进程:id 已经不存在,复用分支必须复位并真正 start 一次
  const second = await f.call('start');
  assert.equal(second.ok, true);
  assert.equal(second.data.recoveredFromMissingInstance, true);
  assert.equal(starts, 2);
  assert.deepEqual(calls.filter(call => call.body?.method === 'start').length, 2);
  const third = await f.call('start');
  assert.equal(third.data.reused, true, '实例还在时仍然复用');
  assert.equal(third.data.url, 'https://example.com');
  assert.equal(starts, 2);
  await f.sessions.dispose();
});
test('dsb_health never triggers the installer and summarises tasks, launching and capture state', async t => {
  const { client, calls } = await server(t, (body, req) => {
    if (req.url === '/playwright/health') return { ok: true, data: { name: 'playwright-server' } };
    if (req.url === '/playwright/tasks') return { ok: true, data: { count: 2, launching: { elapsedMs: 1200, browser: 'chrome' },
      tasks: [{ id: 1, url: 'https://a' }, { id: 2, url: 'https://b' }], browser: { type: 'chrome' } } };
    if (req.url === '/playwright/config') return { ok: true, data: { jsDir: 'scripts/js', capture: { degraded: true, cooldownMs: 30000 } } };
    return { ok: true, data: {} };
  });
  let ensured = 0;
  client.options.ensureBackend = async () => { ensured += 1; };
  const f = fixture(client);
  const result = await f.call('health');
  assert.equal(result.ok, true);
  assert.equal(result.data.health.name, 'playwright-server');
  assert.equal(result.data.taskCount, 2);
  assert.deepEqual(result.data.launching, { elapsedMs: 1200, browser: 'chrome' });
  assert.equal(result.data.captureDegraded, true);
  assert.equal(result.data.config.jsDir, 'scripts/js');
  assert.equal(ensured, 0, '健康检查不得调用 ensureBackend');
  assert.deepEqual(calls.map(call => call.path).sort(), ['/playwright/config', '/playwright/health', '/playwright/tasks']);
  await f.sessions.dispose();
});
test('an unknown batch outcome still allows the restricted read-only way out', async t => {
  let cancelled = false;
  const { client, calls } = await server(t, (body, _req, res) => {
    if (body.method === 'commands') { res.end('truncated-response'); return; }
    if (body.method === 'list_jobs') return { ok: true, data: { count: 2, jobs: [
      { jobId: 'job-1', browserId: body.id, status: cancelled ? 'cancelled' : 'running', steps: 3 },
      { jobId: 'job-other', browserId: 999, status: 'done', steps: 1 },
    ] } };
    if (body.method === 'cancel_job') { cancelled = true; return { ok: true, data: { jobId: body.params.jobId, cancelRequested: true, status: 'running' } }; }
    if (body.method === 'get_job') return { ok: true, data: { jobId: body.params.jobId, status: cancelled ? 'cancelled' : 'running', steps: 3 } };
    return { ok: true, data: {} };
  });
  const f = fixture(client);
  await assert.rejects(f.call('batch', { commands: [{ method: 'get_url' }] }), /outcome may be unknown/);
  let frozen;
  try { await f.call('click', { index: 0 }); assert.fail('click must be blocked while the batch outcome is unknown'); }
  catch (error) { frozen = error; }
  assert.match(String(frozen?.message), /submission outcome unknown/);
  assert.match(String(frozen?.message), /dsb_job\(\{browserId:/, '错误信息必须说明怎么恢复');
  const taskId = f.sessions.inspect(f.a).data.id;
  // 受限只读出路:只列本任务(browserId)的作业,别的任务的不许看
  const listed = await f.call('job', { browserId: taskId });
  assert.equal(listed.data.count, 1);
  assert.equal(listed.data.jobs[0].jobId, 'job-1');
  await assert.rejects(f.call('job', { jobId: 'job-other' }), /does not belong/);
  assert(!calls.some(call => call.body?.method === 'click_element_by_index'), '受限期间不得提交新动作');
  // 还有 running 的作业时不许解冻
  await assert.rejects(f.call('job', { browserId: taskId, clearUnknown: true }), /still running/);
  assert.equal(f.sessions.inspect(f.a).data.batchUnknown, true);
  // 取消并看到终态之后,显式解冻;此前普通命令一律被挡
  await f.call('job', { jobId: 'job-1', cancel: true });
  await assert.rejects(f.call('state'), /submission outcome unknown/);
  const cleared = await f.call('job', { browserId: taskId, clearUnknown: true });
  assert.equal(cleared.data.batchUnknownCleared, true);
  assert.equal(f.sessions.inspect(f.a).data.batchUnknown, false);
  const resumed = await f.call('state');
  assert.equal(resumed.ok, true, '解冻后可以继续提交动作');
  await f.sessions.dispose();
});
