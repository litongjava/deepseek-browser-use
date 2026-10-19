import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import test from 'node:test';

test('CDP inspection, load, reuse and rejected command produce bounded safe results', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'zeroomega-cdp-test-'));
  try {
    fs.writeFileSync(path.join(directory, 'DevToolsActivePort'), '12345\n/devtools/browser/test');
    fs.writeFileSync(path.join(directory, 'manifest.json'), JSON.stringify({name: 'Proxy SwitchyOmega 3 (ZeroOmega)', manifest_version: 3}));
    const preload = path.join(directory, 'mock.mjs');
    fs.writeFileSync(preload, `
      let installed = process.env.TEST_SCENARIO === 'existing';
      const extension = {id:'fixture-id', name:'Proxy SwitchyOmega 3 (ZeroOmega)', version:'3.5.2', enabled:true, path:'private-path'};
      globalThis.WebSocket = class extends EventTarget {
        constructor() { super(); setTimeout(() => this.dispatchEvent(new Event('open')), 0); }
        send(raw) {
          const request = JSON.parse(raw);
          let result;
          if (request.method === 'Extensions.getExtensions') result = {extensions: installed ? [extension] : []};
          else if (request.method === 'Extensions.loadUnpacked') {
            if (installed) throw new Error('Duplicate load');
            installed = true;
            result = {id:extension.id};
          } else throw new Error('Unexpected method');
          const response = process.env.TEST_SCENARIO === 'reject' ? {id:request.id,error:{message:'private-failure'}} : {id:request.id,result};
          setTimeout(() => this.dispatchEvent(new MessageEvent('message', {data:JSON.stringify(response)})), 0);
        }
        close() {}
      };
    `);
    for (const [scenario, mode, count, status] of [['empty', 'inspect', 0, 0], ['empty', 'load', 1, 0], ['existing', 'load', 1, 0], ['reject', 'inspect', 0, 1]]) {
      const result = spawnSync(process.execPath, ['--import', pathToFileURL(preload).href,
        fileURLToPath(new URL('./extension.mjs', import.meta.url)), mode,
        '--profile', directory, '--extension', directory], {encoding:'utf8', env:{...process.env, TEST_SCENARIO:scenario}, timeout:5000});
      assert.equal(result.status, status, result.stderr);
      const output = result.stdout + result.stderr;
      assert.ok(output.length < 600);
      assert.ok(!output.includes('private-'));
      const payload = JSON.parse(output);
      if (status === 0) assert.equal(payload.extensions.length, count);
      else assert.equal(payload.ok, false);
    }
  } finally {
    fs.rmSync(directory, {recursive:true, force:true});
  }
});
