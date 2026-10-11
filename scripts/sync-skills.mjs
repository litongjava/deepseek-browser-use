#!/usr/bin/env node
/**
 * 把仓库里的技能（.agents/skills/）安装到本机各个 AI 编码工具的技能目录。
 *
 * 三个工具对 frontmatter 的要求不一样，脚本按目标改写，源文件始终只有一份：
 *
 *   dsh    → ~/.dsh/skills/      认 whenToUse（驼峰），未知键忽略
 *   claude → ~/.claude/skills/dsb-skills/   插件调用名 dsb-skills:<name>
 *   codex  → ~/.codex/skills/    严格白名单：只允许 name/description/license/
 *                                allowed-tools/metadata，多一个键就整份拒绝加载；
 *                                description 不能含尖括号、不能超过 1024 字符。
 *                                所以 whenToUse 会被并进 description（不留丢路由信息），
 *                                尖括号换成全角、超长截断。
 *
 * 用法：
 *   node scripts/sync-skills.mjs                    交互式选择目标
 *   node scripts/sync-skills.mjs --all              装到全部工具
 *   node scripts/sync-skills.mjs dsh claude         装到指定工具
 *   node scripts/sync-skills.mjs --all --dry-run    只看会写什么，不动文件
 *   node scripts/sync-skills.mjs --status           看各目标与仓库是否已经一致
 *   node scripts/sync-skills.mjs --all --force      覆盖目标里非本脚本安装的同名技能
 *
 * 每个目标根目录下有一份 .sync-skills.json 清单，记着哪些技能是本脚本装的；
 * DSH/Codex 跳过非清单技能；Claude 以插件安装并备份迁移旧技能，遇到非清单旧技能会停止。
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { PLUGIN_NAME, inspectClaudePlugin, syncClaudePlugin } from './lib/claude-skill-plugin.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const SRC_DIR = path.join(REPO_ROOT, '.agents', 'skills');

const HOME = os.homedir();

/** codex 对 description 的硬限制，来自它自带的 quick_validate.py。 */
const CODEX_DESC_MAX = 1024;

/** 从 frontmatter 文本里取单行标量的值。 */
function fmValue(frontmatter, key) {
  const m = new RegExp(`^${key}:\\s*(.*)$`, 'm').exec(frontmatter);
  return m ? m[1].trim() : undefined;
}

/** 拆出 frontmatter 与正文。 */
function splitFrontmatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!m) return null;
  return { frontmatter: m[1], body: text.slice(m[0].length), raw: m[0] };
}

/**
 * 各目标如何把源 SKILL.md 变成目标 SKILL.md。
 * 返回 { text, notes }，notes 会打印出来，避免改写动作被无声吞掉。
 */
