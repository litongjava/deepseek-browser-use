package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import org.junit.Test;

public class ActionErrorTest {
  @Test public void timeoutCallLogPreservesActualCause() {
    String prefix = "Timeout 5000ms exceeded.\nCall log:\n";
    assertEquals("ELEMENT_NOT_EDITABLE", ActionError.code(prefix + "element is not editable"));
    assertEquals("ELEMENT_OBSCURED", ActionError.code(prefix + "<div class=overlay> intercepts pointer events"));
    assertEquals("ELEMENT_HIDDEN", ActionError.code(prefix + "element is not visible"));
    assertEquals("ELEMENT_DISABLED", ActionError.code(prefix + "element is not enabled"));
    assertEquals("STALE_ELEMENT", ActionError.code(prefix + "element is not attached to the DOM"));
    assertEquals("ACTION_TIMEOUT", ActionError.code(prefix + "waiting for locator('#missing')"));
    assertFalse(ActionError.describe("click", prefix).contains("请重新"));
  }

  // ==================== Chrome 的网络错误页 ====================

  /** 错误码就在异常文本里(Playwright 对网络层失败抛的是 net::ERR_xxx at <url>) */
  @Test public void netErrorCodeIsExtractedFromTheExceptionText() {
    assertEquals("ERR_CONNECTION_REFUSED",
        ActionError.netErrorFromMessage("Error: net::ERR_CONNECTION_REFUSED at http://127.0.0.1:45999/"));
    assertEquals("ERR_NETWORK_CHANGED",
        ActionError.netErrorFromMessage("net::ERR_NETWORK_CHANGED at https://x.com/compose/post"));
    assertEquals("ERR_ABORTED", ActionError.netErrorFromMessage("net::ERR_ABORTED at https://example.com"));
    assertNull("普通超时里没有网络错误码", ActionError.netErrorFromMessage("Timeout 30000ms exceeded."));
    assertNull("null 不能炸", ActionError.netErrorFromMessage(null));
  }

  /**
   * 网络错误必须排在那句 {@code Timeout} 之前
   *
   * <p>实测(TUN 抖动)Playwright 对一次网络层失败**只**抛 {@code Timeout 30000ms exceeded},
   * 于是老逻辑把它归成 {@code ACTION_TIMEOUT}(「元素可能只是不可点」),而真相是这一页根本没有内容。
   */
  @Test public void networkFailureIsNotSwallowedByTheTimeoutBranch() {
    String message = "Timeout 30000ms exceeded.\nnet::ERR_NETWORK_CHANGED at https://x.com/compose/post";
    assertEquals("网络错误要盖过 timeout", ActionError.NETWORK_ERROR, ActionError.code(message));
    // 服务端自己探到错误页时会拼一个哨兵进去,同样要认得
    assertEquals(ActionError.NETWORK_ERROR, ActionError.code("go_to_url 失败：" + ActionError.NETWORK_ERROR));
  }

  @Test public void networkErrorIsRetryableWithABackoff() {
    assertTrue("代理/TUN 抖动是暂时的,应当可重试", ActionError.retryable(ActionError.NETWORK_ERROR));
    assertTrue("退避要给够几秒,太短会连着失败几次",
        ActionError.retryAfterMs(ActionError.NETWORK_ERROR) >= 3_000);
  }

  @Test public void networkErrorDescriptionPointsAtTheProxyNotTheSelector() {
    String described = ActionError.describeNetworkError("go_to_url", "ERR_NETWORK_CHANGED");
    assertTrue("要带上错误码,实际:" + described, described.contains("ERR_NETWORK_CHANGED"));
    assertTrue("要带上 NETWORK_ERROR 码,实际:" + described, described.contains(ActionError.NETWORK_ERROR));
    assertTrue("要看代理,而不是找元素,实际:" + described,
        described.contains("代理") && described.contains("网络错误页"));
    assertTrue("要明确说「不是元素不可点」这件事,实际:" + described,
        described.contains("不是「元素在、只是暂时不可点」"));

    String aborted = ActionError.describeNetworkError("go_to_url", "ERR_ABORTED");
    assertTrue("ERR_ABORTED 是另一回事(被页面取消,例如点到了下载),措辞要分开,实际:" + aborted,
        aborted.contains("取消"));
  }
}
