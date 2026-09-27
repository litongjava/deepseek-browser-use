package nexus.io.ai.browser.service;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;

import nexus.io.ai.browser.dom.model.DOMState;
import nexus.io.model.body.RespBodyVo;

/** Local fixtures: navigation during capture must not leave actionable mixed indices. */
public class BrowserConsistencyTest {
  private static PlaywrightService service;
  private static Long id;

  @BeforeClass
  public static void start() throws Exception {
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, Files.createTempDirectory("consistency-profile").toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() {
    if (id != null) service.close(id);
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
  }

  @Test
  public void stableSnapshotIgnoresItsOwnHighlights() {
    service.getInstance(id).page.setContent("<title>fixture</title><button>Submit</button>");
    RespBodyVo response = service.getBrowserState(id, true, 0);
    assertTrue(response.getMsg(), response.isOk());
    Kv data = (Kv) response.getData();
    assertEquals(data.toString(), true, data.get("snapshotConsistent"));
    assertEquals(true, data.get("indicesUsable"));
    assertEquals(1, data.get("snapshotAttempts"));
  }

  @Test
  public void documentReplacementIsRetriedWithoutReplayingAnAction() {
    AtomicInteger captures = new AtomicInteger();
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        if (captures.incrementAndGet() == 1) inst.page.setContent("<button>New document</button>");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Old document</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0).getData();
    assertEquals(true, data.get("snapshotConsistent"));
    assertEquals(2, data.get("snapshotAttempts"));
    assertTrue(data.getStr("text"), data.getStr("text").contains("New document"));
    assertFalse(data.getStr("text").contains("Old document"));
  }

  @Test
  public void continuouslyChangingSnapshotInvalidatesIndices() {
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        inst.page.evaluate("document.body.append(document.createElement('button'))");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Submit</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0).getData();
    assertEquals(false, data.get("snapshotConsistent"));
    assertEquals(false, data.get("indicesUsable"));
    assertNull(service.getInstance(id).domState);
    assertFalse(service.clickElementByIndex(id, 0).isOk());
  }

  /**
   * 纯内容变动(实时行情每秒刷新价格文字)不该判死索引
   *
   * <p>
   * 这是实测拿到的那条退化:实时行情页的价格文字持续改写,旧判据只要整个文档动过一次就作废整份索引,
   * 于是「每次按索引点击都只拿到『当前没有页面快照』」,只能全程改用选择器与文本定位。位置型 xpath 不受
   * 文字改写影响,所以这种变动必须被容忍。
   */
  @Test
  public void contentOnlyChurnKeepsIndicesUsable() {
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        inst.page.evaluate("document.querySelector('button').textContent = 'Submit ' + Math.random()");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Submit</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0).getData();
    assertEquals(data.toString(), true, data.get("snapshotConsistent"));
    assertEquals(true, data.get("indicesUsable"));
    assertNotNull(data.get("snapshotNote"));
    assertEquals(0, data.get("snapshotStructuralMutations"));
    assertTrue((Integer) data.get("snapshotContentMutations") > 0);
    assertNotNull(service.getInstance(id).domState);
    assertTrue(service.clickElementByIndex(id, 0).isOk());
  }

  /** strictSnapshot 要能退回旧的「任何 DOM 变更即作废」判据 */
  @Test
  public void strictSnapshotRestoresMutationBasedInvalidation() {
    PlaywrightService changing = new PlaywrightService() {
      @Override public Kv capture(BrowserInstance inst) {
        inst.page.evaluate("document.querySelector('button').textContent = 'Submit ' + Math.random()");
        return Kv.by("seq", inst.captureSeq.incrementAndGet());
      }
    };
    service.getInstance(id).page.setContent("<button>Submit</button>");
    Kv data = (Kv) changing.getBrowserState(id, false, 0, true, 200, null, Boolean.TRUE).getData();
    assertEquals(false, data.get("snapshotConsistent"));
    assertEquals(false, data.get("indicesUsable"));
    assertTrue(String.valueOf(data.get("snapshotIssues")), ((List<?>) data.get("snapshotIssues")).contains("mutations_changed"));
  }

  /** 位置已经被别的元素占了(同一位置换了标签)时必须作废,哪怕变更计数没动 */
  @Test
  public void elementIdentityMismatchInvalidatesIndices() {
    List<String> issues = PlaywrightService.snapshotIssues(Kv.by("url", "same"),
        Kv.by("url", "same"), new DOMState(null, Map.of(), 0, 0, 0, 0),
        List.of(Kv.by("index", 3).set("resolved", true).set("tag", "input").set("snapshotTag", "button")));
    assertTrue(issues.toString(), issues.stream().anyMatch(i -> i.startsWith("element_identity_changed:")));
  }

  @Test
  public void missingElementsAndUrlChangesAreExplicit() {
    DOMState state = new DOMState(null, Map.of(), 0, 0, 0, 0);
    List<String> issues = PlaywrightService.snapshotIssues(Kv.by("url", "old"),
        Kv.by("url", "new"), state, List.of(Kv.by("resolved", false)));
    assertTrue(issues.contains("url_changed"));
    assertTrue(issues.contains("elements_unresolved"));
  }

  @Test
  public void observationFailureCannotEraseACompletedAction() {
    Kv result = PlaywrightService.observeSafely(() -> {
      throw new PlaywrightException("Object doesn't exist: response@fixture");
    });
    assertEquals(false, result.get("observationComplete"));
    assertEquals("unknown", result.get("changeStatus"));
    assertNull(result.get("changed"));
    assertNotNull(result.get("observationError"));
  }

  @Test
  public void uncertainClickReportsUnknownAndDoesNotRetry() {
    BrowserInstance inst = service.getInstance(id);
    inst.page.setContent("<button>Submit</button>");
    Kv before = PlaywrightService.stateProbe(inst, null);
    RespBodyVo response = PlaywrightService.actionErrorOrEffect("click_element_by_role", before,
        inst, null, new PlaywrightException("Object doesn't exist: response@fixture"), "uncertain click");
    assertFalse(response.isOk());
    Kv data = (Kv) response.getData();
    assertEquals("unknown", data.get("actionStatus"));
    assertEquals(false, data.get("retrySafe"));
    assertNull(data.get("effective"));
  }
}
