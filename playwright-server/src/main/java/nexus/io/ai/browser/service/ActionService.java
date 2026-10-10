package nexus.io.ai.browser.service;

import nexus.io.tio.utils.validator.ParameterValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.actions.registry.CommandFlags;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.ai.browser.actions.registry.TrackedArgs;

import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;

/**
 * 方法分发与批量指令执行器
 *
 * <p>
 * 对外只有一个端点 {@code POST /playwright/command},请求体是
 * {@code {"id":123,"method":"get_browser_state","params":{}}}。{@link #execute}
 * 负责把 {@code method} 分发到 {@link CommandTable},批量调用走 {@code method=commands}:
 *
 * <pre>
 * {"id":123,"method":"commands","params":{
 *    "stopOnError":false,
 *    "commands":[{"click_element_by_index":{"index":12}},{"get_browser_state":{}}]}}
 * </pre>
 *
 * <p>
 * 批量里每一步的结果都在 data.results 里,所以一个批次可以同时做动作和读取(点击 → get_browser_state →
 * 再点击),模型一次推理就能拿到全部观察结果。
 */
public class ActionService {

public static final Set<String> PAGE_CHANGING = CommandFlags.namesWith(CommandFlags.Flag.PAGE_CHANGING);

  // 命令数组的上限移到 PlaywrightService.commandMaxPerBatch()(可配,见 browser.command.maxPerBatch)

  /**
   * 遇到 Playwright 事件泵的「伪故障」时可以**放心重发**的命令
   *
   * <p>
   * 只收三类:①只读(读回的是同一个页面状态,重发没有任何副作用);②幂等导航与等待(去同一个地址、
   * 再等一次);③覆盖式落盘(截图 / PDF 写的是同一个文件)。判据来自实现语义,不是猜的。
   *
   * <p>
   * <b>动作类一律不在名单里</b> —— 点击 / 输入 / 提交 / `execute_js` 都可能已经生效,重发会造成重复提交。
   * 它们照样会被识别成 {@code SPURIOUS_DISPATCH}(见 {@link ActionError}),只是不自动重发,由调用方
   * 读完页面状态再决定。{@code start} / {@code close} / {@code upload_file} / {@code set_cookie} 同理不收。
   *
   * <p>
   * 「不在名单里」不等于「永远不会重发」:调用方可以用 {@code retryOnSpurious:true} **按调用点**声明
   * 这一次重发无害(见 {@link #retrySafeFor}),这是给「伪故障其实发生在命令发出去之前」那一支留的口子。
   */
public static final Set<String> SPURIOUS_RETRY_SAFE = CommandFlags.namesWith(CommandFlags.Flag.RETRY_SAFE);

  /**
   * 伪故障最多重发几次(含首次),可配 {@code browser.command.spuriousMaxAttempts}
   *
   * <p>
   * 默认 3 次:3 次以内是「消息泵里正在派发的那一条」,再多就是别的问题了。写法与其它上限一致,
   * 免得同一类数字有的能配、有的只能改代码。
   */
  private static int spuriousMaxAttempts() {
    return positiveInt(ChromeBrowser.config("browser.command.spuriousMaxAttempts"), DEFAULT_SPURIOUS_MAX_ATTEMPTS);
  }

  private static final int DEFAULT_SPURIOUS_MAX_ATTEMPTS = 3;

  /** 两次重发之间的间隔(毫秒),可配 {@code browser.command.spuriousRetryDelayMs} */
  private static long spuriousRetryDelayMs() {
    return positiveInt(ChromeBrowser.config("browser.command.spuriousRetryDelayMs"),
        (int) DEFAULT_SPURIOUS_RETRY_DELAY_MS);
  }