const TARGETS = {
  dsh: {
    label: 'DSH (DeepSeek Harness)',
    dir: path.join(HOME, '.dsh', 'skills'),
    // DSH 读驼峰键；源文件本来就是驼峰，原样复制。
    rewrite: (text) => ({ text, notes: [] }),
  },
  claude: {
    label: 'Claude Code (dsb-skills plugin)',
    dir: path.join(HOME, '.claude', 'skills'),
    // Claude Code 读下划线键，驼峰会被静默忽略，所以改键名。
    rewrite: (text) => ({ text: text.replace(/^whenToUse:/m, 'when_to_use:'), notes: [] }),
  },
  codex: {
    label: 'Codex',
    dir: path.join(HOME, '.codex', 'skills'),
    rewrite: (text) => {
      const parts = splitFrontmatter(text);
      if (!parts) return { text, notes: [] };
      const notes = [];
      const name = fmValue(parts.frontmatter, 'name') || '';
      const when = fmValue(parts.frontmatter, 'whenToUse');
      let desc = fmValue(parts.frontmatter, 'description') || '';

      // codex 不认 whenToUse，删掉会丢路由信息，所以并进 description。
      if (when) { desc = `${desc} ${when}`.trim(); notes.push('把 whenToUse 并入 description'); }

      // codex 的校验器拒绝 description 里的尖括号。
      if (/[<>]/.test(desc)) {
        desc = desc.replace(/</g, '＜').replace(/>/g, '＞');
        notes.push('描述里的尖括号换成全角（codex 不接受 < >）');
      }
      if (desc.length > CODEX_DESC_MAX) {
        desc = desc.slice(0, CODEX_DESC_MAX - 3) + '...';
        notes.push(`描述超长，截到 ${CODEX_DESC_MAX} 字符`);
      }

      // 保留 codex 白名单里的其它键（本仓库暂时没有，留着以防将来加）。
      const keep = ['license', 'allowed-tools'];
      const extra = keep
        .map((k) => fmValue(parts.frontmatter, k))
        .filter((v) => v !== undefined)
        .map((v, i) => `${keep[i]}: ${v}`)
        .join('\n');

      // description 用块标量输出，避免长文本里的冒号等字符破坏 YAML。
      const fm = [
        `name: ${name}`,
        'description: |-',
        ...desc.split('\n').map((l) => '  ' + l),
        ...(extra ? [extra] : []),
      ].join('\n');

      return { text: `---\n${fm}\n---\n${parts.body}`, notes };
    },
  },
};

const MANIFEST_NAME = '.sync-skills.json';

function parseArgs(argv) {
  const opts = { tools: [], all: false, dryRun: false, force: false, status: false, verbose: false };
  for (const a of argv) {
    if (a === '--all') opts.all = true;
    else if (a === '--dry-run') opts.dryRun = true;
    else if (a === '--force') opts.force = true;
    else if (a === '--status') opts.status = true;
    else if (a === '-v' || a === '--verbose') opts.verbose = true;
    else if (a === '-h' || a === '--help') opts.help = true;
    else if (TARGETS[a]) opts.tools.push(a);
    else throw new Error(`未知参数：${a}`);
  }
  return opts;
}

function usage() {
  console.log(`把 ${path.relative(REPO_ROOT, SRC_DIR)} 里的技能安装到各工具的技能目录。

用法：
  node scripts/sync-skills.mjs                   交互式选择目标
  node scripts/sync-skills.mjs --all             装到全部工具
  node scripts/sync-skills.mjs dsh claude        装到指定工具
  node scripts/sync-skills.mjs --all --dry-run   预览，不改动文件
  node scripts/sync-skills.mjs --status          只看各目标是否已与仓库一致
  node scripts/sync-skills.mjs --all --force     覆盖非本脚本安装的同名技能

Claude 使用 dsb-skills:<name>，旧技能备份到 ~/.claude/skill-backups/，不会覆盖未受管理的旧技能。
可用的目标：${Object.keys(TARGETS).join(', ')}`);
}

/** 列出源技能目录（只认 <技能名>/SKILL.md 这一层，与各工具的规则一致）。 */
function readSourceSkills() {
  if (!fs.existsSync(SRC_DIR)) throw new Error(`找不到技能源目录：${SRC_DIR}`);
  const skills = [];
  for (const entry of fs.readdirSync(SRC_DIR, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const skillFile = path.join(SRC_DIR, entry.name, 'SKILL.md');
    if (!fs.existsSync(skillFile)) continue;
    skills.push({ name: entry.name, dir: path.join(SRC_DIR, entry.name) });
  }
  return skills.sort((a, b) => a.name.localeCompare(b.name));
}

/** 递归收集相对文件列表。 */
function relativeFiles(root) {
  const out = [];
  (function walk(dir, rel) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(path.join(dir, e.name), r);
      else if (e.name !== MANIFEST_NAME) out.push(r);
    }
  })(root, '');
  return out.sort();
}

