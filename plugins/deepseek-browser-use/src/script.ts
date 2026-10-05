import { randomBytes } from 'node:crypto';
import type { Envelope, Params } from './client.js';

/**
 * 「像函数字面量」的判据
 *
 * <p>
 * 两条约束都来自真实服务的实测:
 * <ul>
 * <li>不能照抄服务端那条的 `^\s*\(` 分支:它会把 `({a: 1})` 这种「括号包起来的表达式」认成函数,
 * 于是包装层去调用一个对象,报 `{(intermediate value)} is not a function`;</li>
 * <li>又必须给 `async () =&gt;` / `function ()` 补一对括号:服务端只认 `(` 或标识符开头的字面量,
 * `async () =&gt; {…}` 会被它当成语句片段再包一层(外层没有 await),函数就永远不会被调用 ——
 * 实测 `return (async () =&gt; 42)()` 的包装结果就是这样丢的。`(async () =&gt; …)()` 是表达式,
 * 服务端不匹配任一判据,原样求值,正好是我们要的。</li>
 * </ul>
 *
 * <p>
 * 为什么包装层必须与它同形:{@code execute_js} 的求值约定是**求值结果是函数就先调用它**
 * (服务端 `normalizeScript` 也按这个约定补包装)。包装层只有同形,才能保证「谁被调用」不变。
 */
