import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

export const PLUGIN_NAME = 'dsb-skills';
const MANIFEST = '.sync-skills.json';
const OWNER = 'deepseek-browser-use/sync-skills';
const digest = (data) => createHash('sha256').update(data).digest('hex');

function readManifest(file) {
  if (!fs.existsSync(file)) return {};
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function assertDirectory(dir) {
  if (!fs.existsSync(dir)) return;
  const info = fs.lstatSync(dir);
  if (!info.isDirectory() || info.isSymbolicLink()) {
    throw new Error(`Expected a real directory, not a file or link: ${dir}`);
  }
}

function filesIn(root, rel = '') {
  if (!fs.existsSync(root)) return [];
  const files = [];
  for (const item of fs.readdirSync(root, { withFileTypes: true })) {
    const name = rel ? `${rel}/${item.name}` : item.name;
    if (item.isSymbolicLink()) throw new Error(`Refusing a symlink in the skill installation: ${name}`);
    if (item.isDirectory()) files.push(...filesIn(path.join(root, item.name), name));
    else if (item.isFile()) files.push(name);
    else throw new Error(`Unsupported skill entry: ${name}`);
  }
  return files.sort();
}

/** Inspect the entire installation before changing any live files. */
export function inspectClaudePlugin({ skills, targetDir, pluginManifest, plannedFiles, force = false }) {
  assertDirectory(targetDir);
  const manifest = JSON.parse(pluginManifest.toString('utf8'));
  if (manifest.name !== PLUGIN_NAME) throw new Error(`Plugin name must be ${PLUGIN_NAME}`);
  const destination = path.join(targetDir, PLUGIN_NAME);
  assertDirectory(destination);
  const rootManifestPath = path.join(targetDir, MANIFEST);
  const rootManifest = readManifest(rootManifestPath);
  const legacyOwned = new Set(rootManifest.skillNames || []);
  const expected = new Map([['.claude-plugin/plugin.json', pluginManifest]]);
  const names = [];
  const legacy = [];
  for (const skill of skills) {
    if (!/^[a-z0-9-]+$/.test(skill.name) || skill.name === PLUGIN_NAME || names.includes(skill.name)) {
      throw new Error(`Invalid or duplicate skill directory: ${skill.name}`);
    }
    names.push(skill.name);
    const planned = plannedFiles(skill);
    if (!planned.some((file) => file.rel === 'SKILL.md')) throw new Error(`Missing SKILL.md: ${skill.name}`);
    for (const file of planned) {
      const parts = file.rel.split(/[\\/]/);
      if (path.isAbsolute(file.rel) || parts.some((part) => !part || part === '..' || part === '.')) {
        throw new Error(`Unsafe skill file: ${file.rel}`);
      }
      expected.set(`skills/${skill.name}/${parts.join('/')}`, file.data);
    }
    const oldDir = path.join(targetDir, skill.name);
    if (fs.existsSync(oldDir)) {
      assertDirectory(oldDir);
      if (!legacyOwned.has(skill.name)) {
        throw new Error(`Unmanaged legacy skill would load twice; preserve or move it manually: ${skill.name}`);
      }
      filesIn(oldDir);
      legacy.push(skill.name);
    }
  }
  const marker = readManifest(path.join(destination, MANIFEST));
  if (fs.existsSync(destination) && marker.managedBy !== OWNER && !force) {
    throw new Error(`Unmanaged plugin directory: ${destination}; use --force only after reviewing it`);
  }
  const actual = filesIn(destination).filter((name) => name !== MANIFEST);
  const changed = [...expected].filter(([name, data]) =>
    !actual.includes(name) || !fs.readFileSync(path.join(destination, name)).equals(data)).map(([name]) => name);
  const extra = actual.filter((name) => !expected.has(name));
  const sameMarker = marker.managedBy === OWNER && marker.pluginName === PLUGIN_NAME
    && JSON.stringify(marker.skillNames) === JSON.stringify(names)
    && Object.keys(marker.files || {}).length === expected.size
    && [...expected].every(([name, data]) => marker.files?.[name] === digest(data));
  return {
    destination, targetDir, expected, names, legacy, changed, extra,
    rootManifest, rootManifestPath,
    same: fs.existsSync(destination) && !changed.length && !extra.length && !legacy.length && sameMarker,
  };
}

/** Install a complete plugin and archive owned legacy skills outside the discovery root. */
export function syncClaudePlugin(options) {
  const plan = inspectClaudePlugin(options);
  if (options.dryRun || plan.same) return { ...plan, backupDir: null, wrote: false };
  const backupRoot = options.backupRoot || path.join(path.dirname(plan.targetDir), 'skill-backups', PLUGIN_NAME);
  const relative = path.relative(plan.targetDir, path.resolve(backupRoot));
  if (relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative))) {
    throw new Error('Skill backups must be outside the skill discovery directory');
  }
  fs.mkdirSync(backupRoot, { recursive: true });
  const backupDir = fs.mkdtempSync(path.join(backupRoot, 'sync-'));
  const staged = path.join(backupDir, 'next-plugin');
  for (const [name, data] of plan.expected) {
    const file = path.join(staged, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data);
    if (!fs.readFileSync(file).equals(data)) throw new Error(`Staged file verification failed: ${name}`);
  }
  const metadata = {
    managedBy: OWNER,
    pluginName: PLUGIN_NAME,
    skillNames: plan.names,
    files: Object.fromEntries([...plan.expected].map(([name, data]) => [name, digest(data)])),
  };
  fs.writeFileSync(path.join(staged, MANIFEST), JSON.stringify(metadata, null, 2) + '\n');
  const originalRootManifest = fs.existsSync(plan.rootManifestPath) ? fs.readFileSync(plan.rootManifestPath) : null;
  if (originalRootManifest) fs.writeFileSync(path.join(backupDir, 'legacy-manifest.json'), originalRootManifest);
  const moved = [];
  let installed = false;
  let manifestWritten = false;
  try {
    fs.mkdirSync(plan.targetDir, { recursive: true });
    if (fs.existsSync(plan.destination)) {
      const saved = path.join(backupDir, 'previous-plugin');
      fs.renameSync(plan.destination, saved);
      moved.push([saved, plan.destination]);
    }
    for (const name of plan.legacy) {
      const saved = path.join(backupDir, 'legacy', name);
      fs.mkdirSync(path.dirname(saved), { recursive: true });
      const current = path.join(plan.targetDir, name);
      fs.renameSync(current, saved);
      moved.push([saved, current]);
    }
    fs.renameSync(staged, plan.destination);
    installed = true;
    const nextRootManifest = {
      ...plan.rootManifest,
      skillNames: (plan.rootManifest.skillNames || []).filter((name) => !plan.names.includes(name)),
      plugins: { ...plan.rootManifest.plugins, [PLUGIN_NAME]: { managedBy: OWNER, skillNames: plan.names } },
    };
    const nextManifestPath = path.join(backupDir, 'next-manifest.json');
    fs.writeFileSync(nextManifestPath, JSON.stringify(nextRootManifest, null, 2) + '\n');
    fs.renameSync(nextManifestPath, plan.rootManifestPath);
    manifestWritten = true;
    if (!inspectClaudePlugin(options).same) throw new Error('Installed plugin verification failed');
  } catch (error) {
    if (installed) fs.renameSync(plan.destination, path.join(backupDir, 'failed-plugin'));
    for (const [saved, original] of moved.reverse()) fs.renameSync(saved, original);
    if (manifestWritten) {
      if (originalRootManifest) fs.writeFileSync(plan.rootManifestPath, originalRootManifest);
      else fs.renameSync(plan.rootManifestPath, path.join(backupDir, 'failed-manifest.json'));
    }
    throw error;
  }
  return { ...plan, backupDir, wrote: true };
}
