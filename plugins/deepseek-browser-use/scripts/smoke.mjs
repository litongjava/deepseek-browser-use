// Real browser smoke: point DSB_BASE_URL at a running service. Never shuts down that service.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Context } from '@deepseek-ai/cordis';
import { ToolRuntime } from '@deepseek-ai/dsh-tools';
import { BrowserClient } from '../dist/client.js';
import { BrowserSessions } from '../dist/sessions.js';
import { createTools } from '../dist/tools.js';

const client = new BrowserClient({ baseUrl: process.env.DSB_BASE_URL ?? 'http://127.0.0.1:10049', timeoutMs: 60000, maxResponseBytes: 16777216 });
const sessions = new BrowserSessions(client, { browser: process.env.DSB_BROWSER ?? 'chrome', headless: true });
const cleanups = [];
const agent = { id: 'plugin-smoke', ctx: { effect(fn) { const dispose = fn(); cleanups.push(dispose); return dispose; } } };
const ctx = new Context();
ctx.provide('systemPrompt', { tools() {} });
ctx.provide('agents', { get(id) { return id === agent.id ? agent : undefined; } });
const runtime = new ToolRuntime(ctx);
const disposers = createTools(ctx, client, sessions, { maxTextChars: 24000, maxUploadBytes: 8388608 }).map(tool => runtime.register(tool));
const page = createServer((_req, res) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.end('<!doctype html><title>DSB plugin smoke</title><label>Name<input id="name"></label><button id="go" onclick="document.querySelector(\'#result\').textContent=document.querySelector(\'#name\').value">Submit</button><p id="result"></p><input id="file" type="file">');
});
page.listen(0, '127.0.0.1'); await once(page, 'listening');
const dir = await mkdtemp(join(tmpdir(), 'dsb-smoke-'));
let n = 0;
async function call(name, args = {}) {
  const result = await runtime.execute({ name: `dsb_${name}`, arguments: args, callId: `smoke-${++n}`, agent, signal: AbortSignal.timeout(90000) });
  assert.equal(result.isError, false, JSON.stringify(result));
  assert.equal(result.value.ok, true, JSON.stringify(result.value));
  console.log(`${n}. dsb_${name}: OK`);
  return result.value;
}
try {
  await call('health');
  await call('navigate', { url: `http://127.0.0.1:${page.address().port}` });
  const state = await call('state'); assert.match(JSON.stringify(state), /Submit/);
  await call('input', { selector: '#name', text: '中文插件验证' });
  await call('click', { selector: '#go' });
  const checked = await call('evaluate', { body: 'return document.querySelector("#result").textContent;' });
  assert.match(JSON.stringify(checked), /中文插件验证/);
  const file = join(dir, 'smoke.txt'); await writeFile(file, 'upload verification');
  await call('upload', { localPath: file, selector: '#file' });
  const uploaded = await call('evaluate', { body: 'return document.querySelector("#file").files[0].name;' });
  assert.match(JSON.stringify(uploaded), /smoke.txt/);
  const screenshot = await call('screenshot'); assert.match(JSON.stringify(screenshot), /\.png/);
  // 字段感知投影:select 接受简化路径,规范值不受影响(投影文本由 render 基于同一份值产出,有单测钉住)
  const projected = await call('state', { select: 'data.text' });
  assert.equal(typeof projected.data.text, 'string');
  assert.match(projected.data.text, /Submit/);
  const submitted = await call('batch', { commands: [{ method: 'get_title' }, { method: 'get_url' }] });
  let job;
  const deadline = Date.now() + 30000;
  do {
    // includeResult:true 才内联作业结果;默认只回 {steps, hasResult}
    job = await call('job', { jobId: submitted.data.jobId, includeResult: true });
    if (job.data.status !== 'running') break;
    if (Date.now() > deadline) throw new Error('Smoke batch did not finish within 30s');
    await new Promise(resolve => setTimeout(resolve, 150));
  } while (true);
  assert.equal(job.data.ok, true, JSON.stringify(job));
  await call('close');
  console.log('Real Harness ToolRuntime → HTTP → browser smoke passed.');
} finally {
  try { await sessions.dispose(); } finally {
    disposers.forEach(dispose => dispose());
    page.closeAllConnections(); await new Promise(resolve => page.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
}