function readManifest(target) {
  const f = path.join(target.dir, MANIFEST_NAME);
  if (!fs.existsSync(f)) return { skillNames: [] };
  try {
    const parsed = JSON.parse(fs.readFileSync(f, 'utf8'));
    return { skillNames: Array.isArray(parsed.skillNames) ? parsed.skillNames : [] };
  } catch {
    return { skillNames: [] };
  }
}

function writeManifest(target, names) {
  fs.mkdirSync(target.dir, { recursive: true });
  fs.writeFileSync(
    path.join(target.dir, MANIFEST_NAME),
    JSON.stringify({ skillNames: [...names].sort() }, null, 2) + '\n',
  );
}

/** 这份技能写进目标时应该是什么内容（已按目标规则改写）。 */
function plannedFiles(skill, target) {
  const notes = [];
  const files = relativeFiles(skill.dir).map((rel) => {
    const raw = fs.readFileSync(path.join(skill.dir, rel));
    if (rel !== 'SKILL.md') return { rel, data: raw };
    const { text, notes: n } = target.rewrite(raw.toString('utf8'));
    notes.push(...n);
    return { rel, data: Buffer.from(text, 'utf8') };
  });
  return { files, notes };
}

/** 目标当前内容与"应写内容"的差异。 */
function inspectSkill(skill, target) {
  const destDir = path.join(target.dir, skill.name);
  const { files, notes } = plannedFiles(skill, target);
  if (!fs.existsSync(destDir)) return { status: 'new', changed: files.length, total: files.length, notes };

  const have = new Set(relativeFiles(destDir));
  let changed = files.filter(({ rel, data }) =>
    !have.has(rel) || !fs.readFileSync(path.join(destDir, rel)).equals(data)).length;
  changed += [...have].filter((rel) => !files.some((p) => p.rel === rel)).length;
  return { status: changed ? 'drift' : 'same', changed, total: files.length, notes };
}

function applySkill(skill, target, owned, { dryRun }) {
  const destDir = path.join(target.dir, skill.name);
  const { files } = plannedFiles(skill, target);
  let wrote = 0;
  for (const { rel, data } of files) {
    const dst = path.join(destDir, rel);
    if (fs.existsSync(dst) && fs.readFileSync(dst).equals(data)) continue;
    if (!dryRun) {
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.writeFileSync(dst, data);
    }
    wrote++;
  }
  owned.add(skill.name);
  return wrote;
}

/** 按各目标的规则复查落盘结果（与官方校验器的规则对齐）。 */
function verify(target, skillNames) {
  const problems = [];
  const isCodex = target.dir.includes('.codex');
  const allowed = new Set(['name', 'description', 'license', 'allowed-tools', 'metadata']);
  for (const name of skillNames) {
    const f = path.join(target.dir, name, 'SKILL.md');
    if (!fs.existsSync(f)) { problems.push(`${name}: 没有 SKILL.md`); continue; }
    const parts = splitFrontmatter(fs.readFileSync(f, 'utf8'));
    if (!parts) { problems.push(`${name}: 缺少 frontmatter`); continue; }

    const keys = [...parts.frontmatter.matchAll(/^([A-Za-z_-]+):/gm)].map((x) => x[1]);
    if (!keys.includes('name') || !keys.includes('description')) {
      problems.push(`${name}: 缺 name 或 description`);
    }
    if (isCodex) {
      const bad = keys.filter((k) => !allowed.has(k));
      if (bad.length) problems.push(`${name}: Codex 不接受字段 ${bad.join(',')}`);
      const desc = fmValue(parts.frontmatter, 'description') || '';
      // 块标量形式下，值在后续缩进行里，用整段文本兜底判断。
      const descFull = /description:\s*\|-/.test(parts.frontmatter)
        ? parts.frontmatter.split(/description:\s*\|-\r?\n/)[1].split(/\n[A-Za-z_-]+:/)[0].trim()
        : desc;
      const val = descFull || desc;
      if (/[<>]/.test(val)) problems.push(`${name}: 描述含尖括号，Codex 拒绝`);
      if (val.length > CODEX_DESC_MAX) problems.push(`${name}: 描述长度 ${val.length} 超过 ${CODEX_DESC_MAX}`);
      if (!/^[a-z0-9-]+$/.test(name)) problems.push(`${name}: Codex 要求名称为 kebab-case`);
    }
  }
  return problems;
}

