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
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : undefined;
    calls.push({ path: req.url, body });
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
test('file upload transfers bytes, not a host-local path', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'dsb-plugin-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, '中文.txt'); await writeFile(path, '文件内容');
  const { client, calls } = await server(t, () => ({ ok: true }));
  const f = fixture(client);
  await f.call('upload', { selector: 'input[type=file]', localPath: path });
  const args = calls.find(c => c.body.method === 'upload_file').body.params;
  assert.equal(args.filename, '中文.txt');
  assert.equal(Buffer.from(args.contentBase64, 'base64').toString(), '文件内容');
  assert.equal(args.path, undefined);
  await writeFile(path, 'x'.repeat(1025));
  await assert.rejects(f.call('upload', { index: 0, localPath: path }), /maxUploadBytes/);
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
test('text rendering is bounded while canonical output is complete', async t => {
  const { client } = await server(t, () => ({ ok: true, data: { text: 'x'.repeat(4000) } }));
  const f = fixture(client);
  const result = await f.call('state');
  const rendered = f.tools.find(t => t.name === 'dsb_state').output.render({}, result);
  assert.equal(result.data.text.length, 4000);
  assert.match(rendered[0].text, /Display truncated/);
  await f.sessions.dispose();
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
  assert.equal(registered.length, 14);
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
