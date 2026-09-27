package nexus.io.ai.browser.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.PlaywrightService.SharedBrowser;
import nexus.io.chromium.cdp.CdpBrowser;
import nexus.io.chromium.cdp.CdpException;

/**
 * 用 CDP 把「走 CDP 就会丢掉的那些 launch 期设置」补回来
 *
 * <p>
 * <b>为什么需要它。</b>走 Playwright 的 {@code launchPersistentContext} 时,下载目录、权限、UA
 * 这些是**创建上下文时一次性给好的**(见 {@code PlaywrightService.buildOptions})。换成「自己拉进程 +
 * {@code --remote-debugging-port}」之后没有那个时机了,不补的话就会**静默退化**:下载落到浏览器自己的
 * 目录、剪贴板权限没授、站点侧行为悄悄变了 —— 而回执照样 {@code ok:true}。实测记录在
 * {@code README} 里(CDP 那条路「页面触发的下载落到浏览器自己的下载目录」)。
 *
 * <p>
 * 这里是 {@code nexus.io.chromium.cdp} 这个独立库在服务端的**第一个真实使用者**:它证明那条路
 * 自己接得上、发得出命令、收得到回执。补设置走浏览器级命令({@code Browser.*}),不需要挂到某个页签上。
 *
 * <p>
 * <b>全部 best-effort</b>:任何一项失败只记警告并写进 {@link Facts#notes()} 一起回给调用方,
 * **绝不让 start 失败**。理由很直接:这些是锦上添花的设置,而 start 失败会让整个浏览器不可用 ——
 * 用「少一个下载目录设置」去换「整个任务起不来」是明显的亏本买卖。补不上时调用方看得到 note。
 */
@Slf4j
final class CdpLaunchSupport {

  /** 连接 CDP 端点的超时(毫秒);浏览器刚拉起来,端点已经是通的,不需要等太久 */
  private static final long CONNECT_TIMEOUT_MS = 10_000;

  /** 单条 CDP 命令的回执超时(毫秒) */
  private static final long COMMAND_TIMEOUT_MS = 15_000;

  /** 与 {@code PlaywrightService.downloadsDir()} 保持一致:两条路的下载落点必须是同一个 */
  private static final String DOWNLOADS_SUBDIR = "broswer";

  /**
   * 要授予的权限
   *
   * <p>
   * <b>注意这不是 Playwright 那一套名字。</b>Playwright 的 {@code setPermissions} 收的是
   * {@code clipboard-read} / {@code clipboard-write},而 CDP 的 {@code Browser.grantPermissions}
   * 认的是它自己 {@code PermissionType} 枚举里的 {@code clipboardReadWrite} /
   * {@code clipboardSanitizedWrite}。照抄 Playwright 的名字会得到
   * {@code Unknown permission type: clipboard-read} —— 而且这类错误如果被忽略,表现就是「剪贴板按钮
   * 静默失效」,在站点侧完全看不出是权限问题。实测确认(见 {@code CdpClientTest})。
   */
  private static final List<String> PERMISSIONS = List.of("clipboardReadWrite", "clipboardSanitizedWrite",
      "notifications");

  private CdpLaunchSupport() {
  }

  /**
   * 补设置的结果:CDP 报回来的浏览器身份 + 每一项设置的实际结果
   *
   * @param product        形如 {@code Chrome/154.0.8037.57};连不上时为 null
   * @param protocolVersion CDP 协议版本,例如 {@code 1.3}
   * @param notes          每一项的人话结论(成功与失败都写),进回执便于排查
   */
  record Facts(String product, String protocolVersion, List<String> notes) {
  }

  /**
   * 连上刚拉起来的浏览器,把 launch 期设置补齐
   *
   * @param endpoint 形如 {@code http://127.0.0.1:6573},来自 {@code ChromeLauncher}
   * @return 补设置的结果;连不上时返回 null(调用方据此知道「这次没拿到 CDP 身份」)
   */
  static Facts apply(String endpoint, BrowserChoice type, Path profileDir, boolean userProfile) {
    CdpBrowser browser;
    try {
      browser = CdpBrowser.connect(endpoint, CONNECT_TIMEOUT_MS, COMMAND_TIMEOUT_MS);
    } catch (CdpException e) {
      // 连不上不是致命的:命令仍然由 Playwright 那条 CDP 连接执行,只是少了几项补充设置
      log.warn("CDP 库连不上 {}（不影响 start，命令仍由 Playwright 的 CDP 会话执行）：{}", endpoint, e.getMessage());
      return null;
    }

    try {
      List<String> notes = new ArrayList<>();
      JSONObject version = browser.version();
      String product = version.getString("product");
      String protocolVersion = version.getString("protocolVersion");
      log.info("CDP 已连上:browser={}, product={}, protocolVersion={}, profile={}", type.id(), product,
          protocolVersion, profileDir);

      applyDownloadBehavior(browser, notes);
      applyPermissions(browser, notes);

      return new Facts(product, protocolVersion, notes);
    } catch (CdpException e) {
      log.warn("CDP 补设置时出错（不影响 start）：{}", e.getMessage());
      return new Facts(null, null, List.of("CDP 补设置出错:" + e.getMessage()));
    } finally {
      try {
        browser.close();
      } catch (RuntimeException e) {
        log.debug("关闭 CDP 补设置用的连接失败:{}", e.getMessage());
      }
    }
  }

  /**
   * 下载目录
   *
   * <p>
   * 这条路**没有** {@code setDownloadsPath},不补的话页面触发的下载会落到浏览器自己的默认下载目录,
   * 而调用方还在 {@code ~/Downloads/broswer} 里数文件({@code countDownloadFiles})—— 于是「点了下载
   * 却什么都没发生」。
   */
  private static void applyDownloadBehavior(CdpBrowser browser, List<String> notes) {
    Path downloads = Paths.get(System.getProperty("user.home", "."), "Downloads", DOWNLOADS_SUBDIR);
    try {
      browser.setDownloadBehavior(downloads);
      notes.add("下载目录:" + downloads + "(Browser.setDownloadBehavior)");
    } catch (CdpException e) {
      notes.add("下载目录设置失败(下载会落到浏览器自己的目录):" + e.getMessage());
      log.warn("用 CDP 设置下载目录失败(不影响 start):{}", e.getMessage());
    }
  }

  /**
   * 权限
   *
   * <p>
   * 不授的话 {@code navigator.clipboard.readText()} 会直接抛权限错误,而页面上「复制/粘贴」类的按钮
   * 常常静默失败 —— 这类失败在站点侧看不出是权限问题。
   */
  private static void applyPermissions(CdpBrowser browser, List<String> notes) {
    try {
      browser.grantPermissions(PERMISSIONS, null);
      notes.add("已授权限:" + String.join(" / ", PERMISSIONS));
    } catch (CdpException e) {
      notes.add("授权限失败(剪贴板类操作会不可用):" + e.getMessage());
      log.warn("用 CDP 授权限失败(不影响 start):{}", e.getMessage());
    }
  }

  /** 把补设置的结果写回共享浏览器,让 {@code start} 与 {@code get_config} 能如实回报 */
  static void record(SharedBrowser shared, Facts facts) {
    if (shared == null || facts == null) {
      return;
    }
    shared.cdpProduct = facts.product();
    shared.cdpProtocolVersion = facts.protocolVersion();
    shared.cdpNotes = facts.notes();
  }
}