async function chooseTargets() {
  const names = Object.keys(TARGETS);
  console.log('选择要安装到的位置（可多选，逗号分隔；直接回车 = 全部）：\n');
  names.forEach((key, i) => {
    const t = TARGETS[key];
    const state = fs.existsSync(t.dir) ? '' : '  [目录尚未创建]';
    console.log(`  ${i + 1}. ${key.padEnd(7)} ${t.label.padEnd(24)} ${t.dir}${state}`);
  });
  console.log('  a. 全部');
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await new Promise((res) => rl.question('\n> ', res))).trim();
  rl.close();
  if (answer === '' || answer.toLowerCase() === 'a') return names;
  const picked = [];
  for (const part of answer.split(/[,，\s]+/).filter(Boolean)) {
    const n = Number(part);
    if (n >= 1 && n <= names.length) picked.push(names[n - 1]);
    else if (TARGETS[part]) picked.push(part);
    else console.log(`忽略无法识别的选项：${part}`);
  }
  return [...new Set(picked)];
}

function claudePluginOptions(skills, target, opts = {}) {
  return {
    skills,
    targetDir: target.dir,
    pluginManifest: fs.readFileSync(path.join(REPO_ROOT, '.agents', '.claude-plugin', 'plugin.json')),
    plannedFiles: (skill) => plannedFiles(skill, target).files,
    dryRun: opts.dryRun,
    force: opts.force,
  };
}

