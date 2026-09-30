package nexus.io.ai.browser.service;

import java.util.Locale;
import java.util.Set;

import com.microsoft.playwright.PlaywrightException;

/**
 * 响应体缓存:收到响应时就把 body 抄下来
 *
 * <p>
 * <b>为什么需要它</b>:{@code Response.text()} 是惰性的,浏览器只在很短一段时间内保留响应体,之后再去读就是
 * {@code Protocol error (Network.getResponseBody): No resource with given identifier found}。实测在
 * 12306 这种每秒轮询的页面上,<b>7 秒前</b>的 XHR 就已经读不到了 —— 于是 {@code get_response_body}
 * 声称的「保留最近 100 个响应」在真实站点的表现是「一个都读不到」,而调用方最想知道的是「我这一步提交
 * 到底成功了没有」。
 *
 * <p>
 * 所以在 {@code onRequestFinished} 里把 body 抄一份存进 {@link BrowserInstance.RecordedResponse},之后
 * {@code get_response_body} / {@code wait_for_response} 一律先读缓存,读不到再退回原来的惰性读法。
 *
 * <p>
 * 三条边界:
 * <ul>
 * <li>只抄 {@code xhr} / {@code fetch} —— 页面、脚本、图片的 body 又大又不是「接口返回」,
 * 抄它们只会把内存和带宽吃光;</li>
 * <li>单条最多留 {@link #MAX_CHARS} 个字符,超出部分丢掉并标 {@code bodyTruncated};</li>
 * <li>读取必须留在当前 Playwright 调用线程。独立线程会并发驱动共享 Connection 的事件泵,
 * 造成响应早于请求登记、对象找不到、响应归属错位。requestfinished 时下载已完成,
 * 不需要在 response(只有响应头)回调里等待整个下载。</li>
 * </ul>
 */
public final class ResponseBodyCache {

  /** 单条响应最多缓存多少字符(get_response_body 默认只回 2 万,留 5 倍余量) */
  public static final int MAX_CHARS = 100_000;

  /** 只有取数接口的响应体才值得留 */
  private static final Set<String> CACHEABLE_TYPES = Set.of("xhr", "fetch");

  private ResponseBodyCache() {
  }

  /** 这个资源类型值得抄 body 吗 */
  public static boolean cacheable(String resourceType) {
    return resourceType != null && CACHEABLE_TYPES.contains(resourceType.trim().toLowerCase(Locale.ROOT));
  }

  /** 超长只留前缀(截断与否由调用方按 {@link #MAX_CHARS} 判断并回报) */
  public static String clip(String body) {
    if (body == null || body.length() <= MAX_CHARS) {
      return body;
    }
    return body.substring(0, MAX_CHARS);
  }

  /**
   * 在 requestfinished 回调的线程上读取 body;不要从其它线程调用。
   *
   * <p>
   * 无论成功与否都会把 {@code bodyCaptured} 置为 true,调用方据此判断「是还在抄,还是抄失败了」。
   */
  public static void capture(BrowserInstance.RecordedResponse recorded) {
    if (recorded == null || recorded.bodyCaptured) {
      return;
    }
    String resourceType = recorded.request == null ? null : recorded.request.getStr("resourceType");
    if (!cacheable(resourceType)) {
      return;
    }
    try {
      String body = recorded.response.text();
      recorded.bodyTruncated = body != null && body.length() > MAX_CHARS;
      recorded.body = clip(body);
    } catch (PlaywrightException e) {
      recorded.bodyCaptureError = brief(e.getMessage());
    } catch (RuntimeException e) {
      recorded.bodyCaptureError = brief(String.valueOf(e));
    } finally {
      recorded.bodyAt = System.currentTimeMillis();
      recorded.bodyCaptured = true;
    }
  }

  private static String brief(String message) {
    if (message == null) {
      return "未知原因";
    }
    String single = message.replaceAll("\\s+", " ").trim();
    return single.length() <= 200 ? single : single.substring(0, 200) + "...";
  }
}
