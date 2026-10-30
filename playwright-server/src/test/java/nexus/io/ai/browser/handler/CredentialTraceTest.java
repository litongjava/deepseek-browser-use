package nexus.io.ai.browser.handler;

import static org.junit.Assert.*;
import org.junit.Test;
import com.alibaba.fastjson2.JSONObject;

public class CredentialTraceTest {
  @Test public void credentialsAndTypedInputAreMaskedWithoutLosingStructure() {
    JSONObject record = JSONObject.parseObject("""
        {"method":"input_text_by_selector","params":{"selector":"#password","text":"fixture-password"},
         "data":{"headers":{"Authorization":"Bearer fixture-token"},"access_token":"fixture-token",
         "cookies":[{"name":"session","value":"fixture-cookie","domain":"example.test"}],"count":2}}
        """);
    CommandTraceLog.redactJson(record);
    assertFalse(record.toJSONString().contains("fixture-"));
    assertEquals("#password", record.getJSONObject("params").getString("selector"));
    assertEquals(2, record.getJSONObject("data").getIntValue("count"));
  }
  @Test public void sensitiveScriptsAreNotStored() {
    JSONObject record = JSONObject.parseObject("""
        {"method":"execute_js","params":{"sensitive":true,"body":"fetch('secret')","vars":{"x":"secret"}}}
        """);
    CommandTraceLog.redactJson(record);
    assertFalse(record.toJSONString().contains("fetch("));
  }
  @Test public void credentialsInsideBodyStringsAreMaskedInTrace() {
    JSONObject record = JSONObject.parseObject("""
        {"ok":true,"data":{"requests":[{"method":"POST","url":"https://app.example.test/api/providers",
         "postData":"{\\"id\\":\\"deepseek\\",\\"apiKey\\":\\"sk-test-1234567890abcdef\\"}"},
         {"method":"POST","postData":"user=alice&password=fixture-pass"}],
         "body":"{\\"data\\":{\\"refresh_token\\":\\"fixture-rt\\",\\"name\\":\\"visible\\"}}"}}
        """);
    CommandTraceLog.redactJson(record);
    String text = record.toJSONString();
    assertFalse(text.contains("sk-test-1234567890abcdef"));
    assertFalse(text.contains("fixture-"));
    assertTrue(text.contains("deepseek"));
    assertTrue(text.contains("user=alice"));
    assertTrue(text.contains("visible"));
  }
}
