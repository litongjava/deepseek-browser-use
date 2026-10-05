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

import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * {@code networkRecording=false}(等价于 {@code browser.network.record=off})时到底发生了什么
 *
 * <p>
 * <b>为什么要专门钉这一条</b>:请求/响应监听器是实测里最贵的一类伪故障来源 —— Playwright Java 的页级
 * 事件实际挂在**上下文**上再按页过滤,上下文事件分发一旦碰到已释放的对象就抛
 * {@code Object doesn't exist: response@… / request@…},并砸在**后续任意一次 API 调用**上。
 * 同一个类里 {@code page.onDownload} 已经因为完全相同的理由被拆掉了。
 *
 * <p>
 * 「不订阅就不会有这个分发面」是本服务唯一能**彻底**消掉那一族噪声的设置,所以它必须:
 *
 * <ul>
 * <li>真的不挂监听器(不是「挂上但不记」—— 挂上就已经有分发面了);</li>
 * <li>让读网络的命令**如实说「没在记」**,而不是回一个会被误读成「没有请求」的空结果;</li>
 * <li>不影响其它能力({@code wait_for_idle} 降级为只看 DOM 变更,并在回执里说明)。</li>
 * </ul>
 */
public class NetworkRecordingOffTest {

  private static PlaywrightService service;
  private static Long id;
  private static Path profileDir;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-netoff-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    // 按任务关掉网络记录:这正是踩到 response@ 噪声风暴时该做的动作
    id = service.start(null, true, null, false);
  }

  @AfterClass
  public static void stop() throws Exception {
    if (id != null) {
      service.close(id);
    }
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
    if (profileDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(profileDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test public void noListenersAreAttachedAtAll() {
    BrowserInstance instance = service.getInstance(id);
    assertFalse("off 模式必须**真的不订阅**:挂上就已经有那个分发面了", instance.recorderAttached);
    assertEquals("也不该留下「从什么时候开始记」的时间戳", 0L, instance.recorderAttachedAt);
    assertTrue(instance.requests.isEmpty());
    assertEquals(0, instance.inflight.get());
  }

  @Test public void getRequestsSaysItIsNotRecording() {
    Kv data = (Kv) service.getRequests(id, null).getData();
    assertEquals(Boolean.FALSE, data.get("networkRecorded"));
    assertEquals(0, ((List<?>) data.get("requests")).size());
    String note = data.getStr("networkNote");
    assertTrue("空结果必须自证是「没在记」,实际:" + note, note.contains("没在记"));
    assertTrue("要给出改法,实际:" + note, note.contains("browser.network.record"));
  }

  @Test public void waitForResponseFailsWithTheSameExplanation() {
    RespBodyVo result = service.waitForResponse(id, "/never", 0.2, null, null);
    assertFalse(result.isOk());
    assertTrue(result.getMsg(), result.getMsg().contains("网络记录已被配置关掉"));
    assertEquals(Boolean.FALSE, ((Kv) result.getData()).get("networkRecorded"));
  }

  @Test public void getPageSnapshotWithRequestsAlsoExplains() {
    Kv data = (Kv) service.getPageSnapshot(id, false, true, null).getData();
    assertEquals(Boolean.FALSE, data.get("networkRecorded"));
    assertEquals(0, ((List<?>) data.get("requests")).size());
  }

  @Test public void waitForIdleStillWorksWithoutTheNetworkDimension() {
    RespBodyVo idle = service.waitForIdle(id, 100, 5.0, null, null);
    assertTrue(idle.getMsg(), idle.isOk());
    Kv data = (Kv) idle.getData();
    assertEquals("要如实说明这次没有网络维度", Boolean.FALSE, data.get("networkTracked"));
    assertEquals(0, ((Number) data.get("inflight")).intValue());
  }
}
