import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { inspectClaudePlugin, syncClaudePlugin } from '../lib/claude-skill-plugin.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsb-skill-sync-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const options = {
    targetDir: path.join(root, '.claude', 'skills'),
    pluginManifest: Buffer.from(JSON.stringify({ name: 'dsb-skills', version: '1.0.0' })),
    skills: [{ name: 'browser' }, { name: 'site' }],
    plannedFiles: (skill) => [
      { rel: 'SKILL.md', data: Buffer.from(`---\nname: ${skill.name}\ndescription: Synthetic test skill\n---\nRead [guide](references/guide.md).\n`) },
      { rel: 'references/guide.md', data: Buffer.from('Synthetic reference\n') },
    ],
  };
  const write = (rel, text) => {
    const file = path.join(options.targetDir, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
  };
  return { root, options, write };
}

test('dry run changes nothing and reports all names', (t) => {
  const { root, options } = fixture(t);
  const result = syncClaudePlugin({ ...options, dryRun: true });
  assert.equal(result.wrote, false);
  assert.deepEqual(result.names, ['browser', 'site']);
  assert.deepEqual(fs.readdirSync(root), []);
});

test('installs a namespaced plugin with references and is idempotent', (t) => {
  const { options } = fixture(t);
  const result = syncClaudePlugin(options);
  assert.equal(result.wrote, true);
  assert.equal(JSON.parse(fs.readFileSync(path.join(result.destination, '.claude-plugin/plugin.json'))).name, 'dsb-skills');
  assert.match(fs.readFileSync(path.join(result.destination, 'skills/browser/SKILL.md'), 'utf8'), /name: browser/);
  assert.equal(fs.readFileSync(path.join(result.destination, 'skills/site/references/guide.md'), 'utf8'), 'Synthetic reference\n');
  assert.equal(fs.existsSync(path.join(options.targetDir, 'browser')), false);
  assert.equal(inspectClaudePlugin(options).same, true);
  assert.equal(syncClaudePlugin(options).wrote, false);
});

test('archives owned legacy differences and leaves unrelated skills intact', (t) => {
  const { options, write } = fixture(t);
  write('.sync-skills.json', JSON.stringify({ skillNames: ['browser', 'other'], custom: 'keep' }));
  write('browser/SKILL.md', 'locally edited version');
  write('browser/extra.txt', 'local-only file');
  write('other/SKILL.md', 'unrelated');
  const result = syncClaudePlugin(options);
  assert.equal(fs.readFileSync(path.join(result.backupDir, 'legacy/browser/SKILL.md'), 'utf8'), 'locally edited version');
  assert.equal(fs.readFileSync(path.join(result.backupDir, 'legacy/browser/extra.txt'), 'utf8'), 'local-only file');
  assert.equal(fs.existsSync(path.join(options.targetDir, 'browser')), false);
  assert.equal(fs.readFileSync(path.join(options.targetDir, 'other/SKILL.md'), 'utf8'), 'unrelated');
  const manifest = JSON.parse(fs.readFileSync(path.join(options.targetDir, '.sync-skills.json')));
  assert.deepEqual(manifest.skillNames, ['other']);
  assert.equal(manifest.custom, 'keep');
  assert.deepEqual(manifest.plugins['dsb-skills'].skillNames, ['browser', 'site']);
});

test('refuses unmanaged legacy skills before writing even with force', (t) => {
  const { root, options, write } = fixture(t);
  write('browser/SKILL.md', 'unmanaged');
  assert.throws(() => syncClaudePlugin({ ...options, force: true }), /Unmanaged legacy/);
  assert.equal(fs.existsSync(path.join(root, '.claude/skill-backups')), false);
  assert.equal(fs.readFileSync(path.join(options.targetDir, 'browser/SKILL.md'), 'utf8'), 'unmanaged');
});

test('refuses an unmanaged plugin directory', (t) => {
  const { options, write } = fixture(t);
  write('dsb-skills/local.txt', 'keep');
  assert.throws(() => syncClaudePlugin(options), /Unmanaged plugin/);
  assert.equal(fs.readFileSync(path.join(options.targetDir, 'dsb-skills/local.txt'), 'utf8'), 'keep');
});

test('backs up previous plugin contents including extra files before an update', (t) => {
  const { options, write } = fixture(t);
  syncClaudePlugin(options);
  write('dsb-skills/skills/browser/local.txt', 'local addition');
  const result = syncClaudePlugin(options);
  assert.equal(fs.readFileSync(path.join(result.backupDir, 'previous-plugin/skills/browser/local.txt'), 'utf8'), 'local addition');
  assert.equal(fs.existsSync(path.join(result.destination, 'skills/browser/local.txt')), false);
  assert.equal(inspectClaudePlugin(options).same, true);
});

test('rejects backups under the discovery directory', (t) => {
  const { options } = fixture(t);
  assert.throws(() => syncClaudePlugin({ ...options, backupRoot: path.join(options.targetDir, 'backups') }), /outside/);
});

test('rejects path traversal before writing', (t) => {
  const { options } = fixture(t);
  options.plannedFiles = () => [
    { rel: 'SKILL.md', data: Buffer.from('test') },
    { rel: '../escape', data: Buffer.from('test') },
  ];
  assert.throws(() => syncClaudePlugin(options), /Unsafe/);
});

test('restores legacy skills if installing the staged plugin fails', (t) => {
  const { options, write } = fixture(t);
  write('.sync-skills.json', JSON.stringify({ skillNames: ['browser'] }));
  write('browser/SKILL.md', 'original');
  const original = fs.renameSync;
  fs.renameSync = (from, to) => {
    if (path.basename(from) === 'next-plugin') throw new Error('synthetic rename failure');
    return original(from, to);
  };
  try {
    assert.throws(() => syncClaudePlugin(options), /synthetic rename failure/);
  } finally {
    fs.renameSync = original;
  }
  assert.equal(fs.readFileSync(path.join(options.targetDir, 'browser/SKILL.md'), 'utf8'), 'original');
  assert.equal(fs.existsSync(path.join(options.targetDir, 'dsb-skills')), false);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(options.targetDir, '.sync-skills.json'))).skillNames, ['browser']);
});
