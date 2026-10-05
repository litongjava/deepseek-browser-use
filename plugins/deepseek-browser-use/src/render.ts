import type { Envelope, Params } from './client.js';

/**
 * 回执里**永远完整输出**的小字段
 *
 * <p>
 * 这些字段是「模型下一步该怎么做」的全部依据:成功与否、错误码、能不能重试、状态落在哪个文件、
 * 截图和快照是否可信。以前的 render 是 `JSON.stringify(result).slice(0, maxTextChars)`,而信封里
 * `data` 排在前面、`get_browser_state` 的 `data.text` 又最大 —— 结果是 `ok/code/msg/state_file`
 * 全被切掉,模型看到的是半截 JSON,既读不出结论也无法解析。所以这些字段必须**先于任何大字段**
 * 完整输出,预算只花在大字段上。
 */
export const ALWAYS_FIELDS = ['ok', 'code', 'msg', 'errorCode', 'retryable', 'retryAfterMs', 'warning', 'state_file',
  'screenshot', 'screenshot_path', 'url', 'urlAfter', 'title', 'spuriousDispatch', 'retryExhausted',
  'capture_degraded', 'capture_note', 'indicesUsable', 'snapshotConsistent', 'snapshotIssues',
  'elementsTruncated', 'pageAppearsBlank', 'recoveredFromSpurious', 'outcomeUnknown'] as const;

/** 独立预算的大文本字段(信封根层与 `data` 下面的名字) */
const TEXT_KEYS = ['text', 'browser_state'] as const;

/** 一个字段超过这个长度就不再原样内联,只留名字进 omitted */
const FIELD_INLINE_LIMIT = 2000;

export interface Projection {
  value: Record<string, unknown>;
  notes: string[];
}

interface Context {
  maxChars: number;
  /** 被截断/省略字段的「路径」前缀,用于 textTruncated / omitted 的标注 */
  path: string;
  notes: string[];
}

/** 单个大文本字段的独立预算:值取前 maxChars 个字符,并在同层补上 textTruncated / textChars */
function clipText(context: Context, key: string, value: string): Record<string, unknown> {
  if (value.length <= context.maxChars) return { [key]: value };
  context.notes.push(`${context.path}${key}: ${value.length} 字符,仅输出前 ${context.maxChars}`);
  return { [key]: value.slice(0, context.maxChars), textTruncated: true, textChars: value.length };
}

function isOmissible(value: unknown): boolean {
  if (typeof value === 'string') return value.length > FIELD_INLINE_LIMIT;
  if (Array.isArray(value)) return value.length > 50 || JSON.stringify(value)?.length > FIELD_INLINE_LIMIT;
  if (value && typeof value === 'object') return JSON.stringify(value)?.length > FIELD_INLINE_LIMIT;
  return false;
}

/** 未知层级的对象:小对象原样保留,大对象只留名字,避免又一个「大字段把关键字段挤掉」的入口 */
function projectOpaque(context: Context, value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const omitted: string[] = [];
  for (const [key, item] of Object.entries(value)) {
    if (isOmissible(item)) {
      omitted.push(key);
      continue;
    }
    out[key] = item;
  }
  if (omitted.length) {
    context.notes.push(`${context.path}omitted: ${omitted.join(', ')}`);
    out.omitted = omitted;
  }
  return out;
}

/** 命令返回值(`data.result`)可能是任意 JSON:大结果只报长度,别把信封撑爆 */
function projectResult(context: Context, value: unknown): unknown {
  if (typeof value === 'string') {
    if (value.length <= context.maxChars) return value;
    context.notes.push(`${context.path}result: ${value.length} 字符`);
    return { textTruncated: true, textChars: value.length, value: value.slice(0, context.maxChars) };
  }
  const encoded = JSON.stringify(value ?? null);
  if (encoded !== undefined && encoded.length <= FIELD_INLINE_LIMIT) return value;
  context.notes.push(`${context.path}result: 序列化后 ${encoded?.length ?? 0} 字符`);
  return { omitted: true, resultChars: encoded?.length ?? 0 };
}

/** 批量回执的每一步:保留错误、索引等小字段,只对 `data.text` 这类大字段单独限量 */
function projectResults(context: Context, items: unknown[]): unknown[] {
  return items.map((item, position) => {
    const path = `${context.path}results.${position}.`;
    if (Array.isArray(item)) return item.map((entry, index) =>
      entry && typeof entry === 'object' && !Array.isArray(entry)
        ? projectEnvelopeLike({ ...context, path: `${path}${index}.` }, entry as Record<string, unknown>) : entry);
    if (!item || typeof item !== 'object') return item;
    const source = item as Record<string, unknown>;
    const inner = source.data;
    if (!inner || typeof inner !== 'object' || Array.isArray(inner)) return projectOpaque({ ...context, path }, source);
    return { ...projectOpaque({ ...context, path }, source), data: projectEnvelopeLike({ ...context, path: `${path}data.` }, inner as Record<string, unknown>) };
  });
}

