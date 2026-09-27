package nexus.io.ai.browser.json;

import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.Json;

/**
 * 「序列化不输出 null 值字段」这个全局开关的读取与回报
 *
 * <p>
 * 能力由**框架**提供:配置项 {@code tio.json.skipNull} 打开后,框架会把默认工厂包一层,让
 * {@code Json.getJson()} 也返回跳过 null 的实现(实现见框架的 {@code Json#buildFactory()},
 * 包装只覆盖输出、解析一律委托)。框架里默认关,所以不配置的项目行为一点不变。
 *
 * <p>
 * 本项目要的默认是**开**:{@code /playwright/command} 每一条回执都带 {@code "msg":null}、
 * {@code "error":null},批量回执里更是每一步一份,对读回执的模型与脚本全是噪声。所以这里提供项目
 * 自己的键 {@link #KEY_SKIP_NULL}(默认 true)来驱动同一个开关,并把它回报到启动日志里 ——
 * 配置有没有生效,一眼就能看到。
 *
 * <p>
 * <b>边界</b>:开关是**进程级**的。打开后,同一个进程里被依赖进来的框架模块也会一起跳过 null,
 * 其中要留意的是用 {@code Json.getJson()} 拼**出站请求体**的地方(通知、HTTP 工具等)。本服务不发出站
 * 通知,所以可以接受;需要"必须显式传 null"的报文时,把 {@link #KEY_SKIP_NULL} 设为 {@code false}。
 */
public final class JsonResponses {

  /**
   * 项目自己的开关(优先):是否让序列化跳过 null 值字段
   *
   * <p>
   * 默认 true。写进 {@code app.properties},或用命令行 / JVM 参数 / 环境变量覆盖 —— 四条来源的优先级
   * 由 {@code EnvUtils} 统一决定。
   */
  public static final String KEY_SKIP_NULL = "browser.json.skipNull";

  /** 框架自己的开关(不写项目键时用它),取值只认 {@code true}(忽略大小写) */
  public static final String KEY_SKIP_NULL_FRAMEWORK = Json.KEY_SKIP_NULL;

  /** 没配置时的默认值:跳过 null */
  public static final boolean DEFAULT_SKIP_NULL = true;

  private JsonResponses() {
  }

  /**
   * 把开关交给框架,并把"这次到底生效没有"回报出来
   *
   * <p>
   * 开关的**执行**在框架里({@link Json#installSkipNull(boolean)});这里只做两件事:读项目自己的配置,
   * 再回读一次实际表现。放在启动时调用,所以不受"默认工厂静态初始化只读一次配置"的限制。
   *
   * <p>
   * 关掉时不会拆掉别人装的东西(框架那边只还原"由代码装上的那一层"),所以这里可以放心幂等调用。
   *
   * @return 这次生效的是"跳过 null"(true)还是"带 null 输出"(false)
   */
  public static boolean init() {
    return Json.installSkipNull(skipNullEnabled());
  }

  /**
   * 项目配置说了算:{@link #KEY_SKIP_NULL} 优先,其次框架键,都没有则用 {@link #DEFAULT_SKIP_NULL}
   *
   * <p>
   * 取值只有 {@code true}(忽略大小写)算开,其余一律算关 —— 写错值时"关掉"比"悄悄开着重排了报文"安全。
   */
  public static boolean skipNullEnabled() {
    String value = EnvUtils.getStr(KEY_SKIP_NULL);
    if (isBlank(value)) {
      value = EnvUtils.getStr(KEY_SKIP_NULL_FRAMEWORK);
    }
    if (isBlank(value)) {
      return DEFAULT_SKIP_NULL;
    }
    return Boolean.parseBoolean(value.trim());
  }

  /** 当前默认工厂是不是真的在跳过 null(配置是意图,这才是事实) */
  public static boolean active() {
    return Json.isSkipNull();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
