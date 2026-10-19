import fs from 'node:fs/promises';
import path from 'node:path';

const args = process.argv.slice(2);
const mode = args.shift();
const options = new Map();
for (let index = 0; index < args.length; index += 2) options.set(args[index], args[index + 1]);
let socket;
let timer;
try {
  if (!['inspect', 'load'].includes(mode) || !options.get('--profile')) throw new Error('Invalid arguments');
  const lines = (await fs.readFile(path.join(options.get('--profile'), 'DevToolsActivePort'), 'utf8')).trim().split(/\r?\n/);
  const port = Number(lines[0]);
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !/^\/devtools\/browser\/[\w-]+$/.test(lines[1])) throw new Error('Invalid endpoint');
  socket = new WebSocket(`ws://127.0.0.1:${port}${lines[1]}`);
  let sequence = 0;
  const pending = new Map();
  const deadline = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error('CDP timeout')), 15000);
  });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (request) {
      pending.delete(message.id);
      if (message.error) request.reject(new Error('CDP rejected command'));
      else request.resolve(message.result);
    }
  });
  const command = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, {resolve, reject});
    socket.send(JSON.stringify({id, method, params}));
  });
  const operation = async () => {
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, {once: true});
      socket.addEventListener('error', () => reject(new Error('CDP connection failed')), {once: true});
    });
    const existing = await command('Extensions.getExtensions');
    let matches = existing.extensions.filter(extension => extension.name === 'Proxy SwitchyOmega 3 (ZeroOmega)');
    if (mode === 'load' && matches.length === 0) {
      if (!options.get('--extension')) throw new Error('Extension directory required');
      const directory = path.resolve(options.get('--extension'));
      const manifest = JSON.parse(await fs.readFile(path.join(directory, 'manifest.json'), 'utf8'));
      if (manifest.name !== 'Proxy SwitchyOmega 3 (ZeroOmega)' || manifest.manifest_version !== 3) throw new Error('Unexpected extension');
      const loaded = await command('Extensions.loadUnpacked', {path: directory});
      const after = await command('Extensions.getExtensions');
      matches = after.extensions.filter(extension => extension.id === loaded.id);
      if (matches.length !== 1 || !matches[0].enabled) throw new Error('Extension not enabled');
    }
    console.log(JSON.stringify({ok: true, extensions: matches.map(({id, name, version, enabled}) => ({id, name, version, enabled}))}));
  };
  await Promise.race([operation(), deadline]);
} catch {
  console.error(JSON.stringify({ok: false, hint: 'Inspect the current browser before retrying. CDP may be unavailable or the load may already have completed; use chrome://extensions/.'}));
  process.exitCode = 1;
} finally {
  clearTimeout(timer);
  socket?.close();
}