function reportStatus(skills, targetKeys) {
  let anyDrift = false;
  for (const key of targetKeys) {
    const target = TARGETS[key];
    if (key === 'claude') {
      const plan = inspectClaudePlugin(claudePluginOptions(skills, target));
      console.log(`=== claude → ${plan.destination} ===`);
      console.log(`  ${plan.same ? '一致' : '待同步'} ${PLUGIN_NAME}: ${plan.names.length} 份技能，${plan.changed.length} 个待写文件，${plan.extra.length} 个旧文件，${plan.legacy.length} 份旧技能待备份迁移`);
      if (!plan.same) anyDrift = true;
      continue;
    }
    const owned = new Set(readManifest(target).skillNames);
    console.log(`\n=== ${key} → ${target.dir} ===`);
    if (!fs.existsSync(target.dir)) { console.log('  目录不存在（还没装过）'); anyDrift = true; continue; }
    const tally = { same: 0, drift: 0, new: 0, foreign: 0 };
    for (const skill of skills) {
      const exists = fs.existsSync(path.join(target.dir, skill.name));
      if (exists && !owned.has(skill.name)) {
        tally.foreign++;
        console.log(`  非本脚本 ${skill.name}（跳过，除非 --force）`);
        continue;
      }
      const r = inspectSkill(skill, target);
      tally[r.status]++;
      if (r.status === 'drift') { anyDrift = true; console.log(`  待更新 ${skill.name}  (${r.changed}/${r.total} 个文件有差异)`); }
      else if (r.status === 'new') { anyDrift = true; console.log(`  未安装 ${skill.name}`); }
    }
    const stale = [...owned].filter((n) => !skills.some((s) => s.name === n));
    for (const n of stale) { anyDrift = true; console.log(`  仓库已删除 ${n}（仍在目标里）`); }
    console.log(`  合计：一致 ${tally.same}，待更新 ${tally.drift}，未安装 ${tally.new}，非本脚本 ${tally.foreign}`);
  }
  console.log(anyDrift ? '\n结论：有差异，跑一次同步即可。' : '\n结论：全部与仓库一致。');
  if (anyDrift) process.exitCode = 1;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) { usage(); return; }

  const skills = readSourceSkills();
  if (!skills.length) throw new Error(`源目录里没有找到技能：${SRC_DIR}`);

  let picked = opts.tools;
  if (opts.all) picked = Object.keys(TARGETS);
  if (opts.status && !picked.length) picked = Object.keys(TARGETS);
  if (!picked.length) {
    if (!process.stdin.isTTY) {
      console.log('非交互环境，默认全部目标；要指定目标请传 dsh / claude / codex。\n');
      picked = Object.keys(TARGETS);
    } else {
      picked = await chooseTargets();
    }
  }
  if (!picked.length) { console.log('没有选择任何目标，退出。'); return; }

  console.log(`技能源：${SRC_DIR}（${skills.length} 份）`);
  if (opts.status) { reportStatus(skills, picked); return; }
  if (opts.dryRun) console.log('模式：预演（--dry-run），不会写入文件');

  const allProblems = [];
  for (const key of picked) {
    const target = TARGETS[key];
    console.log(`\n=== ${key} → ${target.dir} ===`);
    if (key === 'claude') {
      const result = syncClaudePlugin(claudePluginOptions(skills, target, opts));
      console.log(`  ${result.same ? '一致' : opts.dryRun ? '将安装' : '已安装'} ${PLUGIN_NAME}: ${result.names.length} 份技能，调用格式 ${PLUGIN_NAME}:<name>`);
      console.log(`  ${result.legacy.length} 份旧技能${opts.dryRun ? '将备份迁移' : '已备份迁移'}；${result.changed.length} 个待写文件`);
      if (result.backupDir) console.log(`  备份目录：${result.backupDir}`);
      if (!opts.dryRun) {
        allProblems.push(...verify({ ...target, dir: path.join(result.destination, 'skills') }, result.names));
      }
      continue;
    }
    const owned = new Set(readManifest(target).skillNames);
    const tally = { new: 0, update: 0, same: 0, foreign: 0 };

    for (const skill of skills) {
      const exists = fs.existsSync(path.join(target.dir, skill.name));
      if (exists && !owned.has(skill.name) && !opts.force) {
        console.log(`  跳过   ${skill.name}  （目标已存在且不是本脚本安装的，加 --force 覆盖）`);
        tally.foreign++;
        continue;
      }
      const { status, changed, total, notes } = inspectSkill(skill, target);
      const noteText = notes.length ? `  [${notes.join('；')}]` : '';
      if (status === 'same') {
        owned.add(skill.name);
        console.log(`  一致   ${skill.name}`);
        if (opts.verbose) console.log(`         ${noteText || '（无需改写）'}`);
        tally.same++;
        continue;
      }
      applySkill(skill, target, owned, opts);
      if (!exists) { console.log(`  新增   ${skill.name}  (${total} 个文件)${noteText}`); tally.new++; }
      else { console.log(`  更新   ${skill.name}  (${changed}/${total} 个文件有差异)`); tally.update++; }
      if (opts.verbose && noteText) console.log(`         ${noteText}`);
    }

    if (!opts.dryRun) {
      writeManifest(target, owned);
      const done = skills.map((s) => s.name).filter((n) => owned.has(n));
      allProblems.push(...verify(target, done).map((p) => `${key}: ${p}`));
    }
    console.log(`  合计：新增 ${tally.new}，更新 ${tally.update}，未变 ${tally.same}，跳过 ${tally.foreign}`);
  }

  if (allProblems.length) {
    console.log('\n校验发现问题：');
    for (const p of allProblems) console.log('  - ' + p);
    process.exitCode = 1;
  } else if (!opts.dryRun) {
    console.log('\n完成，各目标 frontmatter 校验通过。');
  }
}

main().catch((e) => { console.error('错误：' + e.message); process.exitCode = 1; });