  private static int positiveInt(String configured, int fallback) {
    if (configured == null || configured.isBlank()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(configured.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * 伪故障之后**效果可以直接读回来核对**的命令
   *
   * <p>
   * 页签操作是唯一这类:它的结果就摆在 {@link PlaywrightService#getTabs} 里,读一次就有事实,
   * 不必把结论留给调用方猜(见 {@link #verifyTabsAfterSpurious})。其余动作类命令(点击 / 输入 / 提交)
   * 的效果只能靠页面语义判断,不属于这里。
   */
static final Set<String> SPURIOUS_VERIFIABLE = CommandFlags.namesWith(CommandFlags.Flag.VERIFIABLE);
  /** 两次重发之间的间隔默认值:伪故障是「消息泵里正在派发的那一条」引起的,挪开一点点就够了 */
  private static final long DEFAULT_SPURIOUS_RETRY_DELAY_MS = 120;

  private final PlaywrightService svc;

  public ActionService() {
    this(Aop.get(PlaywrightService.class));
  }

  public ActionService(PlaywrightService svc) {
    this.svc = svc;
  }

  private void attachCapture(RespBodyVo result, Long id, String method) {
    if (id == null || !result.isOk() || !PAGE_CHANGING.contains(method))
      return;
    BrowserInstance inst = svc.getInstance(id);
    if (inst == null)
      return;
    // 自动截图是**附带的取证**,它失败绝不该把一条已经成功的命令翻成失败:实测点下载类按钮之后
    // 页面正在消失,截图/取证会抛 object-does-not-exist。这里兜一层,把原因如实写进回执就好。
    Kv capture;
    try {
      capture = svc.capture(inst);
    } catch (RuntimeException e) {
      capture = Kv.by("screenshot_error", PlaywrightService.briefMessage(e.getMessage()));
    }
    Object data = result.getData();
    Kv merged = data instanceof Kv ? (Kv) data : Kv.by("result", data);
    merged.set(capture);
    result.setData(merged);
  }

  /**
   * 把「传了但一次也没被用过」的参数写进回执
   *
   * <p>
   * 命令表里的执行体各自读自己认识的键,没有一份声明式的参数清单,所以拼错的参数名会被**静默丢弃**:
   * 回执照样 {@code ok:true},调用方以为自己的意图生效了。
   *
   * <p>
   * 这里**只加说明、不改结论**——命令该成功还是成功。因为动作类命令一旦被翻成失败,调用方很可能
   * 重发,而重发等于重复点击、重复提交(与 {@link #dispatchWithSpuriousRetry} 里「不确定」那段是
   * 同一条理由)。把线索放进回执让人一眼看穿,与 {@code start} 的 {@code engineWarning} 是同一种处理。
   */
  private void attachUnknownParams(RespBodyVo result, TrackedArgs args, String method) {
    Set<String> unread = args.unreadKeys();
    if (unread.isEmpty()) {
      return;
    }
    Object data = result.getData();
    Kv merged = data instanceof Kv ? (Kv) data : new Kv();
    merged.set("unknownParams", new ArrayList<>(unread));
    merged.set("unknownParamNote", TrackedArgs.unknownParamNote(method, unread, args.readKeys()));
    result.setData(merged);
  }

  /**
   * 跑一条命令,并对 Playwright 的「伪故障」做有限重发
   *
   * <p>
   * 起因是实测里最贵的一类假故障:一次任务里 {@code execute_js}、{@code get_form_state}、
   * {@code get_element_box}、{@code go_to_url}、{@code wait_for_idle}、自动截图会**成串**报
   * {@code Object doesn't exist: response@… / request@…},报错的对象与本次命令毫不相干,而页面完全正常。
   * 根因在 Playwright Java:上下文事件分发在按 guid 查对象时,碰到已释放的对象就抛,而这一抛发生在消息泵里,
   * 会砸在「当时正在等待回复的那次 API 调用」上(详见 {@link ActionError#SPURIOUS_DISPATCH})。
   *
   * <p>
   * 服务端能做的就两件事:①对**重发无害**的命令自己重发({@link #SPURIOUS_RETRY_SAFE}),把噪声吃掉;
   * ②其余命令照旧如实报告,并把「可能已经生效」说清楚。重发过一次的回执里会多一个
   * {@code data.spuriousRetry},便于事后统计这类噪声到底有多少。
   *
   * <p>
   * <b>2026-09-27 补充:伪故障分两支,别一律当成「可能已生效」</b>。实测在 TradingView 这类重度 SPA 上,
   * 一个动作命令回 {@code [SPURIOUS_DISPATCH]} 时有很大比例属于「命令压根没发出去就撞上了噪声」
   * ——此时重发完全无害,却因为「动作类一律不自动重发」而被挡在门外,调用方只能自己去改坐标点击绕。
   * 现在的折中是**按调用点声明**({@code params.retryOnSpurious:true}),而不是把动作类整体放进名单:
   * 判据交给最清楚语义的调用方,服务端不替它猜。
   *
   * @param method 命令名,决定要不要重发
   * @param call   真正执行命令的动作;每次重发都会重新调用一次
   */
  static RespBodyVo dispatchWithSpuriousRetry(String method, java.util.function.Supplier<RespBodyVo> call) {
    return dispatchWithSpuriousRetry(method, null, call);
  }

  /**
   * 同上,{@code params} 用来读调用方**显式声明**的重发许可({@code retryOnSpurious})
   *
   * @param params 命令参数;为 {@code null} 时按「没有声明任何许可」处理
   */
  static RespBodyVo dispatchWithSpuriousRetry(String method, JSONObject params,
      java.util.function.Supplier<RespBodyVo> call) {
    int maxAttempts = retrySafeFor(method, params) ? spuriousMaxAttempts() : 1;
    if ((method.equals("screenshot") || method.equals("get_element_screenshot"))
        && params != null && Boolean.TRUE.equals(params.getBoolean("force"))) maxAttempts = 1;
    // 这次重发的起因:伪故障(事件泵噪声)还是页面正在导航。两者都只对**重发无害**的命令重发,
    // 但回执里必须标出到底踩的是哪一个,事后统计与排查才不会张冠李戴。
    String retryReason = null;
    // A single monotonic deadline and retry allowance includes nested screenshot helpers.
    try (RetryBudget budget = RetryBudget.open(30000)) {
      for (int attempt = 1;; attempt++) {
        RespBodyVo result;
        try { result = call.get(); }
        catch (RuntimeException error) {
          String reason = retryReason(error.getMessage());
          if (attempt >= maxAttempts || reason == null || !budget.retry()) throw error;
          retryReason = reason;
          sleepBeforeRetry(error.getMessage());
          continue;
        }
        if (result != null && !result.isOk() && attempt < maxAttempts) {
          String reason = retryReason(result.getMsg());
          if (reason != null && budget.retry()) {
            retryReason = reason;
            sleepBeforeRetry(result.getMsg());
            continue;
          }
        }
        if (result != null) {
          Kv data = result.getData() instanceof Kv ? (Kv) result.getData() : new Kv();
          data.set("retryBudget", Kv.by("attempts", budget.retries() + 1).set("retries", budget.retries())
              .set("commandAttempts", attempt).set("elapsedMs", budget.elapsedMs()).set("remainingMs", budget.remainingMs()));
          if (budget.retries() > 0 && retryReason != null) {
            if ("navigating".equals(retryReason)) {
              data.set("pageNavigatingRetry", Kv.by("attempts", budget.retries() + 1)
                  .set("errorCode", ActionError.PAGE_NAVIGATING)
                  .set("note", "读取期间页面正在导航/重建；服务端已按可重发名单重试，详见 retryBudget。"));
            } else {
              data.set("spuriousRetry", Kv.by("attempts", budget.retries() + 1)
                  .set("errorCode", ActionError.SPURIOUS_DISPATCH).set("note", "命令与内部截图共用重试额度，含首次最多3次；详见 retryBudget。"));
            }
          }
          // Preserve non-Kv successful payloads; commands returning Kv receive additive diagnostics.
          if (result.getData() == null || result.getData() instanceof Kv) result.setData(data);
        }
        return result;
      }
    }
  }

  /**
   * 这条命令这次可以重发吗
   *
   * <p>
   * 判据有两条,满足任一条即放行:
   *
   * <ol>
   * <li>命令本身在 {@link #SPURIOUS_RETRY_SAFE} 名单里(只读 / 幂等导航 / 覆盖式落盘 / 等待);</li>
   * <li>调用方在参数里写了 {@code retryOnSpurious: true} —— <b>这是给动作类命令留的口子</b>。
   * 默认不认它({@code execute_js} 一开始就是这么设计的:脚本可能有副作用,重发等于再执行一次),
   * 但伪故障里有一大支是「命令还没发出去就撞上了噪声」,那种情况下重发是安全的,而调用方比服务端
   * 更清楚这一次是不是写操作。实测场景:同一个 {@code click_element_by_selector} 一会儿成功一会儿报
   * 伪故障,而页面完全正常 —— 只读诊断过、确认没生效之后,带上这个开关重发是最省事的解法。</li>
   * </ol>
   *
   * <p>
   * 服务端不替调用方猜:动作类命令**默认仍然一次都不重发**,这一点由
   * {@code SpuriousDispatchRetryTest} 钉着。
   */
  static boolean retrySafeFor(String method, JSONObject params) {
    if (SPURIOUS_RETRY_SAFE.contains(method)) {
      return true;
    }
    return callerAllowsSpuriousRetry(params);
  }

  /** 调用方有没有显式声明「这次重发无害」 */
  static boolean callerAllowsSpuriousRetry(JSONObject params) {
    return params != null && Boolean.TRUE.equals(params.getBoolean("retryOnSpurious"));
  }

  /**
   * 这次失败值不值得重发
   *
   * @return {@code "spurious"}(事件泵伪故障)或 {@code "navigating"}(页面正在导航/重建);都不沾返回 null
   */
  private static String retryReason(String message) {
    if (ActionError.isSpuriousDispatch(message)) {
      return "spurious";
    }
    if (ActionError.isPageNavigating(message)) {
      return "navigating";
    }
    return null;
  }

  /**
   * 重发前的等待
   *
   * <p>
   * 伪故障只要挪开一点点 —— 噪声是消息泵里**正在派发的那一条**;页面导航则要给页面时间回来,
   * 按 {@link ActionError#retryAfterMs} 的建议值(1 秒)等,但至少 200 毫秒,免得贴着导航抢跑。
   */
  private static void sleepBeforeRetry(String message) {
    long ms = ActionError.isPageNavigating(message)
        ? Math.max(200, Math.min(ActionError.retryAfterMs(ActionError.PAGE_NAVIGATING), 1_000))
        : spuriousRetryDelayMs();
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      // 被中断就把中断标志还回去,别在这里把调用方的语义改掉
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 执行一条命令
   *
   * <p>
   * 这里是**所有**命令的唯一出口,所以失败回执的公共装饰(补上"现在页面在哪")也收在这里:
   * 各类失败分支有七八处,逐个去加只会漏;包一层就没有漏网的。
   *
   * <p>
   * 注意 {@code download_image} **不在** {@link #SPURIOUS_RETRY_SAFE} 里:它算落盘,但每次落的是
   * **新编号的文件**(不是覆盖同一个),自动重发会留下重复文件 —— 与"覆盖式落盘"那几条的判据不同。
   *
   * @param id     任务 ID,start 时可以为空
   * @param method 命令名,见 {@link CommandTable}
   * @param params 命令参数,可以为空
   */
  public RespBodyVo execute(Long id, String method, JSONObject params) {
    BrowserInstance instance = id == null ? null : svc.getInstance(id);
    if (instance == null || TASK_SCOPE.get() == instance) {
      // 没有实例(例如 start 本身)或已经在同一任务的执行域里(批量中的一步):直接跑
      return executeLocked(id, method, params);
    }

    // 同一任务的命令串行执行:HTTP 线程池会把同一个 id 的请求并发送进来,而 Playwright 的
    // Connection 事件泵不能被并发驱动(见 BrowserInstance#commandLock 的注释)。
    boolean acquired;
    try {
      acquired = instance.commandLock.tryLock(SERIALIZE_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      return commandBusyFailure(method);
    }
    TASK_SCOPE.set(instance);
    try {
      return executeLocked(id, method, params);
    } finally {
      TASK_SCOPE.remove();
      instance.commandLock.unlock();
    }
  }

  /** 同一个任务上已有命令在跑:这条没开始执行,重发是安全的。 */
  static RespBodyVo commandBusyFailure(String method) {
    RespBodyVo busy = RespBodyVo.fail(method + " 未执行：同一个任务上已有命令正在执行，等满 "
        + SERIALIZE_WAIT_MS + " 毫秒仍未轮到（[" + ActionError.COMMAND_TIMEOUT + "]）");
    busy.setData(Kv.by("errorCode", ActionError.COMMAND_TIMEOUT).set("retryable", true)
        .set("started", false).set("retryAfterMs", 1000).set("serialized", true)
        .set("note", "同一个任务的命令是**串行**执行的（Playwright 的 Connection 不能被并发驱动）。"
            + "这条命令没有开始执行，稍后重发即可；长批量进行中时，轮询类命令会排在它后面"));
    return busy;
  }

  private RespBodyVo executeLocked(Long id, String method, JSONObject params) {
    RespBodyVo result = runBounded(id, method, params);
    if (result != null && !result.isOk()) {
      // 超时回执**不要再补页面上下文**:补它要再发一次 Playwright 调用,而此刻正是「底层调不动」
      // 的时候 —— 那一下会把刚拿到结论的 HTTP 线程重新挂死,把兜底的意义全抹掉。
      if (!ActionError.COMMAND_TIMEOUT.equals(errorCodeOf(result))) {
        attachPageContext(result, id);
      }
    }
    return result;
  }

  /** 失败回执里已写的错误码(用于少数需要按码分支的公共装饰) */
  private static String errorCodeOf(RespBodyVo result) {
    Object data = result.getData();
    if (data instanceof Kv kv) {
      String code = kv.getStr("errorCode");
      if (code != null && !code.isBlank()) {
        return code;
      }
    }
    return ActionError.code(result.getMsg());
  }

  /**
   * 命令级 wall-clock 兜底:给每条命令一个「一定会回来」的期限
   *
   * <p>
   * <b>为什么还需要它</b>:{@code execute_js} 已经有页内超时兜底,但那只在页面 JS 还能跑时有效 ——
   * 渲染进程卡死、CDP 半死时连页内定时器都不跑(实测:一个永不 resolve 的 Promise 让请求挂过 120 秒
   * 仍无响应)。这一层在工作线程上 {@code get(timeout)},拿不到就如实回一句「结果未知」,
   * 而不是让调用方无限等。
   *
   * <p>
   * <b>为什么用有界线程池而不是「每条命令一个新线程」</b>:超时的命令**取消不掉**(Playwright 的调用
   * 可能不响应中断),线程会一直留到它自己返回。有界池把这种「卡住的命令」限制在
   * {@code browser.command.maxStuck} 条以内:池子被卡满之后,后面的命令会在队列里等到自己的期限,
   * 然后明确回一句「服务端繁忙、本次没有开始执行」—— 那是**可以安全重试**的结论,
   * 与「结果未知」区分开,不会误导调用方。
   */
  /**
   * 本来就设计成要等的命令:它们的时间由**自己的参数**决定,不能用默认兜底一刀切
   *
   * <p>
   * 例:{@code wait_for_idle(timeoutSeconds=300)} 会被 90 秒的默认预算判死;异步批次最多 200 步、
   * OCR 自己的超时就是 120 秒。这些命令改用一个宽松得多的天花板
   * ({@code browser.command.hardTimeoutMs},默认 15 分钟)—— 目的是「不永远挂死」,不是「快」。
   */
  // 包级可见是为了让 CommandSetInvariantsTest 能断言「这些名字都还在命令表里」——
  // 这类集合是逐条手写的,漏改一条就是静默的行为变化(某命令不再自动截图/不再重试),
  // 而命令表本身看起来完全正常。改成 CommandSpec 派生之前,这条不变式必须先立起来。
static final Set<String> LONG_RUNNING = CommandFlags.namesWith(CommandFlags.Flag.LONG_RUNNING);

  private RespBodyVo runBounded(Long id, String method, JSONObject params) {
    long limit = LONG_RUNNING.contains(method)
        ? PlaywrightService.commandHardTimeoutMs()
        : PlaywrightService.commandTimeoutMs();
    return awaitWithLimit(limit, method, () -> run(id, method, params));
  }

  /**
   * 「有界等待」的机制本身
   *
   * <p>从 {@link #runBounded} 里抽出来是为了能直接单测这个机制(见 {@code CommandTimeoutTest}):
   * 要验证「超时会回来」「返回体字段对不对」,不必真的让一个浏览器卡住 90 秒。
   */
  /**
   * 标记「当前线程已经在命令池里跑」。
   *
   * <p>
   * 批量({@code commands})本身是池里的一个任务,它每一步又会经过这里。照旧再往同一个池里提交的话,
   * 一个在飞的批量要占两个线程:池默认 24 线程,12 个并发批量就吃满;池满时所有外层任务都堵在
   * {@code future.get()} 上,而它们的内层步骤在队列里排在外层后面 —— 全体一起等满 15 分钟硬超时,
   * 回执还写着「结果未知、不要重发」,可队列里那些步骤**之后照样会跑**(外层超时并不取消它们)。
   */
  private static final ThreadLocal<Boolean> IN_COMMAND_POOL = ThreadLocal.withInitial(() -> Boolean.FALSE);

  /**
   * 「当前线程正在为哪个任务执行命令」。
   *
   * <p>
   * 批量整批持有该任务的串行锁,而它内部的每一步可能跑在**派生线程**上(见 {@link #awaitWithLimit}),
   * 派生线程并不"重入"外层线程的锁。靠这个标记把"同一任务的执行域"传下去,步骤才不会被自己的外层
   * 挡在锁外面等成一条「服务端忙」。
   */
  private static final ThreadLocal<BrowserInstance> TASK_SCOPE = new ThreadLocal<>();

  /** 同一个任务的命令最多等多久再放弃(毫秒):占着 HTTP 工作线程干等,比明说「忙」更糟。 */
  private static final long SERIALIZE_WAIT_MS = 5000;

  static RespBodyVo awaitWithLimit(long limitMs, String method,
      java.util.function.Supplier<RespBodyVo> body) {
    if (limitMs <= 0) {
      return body.get();
    }
    AtomicBoolean started = new AtomicBoolean(false);
    Future<RespBodyVo> future;
    if (Boolean.TRUE.equals(IN_COMMAND_POOL.get())) {
      // 已经在池里(批量中的一步):改用一次性线程。
      // 既不占共享池(不会和外层互相堵死),又保留「这一步自己的超时兜底」——
      // 直接内联跑会丢掉内层超时,等于悄悄改变了每一步的语义。
      //
      // 任务执行域要跟着传下去:派生线程不重入外层线程的锁,不传的话每一步都会在
      // 「同一任务串行锁」外面等到超时,整批全废。
      BrowserInstance inheritedScope = TASK_SCOPE.get();
      FutureTask<RespBodyVo> step = new FutureTask<>(() -> {
        started.set(true);
        if (inheritedScope != null) {
          TASK_SCOPE.set(inheritedScope);
        }
        try {
          return body.get();
        } finally {
          TASK_SCOPE.remove();
        }
      });
      Thread thread = new Thread(step, "dsb-command-step");
      thread.setDaemon(true);
      thread.start();
      future = step;
    } else {
      try {
        BrowserInstance inheritedScope = TASK_SCOPE.get();
        future = commandPool().submit(() -> {
          started.set(true);
          IN_COMMAND_POOL.set(Boolean.TRUE);
          if (inheritedScope != null) {
            TASK_SCOPE.set(inheritedScope);
          }
          try {
            return body.get();
          } finally {
            TASK_SCOPE.remove();
            IN_COMMAND_POOL.remove();
          }
        });
      } catch (RejectedExecutionException overloaded) {
        // 池与队列都满了:明确回一条「没开始执行、可以重发」的回执。
        // 以前池是无界队列、永远不会走到这里 —— 也正因为无界,过载时调用方只会一直干等,
        // 连一点「服务端忙不过来了」的信号都拿不到。
        return commandOverloadedFailure(method, limitMs);
      } catch (RuntimeException rejected) {
        // 池已关闭等极端情况:退回同步执行,宁可没有兜底也不要让命令发不出去
        return body.get();
      }
    }
    try {
      return future.get(limitMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      return commandTimeoutFailure(method, limitMs, started.get());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return commandTimeoutFailure(method, limitMs, started.get());
    } catch (java.util.concurrent.ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(cause == null ? failed.getMessage() : cause.getMessage(), cause);
    }
  }

  /** 过载(池与队列都满):这条命令没开始执行,重发是安全的。 */
  static RespBodyVo commandOverloadedFailure(String method, long limit) {
    RespBodyVo busy = RespBodyVo.fail(method + " 未执行：服务端并发已满（线程与排队都已满），"
        + "这条命令没有开始执行（[" + ActionError.COMMAND_TIMEOUT + "]）");
    busy.setData(Kv.by("errorCode", ActionError.COMMAND_TIMEOUT).set("retryable", true)
        .set("started", false).set("retryAfterMs", 500).set("commandTimeoutMs", limit)
        .set("note", "这条命令没有开始执行，重发是安全的。持续出现说明有命令卡住了浏览器会话，"
            + "可用 browser.command.maxStuck 放宽并发，或先确认那些任务还活着"));
    return busy;
  }

  static RespBodyVo commandTimeoutFailure(String method, long limit, boolean started) {
    if (!started) {
      // 队列里等到过期 = 这条命令**根本没开始跑**,重发是安全的 —— 必须与「结果未知」区分开
      RespBodyVo busy = RespBodyVo.fail(method + " 未执行：服务端正在处理其它卡住的命令，等满 "
          + limit + " 毫秒仍未轮到它（[" + ActionError.COMMAND_TIMEOUT + "] 本次没有开始执行）");
      busy.setData(Kv.by("errorCode", ActionError.COMMAND_TIMEOUT).set("retryable", true)
          .set("started", false).set("retryAfterMs", 2000).set("commandTimeoutMs", limit)
          .set("note", "这条命令没有开始执行，重发是安全的；持续出现说明有命令卡住了浏览器会话，"
              + "先用 get_page_snapshot 看看这个任务还活着没有，必要时 close + start 重建任务"));
      return busy;
    }
    RespBodyVo timeout = RespBodyVo.fail(method + " 超时：" + limit + " 毫秒内没有返回"
        + "（[" + ActionError.COMMAND_TIMEOUT + "] 本次结果未知：命令已经开始执行）");
    timeout.setData(Kv.by("errorCode", ActionError.COMMAND_TIMEOUT).set("retryable", false)
        .set("started", true).set("outcomeUnknown", true).set("commandTimeoutMs", limit)
        .set("note", "不要直接重发（命令可能已经生效）。先确认页面状态；若这个任务连续超时，"
            + "说明底层浏览器会话已经卡住，close + start 重建任务比继续等更快。"
            + "可用 browser.command.timeoutMs 调整这个兜底期限（0 = 关闭）"));
    return timeout;
  }

  /**
   * 有界命令池:大小即「允许多少条命令同时卡住」,线程是守护线程,服务退出不需要额外收尾。
   *
   * <p>
   * 队列也是有界的(见 {@link #commandPool()}):无界队列会让过载表现为「一直等」而不是
   * 「明确告知忙」,调用方拿不到任何可判断的信号。
   */
  private static volatile ExecutorService COMMAND_POOL;

  private static ExecutorService commandPool() {
    ExecutorService pool = COMMAND_POOL;
    if (pool != null) {
      return pool;
    }
    synchronized (ActionService.class) {
      if (COMMAND_POOL == null) {
        int size = Math.max(2, PlaywrightService.commandMaxStuck());
        ThreadFactory factory = runnable -> {
          Thread thread = new Thread(runnable, "dsb-command");
          // 守护线程:卡死的命令不该拦住进程退出
          thread.setDaemon(true);
          return thread;
        };
        // 队列有界:池满 + 队列满 → 拒绝 → 回一条「没开始执行、可重发」的回执。
        // 用无界队列时过载只会表现为「一直等」,调用方半点信号都拿不到。
        int queueCapacity = Math.max(8, size);
        COMMAND_POOL = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity), factory);
      }
      return COMMAND_POOL;
    }
  }

  /**
   * 失败回执里补上「现在页面在哪」
   *
   * <p>
   * 只补**没有的**字段:命令自己已经写了 {@code url} 的(例如 {@code get_url}、导航类)不覆盖。
   * 读不到就静默跳过 —— 这只是附加信息,不该让一条失败回执变成异常。
   */
  private void attachPageContext(RespBodyVo result, Long id) {
    if (id == null) {
      return;
    }
    Kv context;
    try {
      context = svc.pageContext(id);
    } catch (RuntimeException e) {
      return;
    }
    if (context == null || context.isEmpty()) {
      return;
    }
    Object data = result.getData();
    Kv merged = data instanceof Kv ? (Kv) data : new Kv();
    if (!(data instanceof Kv) && data != null) {
      merged.set("result", data);
    }
    for (Object key : context.keySet()) {
      String name = String.valueOf(key);
      if (!merged.containsKey(name)) {
        merged.set(name, context.get(name));
      }
    }
    result.setData(merged);
  }

  /**
   * 读一份页签状态(个数 + 当前页签序号),失败就返回 {@code null}(核对不了就不硬猜)
   *
   * <p>
   * 只给 {@link #SPURIOUS_VERIFIABLE} 里的命令用:它必须足够便宜 —— 页签操作一次任务的量本来就不多。
   */
  private Kv tabState(Long id) {
    if (id == null) {
      return null;
    }
    try {
      RespBodyVo resp = svc.getTabs(id);
      if (resp == null || !resp.isOk() || !(resp.getData() instanceof Kv)) {
        return null;
      }
      Object tabs = ((Kv) resp.getData()).get("tabs");
      if (!(tabs instanceof List)) {
        return null;
      }
      List<?> list = (List<?>) tabs;
      int currentIndex = -1;
      for (int i = 0; i < list.size(); i++) {
        Object item = list.get(i);
        if (item instanceof Kv && Boolean.TRUE.equals(((Kv) item).getBoolean("current"))) {
          currentIndex = i;
        }
      }
      return Kv.by("tabCount", list.size()).set("currentIndex", currentIndex);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 伪故障之后,把「动作到底生效没有」从猜测变成事实(目前只有页签类命令做得到)
   *
   * <p>
   * <b>为什么要有这个</b>:实测(2026-10){@code new_tab} 报伪故障时,新页签其实**已经建好**,
   * 只是页签没有切过去 —— 而回执只说「无法判断本次是否已生效」,调用方接着就把输入打进了旧页签
   * (真实事故,不是推测)。页签状态摆在那里,读一次就能给结论:
   *
   * <ul>
   * <li>页签数变多 → 动作已生效:按成功返回,并提示「切过去」这半段不一定完成、**不要重发**
   * (重发会多开一个页签);</li>
   * <li>页签数没变 → 这次确实没生效:回执直接给 {@code retryable:true},重发是安全的;</li>
   * <li>读不回来(没登录 / 实例没了)→ 返回 {@code null},调用方走原来的「不确定」分支。</li>
   * </ul>
   *
   * @return 已经能下定论时的响应;下不了结论时返回 {@code null}
   */
  private RespBodyVo verifyTabsAfterSpurious(Long id, String method, Kv tabsBefore, String detail) {
    if (tabsBefore == null || !SPURIOUS_VERIFIABLE.contains(method)) {
      return null;
    }
    Kv tabsNow = tabState(id);
    if (tabsNow == null) {
      return null;
    }
    String effect = tabEffect(tabsBefore, tabsNow);
    if (effect == null) {
      return null;
    }
    int before = asInt(tabsBefore.get("tabCount"), -1);
    int after = asInt(tabsNow.get("tabCount"), -1);
    int currentIndex = asInt(tabsNow.get("currentIndex"), -1);
    if ("effective".equals(effect)) {
      // 新建页签通常排在最后,据此给出「有没有切过去」的判断(标明是推断,不当事实)
      boolean switched = currentIndex == after - 1;
      Kv data = Kv.by("spuriousDispatch", true).set("effective", true).set("tabCountBefore", before)
          .set("tabCountAfter", after).set("currentIndex", currentIndex).set("switchedToNewTab", switched)
          .set("retryable", false).set("warning",
              method + " 底层抛的是事件泵伪故障(不是它自己报的错),核对后**动作已经生效**:页签数 "
                  + before + " → " + after + "。但「切到新页签」这半段不一定完成(当前 currentIndex="
                  + currentIndex + ",新建页签通常排在最后,据此推断 switchedToNewTab=" + switched
                  + "):请用 get_tabs 确认,需要切换用 switch_tab_by_url。**不要重发 " + method
                  + "** —— 重发会多开一个页签。");
      return RespBodyVo.ok(data);
    }
    if ("not_effective".equals(effect)) {
      Kv data = Kv.by("spuriousDispatch", true).set("effective", false).set("tabCount", after)
          .set("currentIndex", currentIndex).set("retryable", true).set("retryAfterMs", 200)
          .set("note", "已核对:页签数仍是 " + after + " 个,这次 " + method
              + " 确实没有生效。可以直接重发,不会有重复页签。");
      RespBodyVo resp = RespBodyVo.fail(method + " 失败：" + detail + "（[" + ActionError.SPURIOUS_DISPATCH
          + "] 已核对页签数未变,本次未生效,可以安全重发）");
      resp.setData(data);
      return resp;
    }
    return null;
  }

  /**
   * 纯判据:伪故障前后的页签快照说明动作生效了没有
   *
   * <p>
   * 单独抽出来是为了能直接单测(与 {@code BrowserBlindnessUpgradeTest} 里那些判据同一套做法):
   * 结论只有三种,语义必须钉死,别让它藏在私有方法的 if 里。
   *
   * @return {@code "effective"}(页签变多,动作已生效)/ {@code "not_effective"}(个数没变,确实没生效)
   *         / {@code null}(核对不了,不下结论 —— 包括页签变少这种「大概是别的命令关掉的」情形)
   */
  static String tabEffect(Kv before, Kv after) {
    if (before == null || after == null) {
      return null;
    }
    int b = asInt(before.get("tabCount"), -1);
    int a = asInt(after.get("tabCount"), -1);
    if (b < 0 || a < 0) {
      return null;
    }
    if (a > b) {
      return "effective";
    }
    if (a == b) {
      return "not_effective";
    }
    return null;
  }

  private static int asInt(Object value, int fallback) {
    return value instanceof Number ? ((Number) value).intValue() : fallback;
  }

  private RespBodyVo run(Long id, String method, JSONObject params) {
    if (method == null || method.isBlank()) {
      return RespBodyVo.fail("缺少参数 method");
    }
    if ("commands".equals(method)) {
      // 长批次可以异步跑:立刻回 jobId,再用 get_job 取结果。客户端超时不再等于「任务失败」
      if (params != null && Boolean.TRUE.equals(params.getBoolean("async"))) {
        if (id == null) {
          return RespBodyVo.fail("异步执行 commands 需要参数 id");
        }
        JSONObject jobParams = params;
        JobRegistry.Job job = JobRegistry.submit("commands", id, started -> started.result = batchExecute(id,
            jobParams, started.id));
        return RespBodyVo.ok(Kv.by("jobId", job.id).set("status", job.status).set("browserId", id)
            .set("hint", "批次已在后台执行:用 get_job 轮询结果,用 cancel_job 取消(取消会在下一步之前生效)"));
      }
      return batchExecute(id, params);
    }
    CommandTable.Executor executor = CommandTable.get(method);
    if (executor == null) {
      return RespBodyVo.fail(unknownMethodMessage(method));
    }
    // 包一层「会记录读过哪些参数」的对象:命令跑完后就能看出哪些参数被传了却一次也没用上。
    // 以前这类参数是被**静默丢弃**的——回执照样 ok:true,调用方以为自己的意图生效了。实测踩过:
    // 把 request_human_input 的 timeoutSeconds 写成 expiresInSeconds,待办按默认 300 秒建好,
    // 人还没看到请求就已经过期,而回执里一个字都没提。
    TrackedArgs args = new TrackedArgs(params);
    // 页签类命令的效果可以直接读回来(见 verifyTabsAfterSpurious):发命令前先记一份页签状态,
    // 万一撞上伪故障,就能把「到底生效没有」写成事实,而不是留给调用方猜。
    Kv tabsBefore = SPURIOUS_VERIFIABLE.contains(method) ? tabState(id) : null;
    try (RetryBudget commandBudget = RetryBudget.open(30000)) {
      RespBodyVo result = dispatchWithSpuriousRetry(method, args, () -> executor.run(svc, id, args));
      if (!result.isOk() && result.getMsg() != null) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\[([A-Z_]+)\\]").matcher(result.getMsg());
        String errorCode = match.find() ? match.group(1) : ActionError.code(result.getMsg());
        Kv detail = result.getData() instanceof Kv ? (Kv) result.getData() : new Kv();
        if (detail.get("errorCode") != null) errorCode = detail.getStr("errorCode");
        detail.set("errorCode", errorCode);
        // 可重试性与建议退避:调用方据此自动重试,不必去猜中文提示
        boolean retryable = ActionError.retryable(errorCode);
        detail.set("retryable", retryable);
        if (retryable && detail.get("retryAfterMs") == null) {
          detail.set("retryAfterMs", ActionError.retryAfterMs(errorCode));
        }
        result.setData(detail);
      }
      attachCapture(result, id, method);
      if (result.getData() instanceof Kv data) {
        Kv metrics = data.get("retryBudget") instanceof Kv existing ? existing : new Kv();
        metrics.set("attempts", commandBudget.retries() + 1).set("retries", commandBudget.retries())
            .set("elapsedMs", commandBudget.elapsedMs()).set("remainingMs", commandBudget.remainingMs());
        data.set("retryBudget", metrics);
      }
      attachUnknownParams(result, args, method);
      return result;
    } catch (ParameterValidationException e) {
      RespBodyVo invalid = RespBodyVo.fail(400, e.getMessage());
      invalid.setData(Kv.by("errorCode", ActionError.INVALID_ARGUMENT).set("retryable", false)
          .set("invalidArgument", true).set("started", false));
      return invalid;
    } catch (IllegalArgumentException e) {
      // 参数校验错(CommandTable 的 reqInt/reqStr 等):命令**根本没发出去**,不存在「可能已生效」的问题。
      //
      // 单独给一个错误码:以前这类失败与「站点/引擎拒绝了这次动作」共用 ACTION_FAILED + retryable:false,
      // 调用方分不出「我参数写错了」(改了再来)和「页面/引擎不让做」(重试也没有不同结果)——
      // 而这两件事该采取的行动完全不同。
      RespBodyVo invalid = RespBodyVo.fail(method + " 失败：" + e.getMessage());
      invalid.setData(Kv.by("errorCode", ActionError.INVALID_ARGUMENT).set("retryable", false)
          .set("invalidArgument", true).set("started", false)
          .set("note", "这条命令的参数不合法,服务端没有执行它;按提示改正参数后重发即可"));
      return invalid;
    } catch (Exception e) {
      // 执行器抛到这里的异常,处理不了「动作到底生效没有」——实测点下载按钮时底层抛
      // object-does-not-exist(artifact@/response@),文件其实已经落盘。老写法只说「失败」,
      // 调用方就会重试,而重试可能造成**重复下载 / 重复提交**。所以这里明确标成「不确定」,
      // 并把「先读状态、别直接重试」写进 data.note 与 msg。
      String rawMessage = e.getMessage();
      String detail = PlaywrightService.briefMessage(rawMessage);
      // 判伪故障用**原始文本**,不用 briefMessage 的结果:后者只保留 message=' 之后的第一行,
      // 而「Object doesn't exist: response@…」可能出现在别的位置,截断后就认不出来了 ——
      // 同一件事在 dispatchWithSpuriousRetry(:214)与 CapturePolicy(:52)里判的是原文,
      // 这里也必须同口径,否则「重试层认为是伪故障、分类层认为不是」。
      boolean spurious = ActionError.isSpuriousDispatch(rawMessage);
      boolean retrySafe = retrySafeFor(method, args);
      if (spurious && !retrySafe) {
        // 伪故障 + 动作类命令:能核对的就核对(页签类),核对不了才把结论留给调用方
        RespBodyVo verified = verifyTabsAfterSpurious(id, method, tabsBefore, detail);
        if (verified != null) {
          return verified;
        }
        // 伪故障 + 动作类命令:诊断说清楚,但结论仍是「不确定」——动作可能已经生效
        Kv uncertain = Kv.by("errorCode", ActionError.ACTION_UNCERTAIN).set("retryable", false)
            .set("spuriousDispatch", true)
            .set("note", "这个异常来自 Playwright 的事件分发（底层对象已释放），不是 " + method
                + " 自己报的错：命令**可能已经生效**。请先用只读命令"
                + "（get_browser_state / get_form_state / get_page_snapshot）确认页面状态，"
                + "不要直接重试——重试可能造成重复下载 / 重复提交。"
                + "确认过「没生效」之后，写 retryOnSpurious:true 再发一次是安全的："
                + "伪故障里有很大一支是命令还没发出去就撞上了噪声，服务端会替你把这一类吃掉。");
        RespBodyVo resp = RespBodyVo.fail(method + " 失败：" + detail
            + "（[" + ActionError.SPURIOUS_DISPATCH + "] 疑似 Playwright 事件分发的伪故障，"
            + "但无法判断本次是否已生效，请先读页面状态再决定是否重试）");
        resp.setData(uncertain);
        return resp;
      }
      if (spurious) {
        // 伪故障 + 只读命令:服务端已经替调用方重发过 SPURIOUS_MAX_ATTEMPTS 次,仍失败就如实报,
        // 并明确「可以再发」——只读命令重发没有副作用,不必让调用方去猜
        boolean declared = callerAllowsSpuriousRetry(args);
        Kv detailKv = Kv.by("errorCode", ActionError.SPURIOUS_DISPATCH).set("retryable", true)
            .set("retryAfterMs", 200).set("spuriousDispatch", true)
            .set("note", "这是 Playwright 事件分发投递过来的伪故障（底层对象已释放，与本次命令无关）："
                + "本次有限重试未恢复；重试次数可能因共享额度或截止时间而减少，请先检查原结果。");
        if (declared) {
          detailKv.set("retryAllowedByCaller", true);
        }
        RespBodyVo resp = RespBodyVo.fail(method + " 失败：" + detail
            + "（[" + ActionError.SPURIOUS_DISPATCH + "] 疑似 Playwright 事件分发的伪故障，可以再发一次）");
        resp.setData(detailKv);
        return resp;
      }
      if (ActionError.isPageNavigating(rawMessage)) {
        // 页面正在导航/重建:这一刻拿不到 DOM。只读命令重发无害(服务端也已经按可重发名单重发过),
        // 必须归成可重试的 PAGE_NAVIGATING;动作类命令则保留「不确定」,不诱导调用方重复提交。
        Kv navigating = Kv.by("errorCode", ActionError.PAGE_NAVIGATING)
            .set("retryable", retrySafe)
            .set("pageNavigating", true)
            .set("retryAfterMs", ActionError.retryAfterMs(ActionError.PAGE_NAVIGATING))
            .set("note", retrySafe
                ? "页面正在导航/重建，这一刻拿不到 DOM；这是只读命令，重发无害，稍后再发即可。"
                : "页面正在导航/重建，这一刻拿不到 DOM；这是动作类命令，无法判断是否已生效，"
                    + "请先用只读命令确认页面状态再决定是否重发。");
        RespBodyVo resp = RespBodyVo.fail(method + " 失败：" + detail
            + "（[" + ActionError.PAGE_NAVIGATING + "] 页面正在导航/重建，DOM 这一刻不可用"
            + (retrySafe ? "，可以重发" : "，先读页面状态") + "）");
        resp.setData(navigating);
        return resp;
      }
      Kv uncertain = Kv.by("errorCode", ActionError.ACTION_UNCERTAIN)
          .set("retryable", false)
          .set("note", "执行器抛了未预期异常，无法判断动作是否已经生效。请先用只读命令"
              + "（get_browser_state / get_form_state / get_page_snapshot）确认页面状态，"
              + "不要直接重试——重试可能造成重复下载 / 重复提交。");
      RespBodyVo resp = RespBodyVo.fail(method + " 失败：" + detail
          + "（[" + ActionError.ACTION_UNCERTAIN + "] 无法判断动作是否已生效，请先读页面状态再决定是否重试）");
      resp.setData(uncertain);
      return resp;
    }
  }

  /**
   * 失败步的统一错误对象
   *
   * <p>格式:{@code {code, message, retryable, retryAfterMs}}。批量里每一步的失败原因都在这里,
   * 调用方按 {@code retryable} 决定要不要退避重试,不用去解析中文 {@code msg}。
   */
  private static Kv describeError(RespBodyVo result) {
    String message = result.getMsg();
    String errorCode = null;
    if (result.getData() instanceof Kv) {
      errorCode = ((Kv) result.getData()).getStr("errorCode");
    }
    if (errorCode == null) {
      java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\[([A-Z_]+)\\]").matcher(
          message == null ? "" : message);
      errorCode = match.find() ? match.group(1) : ActionError.code(message);
    }
    boolean retryable = ActionError.retryable(errorCode);
    Kv error = Kv.by("code", errorCode).set("message", message).set("retryable", retryable);
    if (retryable) {
      error.set("retryAfterMs", ActionError.retryAfterMs(errorCode));
    }
    return error;
  }

  /**
   * 执行一条命令里附带的断言({@code expect})
   *
   * <p>
   * <b>为什么需要它</b>:本会话里最贵的坑就是「动作回执说成功、页面其实没变」——JS 派发的点击在
   * 某些按钮上完全无效,而接口照样回 {@code ok:true}。批次里每一步都可以带一个 {@code expect}:
   *
   * <pre>
   * {"click_element_by_selector": {"selector": ".ant-modal-confirm .ant-btn-primary"},
   *  "expect": {"js": "document.querySelectorAll('.ant-modal-confirm').length", "equals": 0}}
   * </pre>
   *
   * 断言在命令执行**之后**求值,支持 {@code equals} / {@code notEquals} / {@code min} / {@code max} /
   * {@code contains} / {@code truthy};结果写进该步的 {@code expectResult}。断言失败**不会**把命令本身
   * 判成失败(命令确实执行了),但会在批次汇总里计入 {@code expectFailed},并可按
   * {@code stopOnExpectFailure} 提前停下。
   *
   * @return {@code {passed, actual, expected, ...}};断言脚本自己报错时 {@code passed:false} 且带 error
   */
  private Kv checkExpectation(Long browserId, JSONObject expect) {
    String js = expect.getString("js");
    if (js == null || js.isBlank()) {
      return Kv.by("passed", false).set("error", "expect 需要 js 字段(要断言的表达式)");
    }
    RespBodyVo evaluated = svc.executeJs(browserId, js);
    if (!evaluated.isOk()) {
      return Kv.by("passed", false).set("error", evaluated.getMsg());
    }
    Object data = evaluated.getData();
    Object actual = data instanceof Kv ? ((Kv) data).get("result") : data;
    Boolean passed = compareExpectation(expect, actual);
    Kv report = Kv.by("passed", passed).set("actual", actual).set("js", js);
    for (String key : new String[] {"equals", "notEquals", "min", "max", "contains", "truthy"}) {
      if (expect.containsKey(key)) {
        report.set("expected", expect.get(key)).set("matcher", key);
        break;
      }
    }
    return report;
  }

  /** 按 expect 里给的匹配方式比较实际值;没给匹配方式时按「真值」判断 */
  private static Boolean compareExpectation(JSONObject expect, Object actual) {
    if (expect.containsKey("equals")) {
      return sameValue(expect.get("equals"), actual);
    }
    if (expect.containsKey("notEquals")) {
      return !sameValue(expect.get("notEquals"), actual);
    }
    if (expect.containsKey("min")) {
      return asDouble(actual) != null && asDouble(expect.get("min")) != null
          && asDouble(actual) >= asDouble(expect.get("min"));
    }
    if (expect.containsKey("max")) {
      return asDouble(actual) != null && asDouble(expect.get("max")) != null
          && asDouble(actual) <= asDouble(expect.get("max"));
    }
    if (expect.containsKey("contains")) {
      return actual != null && String.valueOf(actual).contains(String.valueOf(expect.get("contains")));
    }
    if (expect.containsKey("truthy")) {
      boolean wanted = Boolean.TRUE.equals(expect.getBoolean("truthy"));
      return wanted == truthy(actual);
    }
    // 只写了 js 没写匹配方式:按「结果是真值」判断
    return truthy(actual);
  }

  /** 数值比较按数字比,其余按字符串比(JSON 反序列化会把 0 变成 Integer、0.0 变成 Double) */
  private static boolean sameValue(Object expected, Object actual) {
    Double left = asDouble(expected);
    Double right = asDouble(actual);
    if (left != null && right != null) {
      return Double.compare(left, right) == 0;
    }
    if (expected instanceof Boolean || actual instanceof Boolean) {
      return truthy(expected) == truthy(actual);
    }
    return java.util.Objects.equals(expected == null ? null : String.valueOf(expected),
        actual == null ? null : String.valueOf(actual));
  }

  private static Double asDouble(Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      try {
        return Double.parseDouble(((String) value).trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static boolean truthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue() != 0;
    }
    if (value instanceof java.util.Collection) {
      return !((java.util.Collection<?>) value).isEmpty();
    }
    String text = String.valueOf(value).trim();
    return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text) && !"null".equals(text);
  }

  /**
   * 批量指令:params 里是 {@code stopOnError} 与 {@code commands}
   *
   * <p>
   * {@code commands} 每项只能有一个键,键是命令名、值是参数对象:
   * {@code [{"click_element_by_index":{"index":12}},{"get_browser_state":{}}]}。
   */
  public RespBodyVo batchExecute(Long id, JSONObject params) {
    return batchExecute(id, params, null);
  }

  /**
   * 批量指令的实装
   *
   * @param jobId 非空表示这是一次异步任务:每步之间检查取消标记,并把已跑步数写回任务
   */
  public RespBodyVo batchExecute(Long id, JSONObject params, String jobId) {
    Long browserId = id;
    boolean stopOnError = true;
    if (params == null) {
      return RespBodyVo.fail("commands 需要 params.commands 命令数组");
    }
    if (params.getBoolean("stopOnError") != null) {
      stopOnError = params.getBoolean("stopOnError");
    }
    Long maxDurationMs = params.getLong("maxDurationMs");
    long batchStartedAt = System.currentTimeMillis();
    JSONArray commands = params.getJSONArray("commands");
    if (commands == null && params.size() == 1 && !params.containsKey("stopOnError")) {
      // 宽容处理:只写一条命令时,params 本身就可以是那一条
      commands = new JSONArray();
      commands.add(params);
    }
    if (commands == null) {
      return RespBodyVo.fail("commands 需要 params.commands 命令数组");
    }
    if (commands.isEmpty()) {
      return RespBodyVo.fail("命令数组为空");
    }
    int maxCommands = PlaywrightService.commandMaxPerBatch();
    if (commands.size() > maxCommands) {
      return RespBodyVo.fail("命令数组最多 " + maxCommands + " 条,当前 " + commands.size()
          + " 条(browser.command.maxPerBatch 可调)");
    }
    if (browserId == null) {
      return RespBodyVo.fail("缺少参数 id");
    }

    List<Object> results = new ArrayList<>(commands.size());
    int succeeded = 0;
    int failed = 0;
    int expectFailed = 0;
    String firstFailure = null;
    String firstExpectFailure = null;
    String stopReason = null;
    boolean stopOnExpectFailure = Boolean.TRUE.equals(params.getBoolean("stopOnExpectFailure"));

    for (int i = 0; i < commands.size(); i++) {
      // 异步任务的取消是协作式的:在每一步之间生效,不会把已经发出的那一步打断
      if (jobId != null && JobRegistry.cancelRequested(jobId)) {
        stopReason = "cancelled";
        break;
      }
      if (maxDurationMs != null && maxDurationMs > 0 && System.currentTimeMillis() - batchStartedAt > maxDurationMs) {
        stopReason = "maxDurationMs(" + maxDurationMs + "ms)";
        break;
      }
      String cmdName = "?";
      RespBodyVo result;
      JSONObject expectation = null;
      try {
        JSONObject entry = commands.getJSONObject(i);
        if (entry == null) {
          result = RespBodyVo.fail("每条命令对象只能包含一个键");
        } else {
          // 允许「一条命令 + 一个 expect 断言」的两键写法:{命令:{...}, expect:{...}}
          expectation = entry.getJSONObject("expect");
          JSONObject only = new JSONObject(entry);
          only.remove("expect");
          if (only.size() != 1) {
            result = RespBodyVo.fail("每条命令对象只能包含一个键（外加可选的 expect）");
          } else {
            cmdName = only.keySet().iterator().next();
            if ("commands".equals(cmdName)) {
              result = RespBodyVo.fail("批量接口不支持嵌套调用 commands");
            } else {
              JSONObject args = only.getJSONObject(cmdName);
              result = execute(browserId, cmdName, args == null ? new JSONObject() : args);
            }
          }
        }
      } catch (Exception e) {
        result = RespBodyVo.fail(PlaywrightService.briefMessage(e.getMessage()));
      }

      Kv step = Kv.by("index", i).set("command", cmdName).set("ok", result.isOk()).set("data", result.getData())
          .set("msg", result.getMsg());
      if (!result.isOk()) {
        // 失败步给一个统一的错误对象:调用方不必去解析中文 msg,也不必自己判断能不能重试
        step.set("error", describeError(result));
      }
      // 断言在命令之后求值:动作说成功、状态其实没变的情况,靠它暴露出来
      if (expectation != null && result.isOk()) {
        Kv checked = checkExpectation(browserId, expectation);
        step.set("expectResult", checked);
        if (!Boolean.TRUE.equals(checked.getBoolean("passed"))) {
          expectFailed++;
          if (firstExpectFailure == null) {
            firstExpectFailure = "第 " + i + " 条命令 " + cmdName + " 的断言没通过："
                + (checked.get("error") != null ? checked.get("error")
                    : "实际值 " + checked.get("actual") + " 不满足 " + checked.get("matcher") + " " + checked.get("expected"));
          }
        }
      }
      results.add(step);

      if (result.isOk()) {
        succeeded++;
      } else {
        failed++;
        if (firstFailure == null) {
          firstFailure = "第 " + i + " 条命令 " + cmdName + " 失败：" + result.getMsg();
        }
        if (stopOnError) {
          break;
        }
      }
      if (stopOnExpectFailure && expectFailed > 0) {
        stopReason = "stopOnExpectFailure";
        break;
      }
      if (jobId != null) {
        JobRegistry.Job job = JobRegistry.get(jobId);
        if (job != null) {
          job.steps = i + 1;
        }
      }
    }

    Kv data = Kv.by("count", results.size()).set("succeeded", succeeded).set("failed", failed)
        .set("expectFailed", expectFailed)
        .set("stopped", stopReason != null || (stopOnError && failed > 0)
            || (stopOnExpectFailure && expectFailed > 0))
        .set("results", results);
    if (stopReason != null) {
      data.set("stopReason", stopReason);
    }
    if (jobId != null) {
      data.set("jobId", jobId);
    }
    if (failed == 0 && expectFailed == 0) {
      return RespBodyVo.ok(data);
    }
    RespBodyVo resp = RespBodyVo.fail(failed > 0 ? firstFailure : firstExpectFailure);
    if (failed == 0) {
      // 命令都执行了,只是断言没通过:回执要如实说明,别让调用方以为动作失败
      data.set("note", "命令本身都执行成功,是 expect 断言没通过(动作发了、页面状态没变成期望的样子)");
    }
    resp.setData(data);
    return resp;
  }

  /** 解析 JSON 请求体,失败时给出中文原因 */
  public static JSONObject parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    return JSON.parseObject(body);
  }

  /**
   * 方法名写错时的提示
   *
   * <p>
   * 实测最常见的错法是凭直觉猜名字(比如把页签列表写成 {@code list_tabs},而它叫 {@code get_tabs}),
   * 只回一句「不支持的方法」就得让模型再猜一轮。这里按编辑距离给出最接近的几个候选,一次就能纠正。
   */
  public static String unknownMethodMessage(String method) {
    StringBuilder message = new StringBuilder("不支持的方法：").append(method);
    java.util.List<String> suggestions = CommandTable.suggest(method, 3);
    if (!suggestions.isEmpty()) {
      message.append("，你是不是想用 ").append(String.join(" / ", suggestions)).append("？");
    }
    message.append("（全部 ").append(CommandTable.names().size()).append(" 个方法见技能文档的方法清单）");
    return message.toString();
  }
}
