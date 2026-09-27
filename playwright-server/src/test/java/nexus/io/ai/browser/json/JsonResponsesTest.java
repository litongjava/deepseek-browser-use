package nexus.io.ai.browser.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import nexus.io.tio.utils.json.IJsonFactory;
import nexus.io.tio.utils.json.Json;
import nexus.io.tio.utils.json.JsonUtils;

/**
 * 「响应不输出 null 值字段」这个工具类的行为
 *
 * <p>
 * 要钉住的是三件事:
 *
 * <ol>
 * <li>装上之后,本项目里走 {@code Json.getJson()} 的序列化点(HTTP 响应体就走它)不再输出 null 值字段;</li>
 * <li>**解析路径不受影响** —— 跳过 null 只该管"写出去的东西",读进来的报文照旧;</li>
 * <li>默认工厂是静态、进程级的,所以这个用例必须**自己收拾干净**:跑完把原来的工厂装回去,
 * 否则会污染同一个 JVM 里的其它用例(它们可能正断言默认行为)。</li>
 * </ol>
 */
public class JsonResponsesTest {

  /** 进这个类之前的原始工厂:跑完要还原,不能把全局状态漏出去 */
  private static IJsonFactory originalFactory;

  @BeforeClass
  public static void captureOriginalFactory() {
    originalFactory = Json.getJsonFactory();
    JsonResponses.install(true);
  }

  @AfterClass
  public static void restoreOriginalFactory() {
    if (originalFactory != null) {
      Json.setDefaultJsonFactory(originalFactory);
    }
  }

  @After
  public void keepSkipNullForNextCase() {
    // 每个用例都可能改过工厂,下一个用例默认还是"跳过 null"这一档
    JsonResponses.install(true);
  }

  private static Map<String, Object> responseLikeBody() {
    // 形状照着 RespBodyVo 来:ok/code 有值,msg/error 为 null
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.put("code", 1);
    body.put("msg", null);
    body.put("error", null);
    body.put("data", Map.of("name", "playwright-server"));
    return body;
  }

  @Test
  public void installedFactoryDropsNullFields() {
    String json = Json.getJson().toJson(responseLikeBody());
    assertFalse("装好之后 null 值字段不该出现,实际:" + json, json.contains("null"));
    assertTrue("有值的字段要保留,实际:" + json, json.contains("playwright-server"));
    assertTrue("布尔与数字不受影响,实际:" + json, json.contains("\"ok\":true"));
  }

  /** 解析不该受"跳过 null"影响:换的是输出侧,读进来的报文照旧解析 */
  @Test
  public void parsingIsNotAffected() {
    Object parsed = Json.getJson().parse("{\"ok\":true,\"msg\":null}");
    assertTrue("解析结果应当是 Map,实际:" + parsed, parsed instanceof Map);
    Map<?, ?> map = (Map<?, ?>) parsed;
    assertEquals(Boolean.TRUE, map.get("ok"));
    assertTrue("被解析的报文里 null 字段要照旧读出来", map.containsKey("msg"));
  }

  /** 关掉开关时是**空操作**:已经装上的工厂不动(要还原得自己重新 setDefaultJsonFactory) */
  @Test
  public void disabledSwitchIsANoOp() {
    JsonResponses.installSkipNullFactory();
    IJsonFactory before = Json.getJsonFactory();
    assertFalse("显式传 false 表示这次不装,返回 false", JsonResponses.install(false));
    assertSame("关掉开关不该悄悄把工厂换掉", before, Json.getJsonFactory());
    String json = Json.getJson().toJson(responseLikeBody());
    assertFalse("既然工厂没被换掉,输出照旧跳过 null,实际:" + json, json.contains("null"));
  }

  /** 重复装不会层层包装(每次包的都只是"当前工厂",不是包上一层皮) */
  @Test
  public void installingTwiceIsIdempotent() {
    JsonResponses.installSkipNullFactory();
    JsonResponses.installSkipNullFactory();
    String json = Json.getJson().toJson(responseLikeBody());
    assertFalse("装两次也只是一层包装,实际:" + json, json.contains("null"));
  }

  @Test
  public void configKeyIsTheDocumentedOne() {
    assertEquals("browser.json.skipNull", JsonResponses.KEY_SKIP_NULL);
  }
}
