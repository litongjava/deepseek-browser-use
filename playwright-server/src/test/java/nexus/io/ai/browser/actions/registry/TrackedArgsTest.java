package nexus.io.ai.browser.actions.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 「参数被静默忽略」这条防线的用例
 *
 * <p>
 * 起因是一次真实事故:调用方把 {@code request_human_input} 的超时参数写成
 * {@code expiresInSeconds},而这个命令只认 {@code timeoutSeconds}。回执照样 {@code ok:true},
 * 待办按默认 300 秒建好,人还没看到请求就已经过期——调用方拿不到任何线索。
 *
 * <p>
 * {@link TrackedArgs} 的办法是「读一个键记一个」,跑完拿 {@code keySet()} 一减就知道谁被忽略了。
 * 这里把它的两条前提钉死:①七个取值方法都会记账;②框架级参数不算「没被用过」。
 */
public class TrackedArgsTest {

  /**
   * 七个取值方法都必须被记录到
   *
   * <p>
   * 这条用例是在钉一个<b>依赖库的实现细节</b>:fastjson2 的 {@code getString}/{@code getInteger}
   * 等方法是直接调 {@code super.get(key)} 的,绕过了子类对 {@code get(Object)} 的重写。所以
   * {@link TrackedArgs} 只能逐个重写取值方法本身。哪天升级依赖后这里挂了,说明取值路径变了,
   * 要跟着改的是 {@link TrackedArgs},而不是放宽这条断言。
   */
  @Test
  public void everyAccessorIsRecorded() {
    JSONObject raw = new JSONObject();
    raw.put("s", "x");
    raw.put("i", 1);
    raw.put("l", 2L);
    raw.put("d", 1.5);
    raw.put("b", true);
    raw.put("arr", new JSONArray());
    raw.put("obj", new JSONObject());

    TrackedArgs args = new TrackedArgs(raw);
    args.getString("s");
    args.getInteger("i");
    args.getLong("l");
    args.getDouble("d");
    args.getBoolean("b");
    args.getJSONArray("arr");
    args.getJSONObject("obj");

    assertEquals("这七个取值方法都应当记账", Set.of("s", "i", "l", "d", "b", "arr", "obj"), args.readKeys());
    assertTrue("全部都读过了,不该还有漏网的:" + args.unreadKeys(), args.unreadKeys().isEmpty());
  }

  /** 传了但没读的键要被指出来 —— 这就是「静默忽略」的可观测化 */
  @Test
  public void unreadParamsAreReported() {
    JSONObject raw = new JSONObject();
    raw.put("url", "https://example.com");
    raw.put("urls", "拼错的参数名");

    TrackedArgs args = new TrackedArgs(raw);
    args.getString("url");

    assertEquals("只该报出没被用过的那一个", Set.of("urls"), args.unreadKeys());
  }

  /**
   * 框架级参数不算「没被用过」
   *
   * <p>
   * {@code retryOnSpurious} 与 {@code async} 由分发层读取,不属于任何单个命令:如果把它们算成
   * 陌生参数,每一条带重发许可的调用都会收到一句莫名其妙的提示。
   */
  @Test
  public void frameworkParamsAreNotReportedAsUnknown() {
    JSONObject raw = new JSONObject();
    raw.put("url", "https://example.com");
    raw.put("retryOnSpurious", true);
    raw.put("async", true);

    TrackedArgs args = new TrackedArgs(raw);
    args.getString("url");

    assertTrue("框架级参数不该被当成陌生参数:" + args.unreadKeys(), args.unreadKeys().isEmpty());
  }

  /**
   * 说明里要同时给出「陌生的」与「实际认的」两个清单
   *
   * <p>
   * 只说「这个参数不认识」不够用:调用方真正需要的是这个命令到底认哪些参数。两个清单一对照,
   * 一轮就能改对,不必再去翻文档。
   */
  @Test
  public void noteCarriesBothUnknownAndAccepted() {
    String note = TrackedArgs.unknownParamNote("request_human_input", Set.of("expiresInSeconds"),
        Set.of("prompt", "timeoutSeconds"));

    assertTrue(note, note.contains("request_human_input"));
    assertTrue("要报出陌生参数:" + note, note.contains("expiresInSeconds"));
    assertTrue("要给出实际认的参数:" + note, note.contains("timeoutSeconds"));
  }
}
