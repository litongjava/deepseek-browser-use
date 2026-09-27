package nexus.io.ai.browser.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import nexus.io.tio.utils.json.Json;
import nexus.io.tio.utils.json.JsonUtils;

/**
 * 本项目对「序列化不输出 null 值字段」这个全局开关的接线
 *
 * <p>
 * 能力本身由框架提供（配置项 {@code tio.json.skipNull} / {@code Json.installSkipNull(..)}），
 * 这里钉住的是**接线正确**：项目自己的键能读到、默认是开、以及装上之后输出确实不带 null。
 * 框架自己的语义由框架侧的 {@code JsonSkipNullConfigTest} 钉。
 */
public class JsonResponsesTest {

  @After
  public void restoreGlobalState() {
    Json.uninstallSkipNull();
    System.clearProperty(JsonResponses.KEY_SKIP_NULL);
    System.clearProperty(JsonResponses.KEY_SKIP_NULL_FRAMEWORK);
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
  public void configKeysAreTheDocumentedOnes() {
    assertEquals("browser.json.skipNull", JsonResponses.KEY_SKIP_NULL);
    assertEquals("tio.json.skipNull", JsonResponses.KEY_SKIP_NULL_FRAMEWORK);
    assertTrue("本项目的默认值应当是「跳过 null」", JsonResponses.DEFAULT_SKIP_NULL);
  }

  /** 装上之后,响应那种形状的对象不再输出 null 值字段 */
  @Test
  public void installedSwitchDropsNullFields() {
    assertTrue("按默认配置应当生效", JsonResponses.init());
    String json = Json.getJson().toJson(responseLikeBody());
    assertFalse("null 值字段不该出现,实际:" + json, json.contains("null"));
    assertTrue("有值的字段要保留,实际:" + json, json.contains("playwright-server"));
    assertTrue("布尔与数字不受影响,实际:" + json, json.contains("\"ok\":true"));
  }

  /** 关掉时不会去拆别人装的东西,并且输出回到「带 null」 */
  @Test
  public void disabledSwitchLeavesOutputWithNulls() {
    System.setProperty(JsonResponses.KEY_SKIP_NULL, "false");
    assertFalse("配置说关就得关", JsonResponses.skipNullEnabled());
    assertFalse("关掉时 init 不该报生效", JsonResponses.init());
    String json = JsonUtils.toJson(responseLikeBody());
    assertTrue("输出要带 null,实际:" + json, json.contains("null"));
  }

  /**
   * 配置项真的能被读到:项目键优先,其次是框架键,都没有用默认值
   *
   * <p>
   * 配置来源有四条（命令行 / 配置文件 / JVM 参数 / 环境变量），这里走 JVM 参数那条 —— 它是
   * {@code EnvUtils} 明确会看的一环，也让"配置项名字写对没有"这件事可测。
   */
  @Test
  public void configIsReadFromBothKeysWithProjectKeyWinning() {
    assertTrue("没配置时用默认值(跳过)", JsonResponses.skipNullEnabled());

    System.setProperty(JsonResponses.KEY_SKIP_NULL_FRAMEWORK, "false");
    assertFalse("框架键也要认", JsonResponses.skipNullEnabled());

    System.setProperty(JsonResponses.KEY_SKIP_NULL, "true");
    assertTrue("两个键都在时,项目自己的键优先", JsonResponses.skipNullEnabled());

    System.setProperty(JsonResponses.KEY_SKIP_NULL, "not-a-boolean");
    assertFalse("写错值按「关」处理,不猜意图", JsonResponses.skipNullEnabled());
  }

  /** {@code active()} 回报的是事实(默认工厂的实际表现),不是配置的意图 */
  @Test
  public void activeReportsActualBehaviour() {
    Json.uninstallSkipNull();
    assertFalse("没装的时候不该说自己在跳过", JsonResponses.active());
    JsonResponses.init();
    assertTrue("装了之后要如实回报", JsonResponses.active());
  }
}
