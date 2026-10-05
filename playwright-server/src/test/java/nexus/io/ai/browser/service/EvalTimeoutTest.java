package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * {@code execute_js} 的时间预算(真浏览器,本地 about:blank,不碰外网)
 *
 * <p>
 * <b>为什么必须有这条用例</b>:2026-10-05 在真服务上量到,{@code page.evaluate} 连它名义上的 30 秒
 * 默认超时都**不生效** ——
 *
 * <ul>
 * <li>一个 40 秒后才 resolve 的 Promise:正常返回(40024ms);</li>
 * <li>一个永不 resolve 的 Promise:**挂过 120 秒仍无响应**,请求最后是被客户端超时放弃的。</li>
 * </ul>
 *
 * 也就是说这条命令原本没有上限,调用方只能靠 HTTP 超时兜着,而放弃不会取消服务端已经在跑的脚本。
 * 现在的兜底是页内 {@code Promise.race}(见 {@code PlaywrightService.withEvalTimeout}):
 * 只要页面的 JS 还能跑,evaluate 就一定会结束。这条用例钉的就是「一定会结束」。
 */
public class EvalTimeoutTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static Path profileDir;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-evaltimeout-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    // 预算调成 1.5 秒:这条用例要验的是「会结束」,没必要真等 60 秒
    System.setProperty(PlaywrightService.KEY_EVAL_TIMEOUT, "1500");
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() throws Exception {
    if (id != null) {
      service.close(id);
    }
    for (String key : List.of(ChromeBrowser.KEY_PROFILE_DIR, PlaywrightService.KEY_EVAL_TIMEOUT)) {
      System.clearProperty(key);
    }
    ChromeBrowser.resetForTests();
    if (profileDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(profileDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  /** 核心回归:永不 settle 的 Promise 必须**在预算内**拿到明确结论,而不是挂死 */
  @Test
  public void neverSettlingPromiseIsCutOffWithinBudget() {
    long startedAt = System.currentTimeMillis();
    RespBodyVo result = actions.execute(id, "execute_js", json("body", "() => new Promise(() => {})"));
    long elapsed = System.currentTimeMillis() - startedAt;

    assertFalse("永不 settle 的脚本必须失败,不能一直等", result.isOk());
    Kv data = (Kv) result.getData();
    assertEquals(ActionError.EVAL_TIMEOUT, data.getStr("errorCode"));
    // 脚本可能已经跑了一半副作用 → 结果未知、不可自动重试
    assertEquals(Boolean.FALSE, data.get("retryable"));
    assertEquals(Boolean.TRUE, data.get("outcomeUnknown"));
    assertEquals(1500L, ((Number) data.get("evalTimeoutMs")).longValue());
    // 没有这一层时这里会是 120 秒级(实测);给足余量但必须远小于它
    assertTrue("必须在预算附近就结束,实测耗时 " + elapsed + "ms", elapsed < 15_000);
  }

  /** 慢但**有限**的脚本不该被误杀(否则这个兜底会把正常长脚本判死) */
  @Test
  public void slowButFiniteScriptStillSucceeds() {
    RespBodyVo result = actions.execute(id, "execute_js",
        json("body", "() => new Promise(r => setTimeout(() => r('ok'), 600))"));
    assertTrue(result.getMsg(), result.isOk());
    assertEquals("ok", ((Kv) result.getData()).getStr("result"));
  }

  /** 单次调用可以覆盖配置里的预算(给「这次就是要等一个慢接口」留的口子) */
  @Test
  public void perCallTimeoutOverridesConfiguredBudget() {
    JSONObject params = json("body", "() => new Promise(r => setTimeout(() => r('slow-ok'), 2500))");
    params.put("timeoutMs", 6000);
    RespBodyVo result = actions.execute(id, "execute_js", params);
    assertTrue("配置是 1500ms,但这次显式给了 6000ms", result.isOk());
    assertEquals("slow-ok", ((Kv) result.getData()).getStr("result"));
  }

  /** 包了一层超时之后,三种脚本形状的行为都不能变 */
  @Test
  public void allScriptShapesStillEvaluate() {
    assertEquals(3, number(actions.execute(id, "execute_js", json("body", "1 + 2"))));
    assertEquals(42, number(actions.execute(id, "execute_js", json("body", "const a = 40; return a + 2;"))));
    assertEquals("ok", ((Kv) actions.execute(id, "execute_js", json("body", "() => 'ok'")).getData())
        .getStr("result"));
  }

  private static int number(RespBodyVo result) {
    assertTrue(result.getMsg(), result.isOk());
    return ((Number) ((Kv) result.getData()).get("result")).intValue();
  }

  /**
   * 「一开始就调用的函数」与「对象字面量」不能被误当成函数字面量
   *
   * <p>
   * 这是真机实测踩出来的坑(在插件那层的包装上暴露,服务端这层同样中招):{@code normalizeToFunction}
   * 认「以 {@code (} 开头」为函数式,而 {@code (() => 42)()} 与 {@code ({a:1}).a} 都符合这个形状 ——
   * 如果超时包装直接写 {@code Promise.resolve().then(fn)},JS 的 {@code then} 收到**非函数**参数会
   * **静默忽略**,于是脚本的副作用照跑、返回值却变成 undefined。包装里改成「先求值、再判断是不是函数」
   * 就没这个问题。
   */
  @Test
  public void invokedFunctionAndObjectLiteralKeepTheirValue() {
    assertEquals(42, number(actions.execute(id, "execute_js", json("body", "(() => 42)()"))));
    assertEquals(1, number(actions.execute(id, "execute_js", json("body", "({a: 1}).a"))));
    assertEquals("async-ok", ((Kv) actions.execute(id, "execute_js",
        json("body", "(async () => 'async-ok')()")).getData()).getStr("result"));
  }

  private static JSONObject json(String key, Object value) {
    JSONObject object = new JSONObject();
    object.put(key, value);
    return object;
  }
}
