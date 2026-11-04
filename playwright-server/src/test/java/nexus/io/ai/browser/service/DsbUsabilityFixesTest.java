package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.ai.browser.actions.registry.CommandSchema;
import nexus.io.ai.browser.util.CredentialRedactor;

public class DsbUsabilityFixesTest {

  @Test
  public void redactorSurvivesLongQuotedValues() {
    String huge = "x".repeat(2_000_000);
    String text = "console: token \"apiKey\":\"" + huge + "\" and \"note\":\"" + huge + "\"";
    String redacted = CredentialRedactor.redactBody(text, "***");
    assertNotNull(redacted);
    assertFalse(redacted.contains("\"apiKey\":\"xxx"));
  }

  @Test
  public void redactorStillMasksEscapedPairs() {
    String redacted = CredentialRedactor.redactBody("not json \"password\":\"a\\\"b\" \"other\":\"keep\"", "***");
    assertFalse(redacted.contains("a\\\"b"));
    assertTrue(redacted.contains("keep"));
  }

  @Test
  public void numericTextParameterIsAccepted() {
    JSONObject args = new JSONObject();
    args.put("text", 2026);
    List<String> errors = CommandSchema.validate("wait_for_text", args, "wait_for_text");
    assertTrue(errors.toString(), errors.isEmpty());
    assertEquals("2026", args.get("text"));
  }

  @Test
  public void decimalTextKeepsItsSpelling() {
    JSONObject args = JSONObject.parseObject("{\"text\":1.10}");
    assertTrue(CommandSchema.validate("wait_for_text", args, "wait_for_text").isEmpty());
    assertEquals("1.10", args.get("text"));
  }

  @Test
  public void popupTimeoutIsBounded() {
    JSONObject args = new JSONObject();
    args.put("selector", "#a");
    args.put("popupTimeoutMs", 999999);
    assertFalse(CommandSchema.validate("click_element_by_selector", args, "c").isEmpty());
  }

  @Test
  public void jsErrorsBecomePlainMessages() {
    Map<String, Object> reason = new LinkedHashMap<>();
    reason.put("reason", new IllegalStateException("TypeError: Failed to fetch\n    at window.fetch (x.js:1:1)"));
    List<Object> settled = new ArrayList<>();
    settled.add(reason);
    settled.add(1);
    Object plain = PlaywrightService.plainJsValue(settled, 0);
    Object error = ((Map<?, ?>) ((List<?>) plain).get(0)).get("reason");
    assertEquals("TypeError: Failed to fetch", ((Kv) error).getStr("error"));
    assertEquals(1, ((List<?>) plain).get(1));
  }

  @Test
  public void refusedCommandCarriesItsCodeAndHint() {
    CommandRefusedException refused = new CommandRefusedException(ActionError.BROWSER_CONFIG_CONFLICT, "differs", "use --port");
    assertEquals(ActionError.BROWSER_CONFIG_CONFLICT, refused.code());
    assertEquals("use --port", refused.hint());
    assertTrue(refused instanceof IllegalStateException);
  }
}
