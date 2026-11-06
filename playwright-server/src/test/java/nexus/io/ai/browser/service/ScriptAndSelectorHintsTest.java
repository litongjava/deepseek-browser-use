package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * Script results and selector failures in a real browser, on a local data URL.
 *
 * <p>Statements without a return used to fail as a syntax error that clients printed as a bare
 * null, and a selector with a wrong attribute gave no clue which attribute the page used.
 */
public class ScriptAndSelectorHintsTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static Path profileDir;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-hints-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
    RespBodyVo opened = actions.execute(id, "go_to_url", json("url",
        "data:text/html,<textarea name=target></textarea><button onclick=\"document.title='done'\">Go</button>"));
    assertTrue(opened.getMsg(), opened.isOk());
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

  @Test
  public void statementsWithoutReturnRunAndYieldTheLastValue() {
    RespBodyVo result = actions.execute(id, "execute_js",
        json("body", "document.querySelector('button').click(); document.title"));
    assertTrue(result.getMsg(), result.isOk());
    assertEquals("done", ((Kv) result.getData()).get("result"));
  }

  @Test
  public void scriptWithoutValueSaysSo() {
    RespBodyVo result = actions.execute(id, "execute_js", json("body", "const unused = 1;"));
    assertTrue(result.getMsg(), result.isOk());
    Kv data = (Kv) result.getData();
    assertNull(data.get("result"));
    assertTrue(String.valueOf(data.get("hint")), String.valueOf(data.get("hint")).contains("return"));
  }

  @Test
  public void syntaxErrorIsMarkedNotStarted() {
    RespBodyVo result = actions.execute(id, "execute_js", json("body", "return (;"));
    assertFalse(result.isOk());
    Kv data = (Kv) result.getData();
    assertEquals("SCRIPT_SYNTAX_ERROR", data.getStr("errorCode"));
    Kv execution = (Kv) data.get("execution");
    assertEquals("not-started", execution.getStr("actionStatus"));
    assertEquals(Boolean.TRUE, execution.get("retrySafe"));
  }

  @Test
  public void missingSelectorListsElementsOfTheSameTag() {
    JSONObject params = json("selector", "textarea[name=content]");
    params.put("text", "x");
    RespBodyVo result = actions.execute(id, "input_text_by_selector", params);
    assertFalse(result.isOk());
    assertTrue(result.getMsg(), result.getMsg().contains("ELEMENT_NOT_FOUND"));
    assertTrue(result.getMsg(), result.getMsg().contains("textarea[name=\"target\"]"));
  }

  @Test
  public void missingTagSaysThePageHasNone() {
    JSONObject params = json("selector", "select#city");
    params.put("text", "x");
    RespBodyVo result = actions.execute(id, "input_text_by_selector", params);
    assertFalse(result.isOk());
    assertTrue(result.getMsg(), result.getMsg().contains("页面上没有 select 元素"));
  }

  private static JSONObject json(String key, Object value) {
    JSONObject object = new JSONObject();
    object.put(key, value);
    return object;
  }
}