/** `data` 的投影:文本字段独立预算,其余大字段进 omitted */
function projectData(context: Context, value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const omitted: string[] = [];
  for (const [key, item] of Object.entries(value)) {
    // 关键字段即使在 data 层出现也一律完整保留(批量每一步的 ok/errorCode 就在这里)
    if ((ALWAYS_FIELDS as readonly string[]).includes(key)) {
      out[key] = item;
      continue;
    }
    if ((TEXT_KEYS as readonly string[]).includes(key) && typeof item === 'string') {
      Object.assign(out, clipText(context, key, item));
      continue;
    }
    if (key === 'results' && Array.isArray(item)) {
      out.results = projectResults(context, item);
      continue;
    }
    if (key === 'result') {
      out.result = projectResult(context, item);
      continue;
    }
    if (isOmissible(item)) {
      omitted.push(key);
      continue;
    }
    out[key] = item;
  }
  if (omitted.length) {
    context.notes.push(`${context.path}omitted: ${omitted.join(', ')}`);
    out.omitted = omitted;
  }
  return out;
}

/**
 * 回执对象的字段感知投影
 *
 * <p>
 * 与 `data` 的投影分开命名,是因为 `data.results[].data` 也是同一形状(批量的每一步),
 * 需要递归地用同一套规则,而不是各写一遍。
 */
export function projectEnvelopeLike(context: Context, value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const omitted: string[] = [];
  for (const [key, item] of Object.entries(value)) {
    if ((ALWAYS_FIELDS as readonly string[]).includes(key)) {
      out[key] = item;
      continue;
    }
    if ((TEXT_KEYS as readonly string[]).includes(key) && typeof item === 'string') {
      Object.assign(out, clipText(context, key, item));
      continue;
    }
    if (key === 'data' && item && typeof item === 'object' && !Array.isArray(item)) {
      // 成功回执的 data.* 才是主体;data 自身的小字段仍然是「关键字段」,由 projectData 原样保留
      out.data = projectData(context, item as Record<string, unknown>);
      continue;
    }
    if (key === 'error' && item && typeof item === 'object' && !Array.isArray(item)) {
      out.error = projectOpaque({ ...context, path: `${context.path}error.` }, item as Record<string, unknown>);
      continue;
    }
    if (key === 'steps' && Array.isArray(item)) {
      out.steps = projectSteps(context, item);
      continue;
    }
    if (isOmissible(item)) {
      omitted.push(key);
      continue;
    }
    out[key] = item;
  }
  if (omitted.length) {
    context.notes.push(`${context.path}omitted: ${omitted.join(', ')}`);
    out.omitted = omitted;
  }
  return out;
}

/** 作业的步骤列表:每步保留 method/ok/error,大 `data` 走同一套投影 */
function projectSteps(context: Context, steps: unknown[]): unknown[] {
  return steps.map((step, position) => {
    const path = `${context.path}steps.${position}.`;
    if (!step || typeof step !== 'object' || Array.isArray(step)) return step;
    return projectEnvelopeLike({ ...context, path }, step as Record<string, unknown>);
  });
}

/** select 表达式的规范化路径:`pages[]` / `pages.0` 都接受,空串与多余点忽略 */
function segments(path: string): string[] {
  return path.split('.').flatMap(part => part.endsWith('[]') ? [part.slice(0, -2), '0'] : [part]).filter(part => part.length > 0);
}

/** select 的简化路径取值,如 `data.text` 或 `data.results.0.data.text` */
export function readPath(value: unknown, path: string): unknown {
  let current = value;
  for (const segment of segments(path)) {
    if (Array.isArray(current)) {
      if (!/^\d+$/.test(segment)) return undefined;
      current = current[Number(segment)];
    } else if (current && typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }
  return current;
}

/**
 * 把回执渲染成**始终合法**的 JSON
 *
 * <p>
 * 大字段的省略说明写在 `notes` 字段里,而不是像以前那样拼在 JSON 尾巴上 —— 后者会让整段文本
 * 不可解析,模型只能靠正则去猜结论。`select` 给定时只投影点名的路径(仍保留 `ok`),
 * 值是字符串且超预算时同样只切那个值。
 */
export function renderEnvelope(value: unknown, options: { maxChars: number; select?: string }): { text: string; projected: Record<string, unknown> } {
  const notes: string[] = [];
  const source = (value && typeof value === 'object' && !Array.isArray(value)) ? value as Record<string, unknown> : { value };
  const select = options.select?.trim();
  if (select) {
    const projected: Record<string, unknown> = { ok: source.ok };
    // 显式点名的字段不再截断:模型要的就是它,再切一次等于没给
    for (const path of select.split(',').map(item => item.trim()).filter(Boolean)) {
      // 取不到时写 null 而不是省略:让模型看到「这条路径不存在」,同时保证输出仍是合法 JSON
      projected[path] = readPath(source, path) ?? null;
    }
    const output = { ...projected, ...(notes.length ? { notes } : {}) };
    return { text: JSON.stringify(output), projected: output };
  }
  const projected = projectEnvelopeLike({ maxChars: options.maxChars, path: '', notes }, source);
  if (notes.length) projected.notes = notes;
  return { text: JSON.stringify(projected), projected };
}
