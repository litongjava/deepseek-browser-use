package nexus.io.ai.browser.json;

import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import nexus.io.tio.utils.json.JsonUtils;

/**
 * 基线：**不加载** {@link JsonResponses} 时，默认行为是带 null 输出的
 *
 * <p>
 * 这条用例必须能在不碰工具类的 JVM 里单独跑（例如
 * {@code java -cp … org.junit.runner.JUnitCore nexus.io.ai.browser.json.DefaultJsonNullBaselineTest}）——
 * 它证明的是「框架的默认行为没变，改动只发生在本项目装上包装工厂之后」，而不是"我以为它没变"。
 *
 * <p>
 * 所以这个类**不许**出现 {@link JsonResponses}，也不许依赖 {@code PlaywrightAppConfig}。
 */
public class DefaultJsonNullBaselineTest {

  @Test
  public void defaultJsonKeepsNullFields() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.put("msg", null);
    String json = JsonUtils.toJson(body);
    assertTrue("默认实现应当照旧输出 null 值字段,实际:" + json, json.contains("null"));
  }
}
