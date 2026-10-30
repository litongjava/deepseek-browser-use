package nexus.io.ai.browser.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * Credential values inside request/response body text must be masked while the rest stays readable.
 */
public class CredentialRedactorTest {

  private static final String KEY = "sk-test-1234567890abcdef";

  @Test
  public void jsonBodyMasksNestedCredentialsAndKeepsOtherFields() {
    String body = "{\"id\":\"deepseek\",\"apiKey\":\"" + KEY + "\",\"baseUrl\":\"https://api.example.test\","
        + "\"price\":1.50,\"enabled\":true,\"auth\":{\"password\":\"fixture-pass\",\"access_token\":\"fixture-at\","
        + "\"user\":\"alice\"},\"list\":[{\"name\":\"Authorization\",\"value\":\"Bearer fixture-bearer\"},"
        + "{\"client_secret\":\"fixture-cs\",\"token\":\"fixture-tok\"}],\"note\":null}";
    String redacted = CredentialRedactor.redactBody(body);
    assertFalse(redacted.contains(KEY));
    assertFalse(redacted.contains("fixture-"));
    JSONObject json = JSON.parseObject(redacted);
    assertEquals("deepseek", json.getString("id"));
    assertEquals("https://api.example.test", json.getString("baseUrl"));
    assertEquals("1.50", json.getBigDecimal("price").toPlainString());
    assertTrue(json.getBooleanValue("enabled"));
    assertTrue(json.containsKey("note"));
    assertEquals("alice", json.getJSONObject("auth").getString("user"));
    assertEquals(CredentialRedactor.MASK, json.getString("apiKey"));
    assertEquals(CredentialRedactor.MASK, json.getJSONObject("auth").getString("password"));
    assertEquals("Authorization", json.getJSONArray("list").getJSONObject(0).getString("name"));
  }

  @Test
  public void formBodyMasksOnlyCredentialPairs() {
    String body = "provider=deepseek&api_key=" + KEY + "&user=alice&pass%77ord=fixture-pass&empty=&x-api-key=fixture-x";
    String redacted = CredentialRedactor.redactBody(body);
    assertEquals("provider=deepseek&api_key=***&user=alice&pass%77ord=***&empty=&x-api-key=***", redacted);
  }

  @Test
  public void nonJsonTextAndPlainJsonAreUntouched() {
    String text = "please enter your password below = thanks";
    assertSame(text, CredentialRedactor.redactBody(text));
    String json = "{ \"id\": \"deepseek\", \"tokenCount\": 12 }";
    assertSame(json, CredentialRedactor.redactBody(json));
    String form = "q=token+bucket&page=2";
    assertSame(form, CredentialRedactor.redactBody(form));
    assertSame(null, CredentialRedactor.redactBody(null));
  }

  @Test
  public void truncatedJsonFallsBackToQuotedPairs() {
    String body = "{\"id\":\"deepseek\",\"apiKey\":\"" + KEY + "\",\"baseUrl\":\"https://api.exa";
    String redacted = CredentialRedactor.redactBody(body);
    assertEquals("{\"id\":\"deepseek\",\"apiKey\":\"***\",\"baseUrl\":\"https://api.exa", redacted);
  }

  @Test
  public void jsonEmbeddedAsStringIsMasked() {
    String body = "{\"payload\":\"{\\\"apiKey\\\":\\\"" + KEY + "\\\"}\",\"ok\":1}";
    String redacted = CredentialRedactor.redactBody(body);
    assertFalse(redacted.contains(KEY));
    assertEquals(1, JSON.parseObject(redacted).getIntValue("ok"));
  }
}
