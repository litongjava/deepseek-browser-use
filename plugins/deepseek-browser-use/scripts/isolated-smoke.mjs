// Development-only runner. Requires Maven-compiled classes and dependency classpath.
// Owns its Java process, ephemeral port, profile and output directory.
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, delimiter } from 'node:path';
import { BrowserClient } from '../dist/client.js';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const serverDir = resolve(root, 'playwright-server');
const dependencies = (await readFile(resolve(serverDir, 'target/plugin-smoke-classpath.txt'), 'utf8')).trim();
const outputRoot = resolve(root, 'logs/plugin-smoke');
await mkdir(outputRoot, { recursive: true });
const output = await mkdtemp(resolve(outputRoot, 'run-'));
const socket = createServer(); socket.listen(0, '127.0.0.1'); await once(socket, 'listening');
const port = socket.address().port; await new Promise(r => socket.close(r));
await writeFile(resolve(output, 'app.properties'), `server.port=${port}\n`);
const child = spawn(process.env.JAVA ?? 'java', [
  // Keep all Java temporary socket files in this runner's owned directory too.
  `-Djdk.net.unixdomain.tmpdir=${output}`,
  `-Dserver.port=${port}`, `-Dbrowser.profileDir=${resolve(output, 'profile')}`,
  `-Dbrowser.chrome.cdpProfileDir=${resolve(output, 'profile')}`,
  '-Dbrowser.chrome.useUserProfile=false', '-cp', `${resolve(serverDir, 'target/classes')}${delimiter}${dependencies}`,
  'nexus.io.ai.browser.PlaywrightApp',
], { cwd: output, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
child.stdout.on('data', data => { log += data; }); child.stderr.on('data', data => { log += data; });
const exited = once(child, 'exit');
const baseUrl = `http://127.0.0.1:${port}`;
const client = new BrowserClient({ baseUrl, timeoutMs: 3000, maxResponseBytes: 16777216 });
let ready = false;
try {
  for (let i = 0; i < 60; i++) {
    if (child.exitCode !== null) throw new Error(`Java exited: ${log.slice(-3000)}`);
    try { const health = await client.request('/playwright/health', AbortSignal.timeout(1000)); ready = health.ok; } catch {}
    if (ready) break;
    await new Promise(r => setTimeout(r, 500));
  }
  if (!ready) throw new Error(`Java service did not become ready: ${log.slice(-3000)}`);
  const smoke = spawn(process.execPath, [fileURLToPath(new URL('./smoke.mjs', import.meta.url))], {
    env: { ...process.env, DSB_BASE_URL: baseUrl }, windowsHide: true, stdio: 'inherit', timeout: 150000,
  });
  const [code] = await once(smoke, 'exit');
  if (code !== 0) throw new Error(`Browser smoke exited ${code}`);
} finally {
  // This endpoint belongs exclusively to this runner, so global shutdown is appropriate here only.
  if (ready) await client.command(1, 'shutdown', {}, AbortSignal.timeout(5000)).catch(() => {});
  child.kill(); await exited;
  await writeFile(resolve(output, 'java.log'), log);
  console.log(`Isolated Java logs and browser artifacts: ${output}`);
}
