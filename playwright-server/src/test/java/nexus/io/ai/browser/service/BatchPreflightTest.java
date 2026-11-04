package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import nexus.io.ai.browser.actions.registry.CommandSchema;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.model.body.RespBodyVo;

public class BatchPreflightTest {
  @Test public void invalidLaterStepNeverDispatchesEvenAsyncOrKeepGoing() {
    AtomicInteger actions = new AtomicInteger();
    ActionService service = new ActionService(new PlaywrightService() {
      @Override public RespBodyVo executeJs(Long id, String body, String file, JSONObject vars, String frame, Integer timeout) {
        actions.incrementAndGet();
        return RespBodyVo.ok();
      }
    });
    for (boolean async : new boolean[] {false, true}) {
      for (String invalid : new String[] {
          "{\"wait_for_function\":{\"body\":\"true\"}}",
          "{\"execute_js\":{}}", "{\"wait_for_text\":{\"text\":\"ok\",\"timeout\":5}}",
          "{\"click_element_by_index\":{\"index\":\"bad\"}}",
          "{\"get_title\":{},\"expect\":{\"js\":\"true\",\"timeoutMs\":-1}}",
          "{\"commands\":{}}", "{\"get_title\":42}"}) {
        JSONObject params = JSONObject.of("commands", JSONArray.parseArray("[{\"execute_js\":{\"body\":\"action\"}}," + invalid + "]"),
            "async", async, "stopOnError", false);
        RespBodyVo result = service.execute(991001L, "commands", params);
        assertFalse(result.isOk());
        assertTrue(result.getMsg(), result.getMsg().contains("steps[1]"));
        assertEquals("not-started", ((Kv) result.getData()).getStr("actionStatus"));
        assertNull(((Kv) result.getData()).get("jobId"));
      }
    }
    assertEquals(0, actions.get());
  }

  @Test public void acceptsAlternativesAndFuturePageReferences() {
    assertTrue(CommandSchema.validate("input_text", JSONObject.of("index", 1, "text", " "), "x").isEmpty());
    assertTrue(CommandSchema.validate("execute_js", JSONObject.of("bodyFile", "query.js"), "x").isEmpty());
    assertTrue(CommandSchema.validate("upload_file", JSONObject.of("selector", "#future", "contentBase64", "YQ=="), "x").isEmpty());
    assertTrue(CommandSchema.validate("wait_for_function", JSONObject.of("expression", "true", "timeoutMs", 1000, "timeoutSeconds", 1), "x").isEmpty());
    assertFalse(CommandSchema.validate("wait_for_function", JSONObject.of("expression", "true", "timeoutMs", 1000, "timeoutSeconds", 2), "x").isEmpty());
    assertFalse(CommandSchema.validate("go_to_url", JSONObject.of("url", "x", "waitUntil", "wrong"), "x").isEmpty());
    assertEquals(CommandTable.names().size(), CommandSchema.describe(CommandTable.names()).size());
  }
}