const FUNCTION_LIKE = /=>|\bfunction\b|^\s*async\b/;
const BRACKETED = /^\s*\(/;
/**
 * 与服务端同款、但认得「同一行里以 `;` 分隔」的语句片段
 *
 * <p>
 * 服务端的 `RETURN_STATEMENT` 只认行首/`;`/`}`/换行之后(且 `\s*` 不含换行,单行里就失效)。
 * 单行写 `const f = () => 1; return f();` 时它既"像函数"又"含 return",服务端会按**函数**处理,
 * 那整段就被当成函数定义、求值结果丢不回来。这里把「函数字面量优先」也照搬过来,只在
 * **不含函数特征**时才补语句包装 —— 与服务端判据保持一致,避免我们比它更激进。
 */
const STATEMENT_FRAGMENT = /(^|[;{}])\s*return[\s;(]/;

/**
 * body 自己会不会返回 Promise
 *
 * <p>
 * 只有这几种情况需要包装层也是 async,才能把 Promise 解开再落进页内副本:
 * ①body 里写了 `await`;②body 是 `async` 函数字面量;③语句片段里有 `return <调用>`
 * (调用可能返回 Promise,异步函数尤其常见 —— 实测 `return (async () => {...})()` 就是这一支)。
 * 只判断 `await` 会漏掉 ②③:那样页内副本存的是一个 Promise 对象,JSON 化之后变成 `{}`,回捞拿不到值。
 */
const NEEDS_AWAIT = /\bawait[\s([/]|^\s*async\b/;
/**
 * 语句片段里的 `return` 会不会带出一个 Promise
 *
 * <p>
 * 判据刻意放宽到「return 后面跟着一次调用」:`return (async () =&gt; 42)()`、`return fetch(x)` 这类
 * 拿回来的都是 Promise。判窄了(只认 `async`/`.then(`)会让页内副本存下一个 Promise 对象,JSON 化
 * 之后变成 `{}`,回捞路径拿不到值 —— 实测 `stmts-return-async` 就是这么丢的。
 * 反过来把它当 async 的代价很小:包装层多 await 一次,普通值 await 之后还是自己。
 */
const RETURN_ASYNC_CALL = /\breturn\s*\(?\s*(async\b|\w[\w$.]*\s*\(|\w[\w$.]*\s*\.\s*(then|catch|finally)\b)/;

type Shape = 'expr' | 'function' | 'statements';

function shapeOf(body: string): Shape {
  const script = body.trim();
  const statement = STATEMENT_FRAGMENT.exec(script);
  const func = FUNCTION_LIKE.exec(script);
  // `return` 出现在函数特征**之前**才算语句片段:`() => { return 6 * 7; }`、`async () => { …; return x; }`
  // 里的 return 属于函数体内部,不能据此把整段当成语句。
  if (statement && (!func || statement.index < func.index)) return 'statements';
  if (func) return 'function';
  return 'expr';
}

/** 函数字面量的求值:是 async 的就得 await,否则拿回的是 Promise 对象而不是它的值 */
function functionCall(script: string): string {
  // 补括号:服务端只认 `(` / 标识符开头的字面量,`async () => {…}` 不补会被它当语句片段再包一层
  const parenthesized = BRACKETED.test(script) ? script : `(${script})`;
  return /^\s*async\b/.test(script) ? `(await ${parenthesized}())` : `(${parenthesized})()`;
}

function isAsyncBody(body: string): boolean {
  const script = body.trim();
  if (NEEDS_AWAIT.test(script)) return true;
  return STATEMENT_FRAGMENT.test(script) && RETURN_ASYNC_CALL.test(script);
}

/** 结果暂存在页面全局名字上;探测脚本按同一个名字读回,所以两者必须成对生成 */
const STORE_KEY = '__dsbRecovery';

/** 页内副本的宿主与键:宿主显式声明,探测与包装共用同一份写法,不依赖两边各自拼对 */
function storeRef(token: string): string {
  return `globalThis.${STORE_KEY}[String.fromCharCode(123,123)+'${token}'+String.fromCharCode(125,125)]`;
}

/**
 * 把 body 包一层:先把结果写进页内副本,再**把它作为求值结果返回**
 *
 * <p>
 * <b>为什么需要它</b>:{@code execute_js} 撞上 Playwright 事件泵的伪故障时,服务端只发一次
 * 回执、返回值直接丢(实测一个下午丢了 5 次)。脚本本身可能已经执行完了,只是回执在路上被噪声吃掉。
 * 把结果先落进页面,插件就能用一条**自己生成的、只读的**探测命令把副本读回来 —— 既不重跑脚本,
 * 也把「已经执行但结果丢了」从不确定变成确定。
 *
 * <p>
 * <b>为什么整段必须是函数字面量</b>:这个服务端的求值约定是「是函数就先调用」。实测把包装写成
 * 立即调用表达式 `(() =&gt; {…})()` 时,求值结果一律是 {@code undefined} —— 页内副本是对的(探针能
 * 读回来),但回执里根本没有 `result`,于是正常路径也变成"结果丢了"。写成函数字面量就没有这个问题。
 *
 * <p>
 * 包装里的属性名刻意用 `String.fromCharCode(123,123)` 拼出来,而不是直接写 `{{token}}`:
 * 服务端 {@code applyVars} 会对**整段脚本**做 `{{key}}` 替换,字面量写进去的话,一旦调用方
 * 正好有同名变量,包装层自己就会被替换坏。运行时拼出来的名字不在脚本文本里,替换碰不到它;
 * 调用方脚本里的 `{{VAR}}` / `${VAR}` 语义因此完全不受影响。
 */
export function wrapScript(body: string, token: string): string {
  const store = storeRef(token);
  const script = body.trim();
  const shape = shapeOf(script);
  const async = isAsyncBody(script);
  // 内层取值表达式:三种形状各自「求值即结果」。含 `async` 的函数字面量也必须走 functionCall ——
  // 普通地包一层会写成"看起来像函数、其实没有 await"的形式,服务端会把它当语句片段再包一层,
  // 外层没有 await,函数就永远不被调用(实测就是这里把结果丢成 undefined 的)。
  // 语句片段同样要按 async 决定内层形状:否则 `return call()` 这类写法会在副本里留下一个 Promise
  // 对象(JSON 化之后就是 `{}`),回捞路径拿不到值。
  const value = shape === 'statements'
    ? `(${async ? 'async ' : ''}() => {${script}})()`
    : shape === 'function' ? functionCall(script) : script;
  const awaited = async && !/^\s*await\b/.test(value) ? `await ${value}` : value;
  // 显式写 globalThis:页面里没有提前声明这个全局名,裸标识符赋值在严格模式下会直接抛 ReferenceError
  const head = `globalThis.${STORE_KEY}=globalThis.${STORE_KEY}||{};`;
  const record = (name: string) => `${store}={ok:true,value:${name}===undefined?null:${name}};`;
  const caught = `catch (e) { ${store}={ok:false,value:null,error:{name:e&&e.name,message:e&&e.message?String(e.message):String(e)}}; throw e; }`;
  // async 包装层里统一 await 一次:语句片段的内层已经 async(拿到 Promise),函数字面量也在这里解 Promise
  if (async) return `async () => { ${head} try { const v = ${awaited}; ${record('v')} return v; } ${caught} }`;
  return `() => { ${head} try { const v = ${value}; ${record('v')} return v; } ${caught} }`;
}

/**
 * 回捞探针
 *
 * <p>
 * 这条脚本是插件**自己生成**的,内容只读且按构造幂等:除了读一个页面属性什么都不做,所以重发它
 * 不会重复执行任何副作用。伪故障发生在服务端,重发一次通常就穿过去了。
 */
export function recoveryProbe(token: string): string {
  return `() => { const c = ${storeRef(token)}; if (!c) { return { found: false }; } return { found: true, outcome: c }; }`;
}

export function recoveryToken(): string {
  return randomBytes(6).toString('hex');
}

/** 与分类结果一起回给模型的恢复指引 */
const SPURIOUS_RECOVERY = ['先看 urlAfter 判断动作到底生效没有,再读 dsb_state;不要凭回执猜',
  '需要重发时,确认无副作用后带 retryOnSpurious:true'] as const;

/**
 * 伪故障结构化分类
 *
 * <p>
 * 服务端只把 {@code spuriousDispatch:true} / {@code errorCode:SPURIOUS_DISPATCH} 写在 data 里,
 * 模型得自己从中文 msg 里看出来「这是事件泵噪声、不是脚本报错」。这里把它翻译成三个直接可用的
 * 结论字段:{@code spurious}(是伪故障)、{@code outcomeUnknown}(结果不可知)、
 * {@code executedLikely}(脚本大概率已经执行)。{@code retryBudget.commandAttempts>=3} 说明
 * 服务端的共享额度已经用完,那就不是瞬时抖动,不能再原地连点。
 */
export function classify(result: Envelope): Envelope {
  const data = (result.data && typeof result.data === 'object' && !Array.isArray(result.data) ? result.data : {}) as Params;
  const spurious = result.ok === false && (data.spuriousDispatch === true || data.errorCode === 'SPURIOUS_DISPATCH');
  if (!spurious) return result;
  const budget = (data.retryBudget && typeof data.retryBudget === 'object' && !Array.isArray(data.retryBudget) ? data.retryBudget : undefined) as Params | undefined;
  const attempts = typeof budget?.commandAttempts === 'number' ? budget.commandAttempts : 0;
  const annotated: Envelope = { ...result, data: { ...data, spurious: true, outcomeUnknown: true, executedLikely: true,
    recovery: [...SPURIOUS_RECOVERY] } };
  if (attempts >= 3) {
    (annotated.data as Params).retryExhausted = true;
    (annotated.data as Params).recovery = [
      `服务端命令已尝试 ${attempts} 次(含内部重试),不是瞬时抖动:别原地连点。`,
      '先读 dsb_state 与 urlAfter 确认真实状态;确实需要重发时,确认无副作用后再带 retryOnSpurious:true',
      '如果这个脚本有副作用,改用 dsb_job 观测或人工确认后单独重发',
    ];
  }
  return annotated;
}
