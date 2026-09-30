package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.sun.net.httpserver.HttpServer;

import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.model.body.RespBodyVo;

/**
 * 「读取侧」能力的回归测试:{@code find_text}、{@code list_tables}、{@code download_image}、
 * {@code get_browser_state} 的大图提示,以及失败回执里的地址
 *
 * <p>这几条都是**实操踩坑之后补的**,坑在真实站点上很难稳定复现,所以这里用 fixture 页面把最小形态钉住:
 * <ul>
 * <li><b>找一行不必拉整页</b>:{@code find_text} 要回命中、前后文与行号,并且**不裁剪上下文**;</li>
 * <li><b>表格能被找到并被指定</b>:{@code list_tables} 给出的 {@code selector} 要能**原样**填进
 * {@code extract_markdown},包括 {@code nth} 指定第几张表 —— 实测政府站的搜索分页上,两个链接的
 * href 完全一样,没有 nth 就只能猜服务端挑了哪个;</li>
 * <li><b>图就是数据</b>:公告附件是图片时,{@code innerText} 一个字都读不到,要能把**原始文件**取下来
 * (不是截屏幕),并且回执里的 {@code path} 能直接喂给 {@code ocr_image};</li>
 * <li><b>失败也要说清"现在在哪"</b>:失败回执带 {@code urlAfter},调用方不必再补一次 {@code get_url}。</li>
 * </ul>
 */
public class BrowserReadingUpgradeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;
  private static Path uploadDir;
  private static Path dataRoot;
  private static byte[] png;

  /** fixture:一段可搜索的正文 + 两张表(第二张才是要的)+ 一张大图(≥200×200,模拟扫描件附件) */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>reading</title></head><body>
        <div id="content">
          <p>项目名称：沿黄高速民权至兰考段项目（民权段）</p>
          <p>批准文号：自然资函〔2022〕955号</p>
          <p>征地补偿安置方案公告（2022年1月26日）</p>
          <table id="meta"><tr><td>索引号：</td><td>001</td></tr></table>
          <table class="detail">
            <tr><th>地类</th><th>面积</th></tr>
            <tr><td>农用地</td><td>187.9883</td></tr>
            <tr><td>未利用地</td><td>0.3431</td></tr>
          </table>
        </div>
        <img id="scan" src="/pic.png" alt="建设用地明细表">
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-reading-profile");
    uploadDir = Files.createTempDirectory("browser-use-reading-upload");
    dataRoot = Files.createTempDirectory("browser-use-reading-data");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    System.setProperty(UploadStore.KEY_DIR, uploadDir.toString());
    System.setProperty(PlaywrightService.KEY_ACTION_TIMEOUT, "900");
    ChromeBrowser.resetForTests();

    png = largePng(240, 180);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      boolean image = path.startsWith("/pic.png");
      byte[] bytes = image ? png : PAGE.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", image ? "image/png" : "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();

    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() {
    if (id != null) {
      try {
        service.close(id);
      } catch (RuntimeException ignored) {
        // 收尾,关不掉也无所谓
      }
    }
    if (server != null) {
      server.stop(0);
    }
    System.clearProperty(PlaywrightService.MAX_CHARS_KEY);
    ChromeBrowser.resetForTests();
  }

  /** 造一张真 PNG:大图提示看的是 naturalWidth/Height,给个 1×1 的图什么都测不出来 */
  private static byte[] largePng(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.setColor(Color.BLACK);
    g.drawRect(4, 4, width - 9, height - 9);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static Kv data(RespBodyVo response) {
    assertTrue("命令应当成功,实际:" + response.getMsg(), response.isOk());
    Object data = response.getData();
    return data instanceof Kv ? (Kv) data : new Kv();
  }

  private void open() {
    TestFlakeGuard.retry("open", () -> {
      service.getInstance(id).page.navigate(base);
      service.getInstance(id).page.waitForTimeout(200);
    });
  }

  // ==================== find_text ====================

  /** 命中要带回前后文与行号,而且不裁剪:调用方正是为了省上下文才用它 */
  @Test
  public void findTextReturnsContextAndLine() {
    open();
    Kv result = data(service.findText(id, "未利用地", false, 40, 5, null, null));

    assertEquals("命中数不对", 1, intOf(result, "matchCount"));
    assertEquals("正文来源应当是 body", "body", result.getStr("source"));
    @SuppressWarnings("unchecked")
    List<Kv> matches = (List<Kv>) result.get("matches");
    assertNotNull(matches);
    assertEquals(1, matches.size());
    Kv first = matches.get(0);
    assertEquals("命中的词要原样带回来", "未利用地", first.getStr("match"));
    assertTrue("命中行号应当 > 0", intOf(first, "line") > 0);
    assertTrue("前文要包含命中附近的内容,实际:" + first.getStr("before"),
        first.getStr("before").contains("农用地"));
    assertTrue("后文要带出后面的字符,实际:" + first.getStr("after"), first.getStr("after").contains("0.3431"));
  }

  /** 正则:一次把几个词都找出来 */
  @Test
  public void findTextSupportsRegex() {
    open();
    Kv result = data(service.findText(id, "955号|1月26日", true, 10, 10, null, null));
    assertEquals("两处命中", 2, intOf(result, "matchCount"));
    assertEquals(true, result.get("regex"));
  }

  /** 找不到就说找不到,并给出"为什么可能找不到"的提示(图里的字、iframe、选择器范围) */
  @Test
  public void findTextExplainsNoHit() {
    open();
    Kv result = data(service.findText(id, "这句肯定不存在", false, 20, 5, null, null));
    assertEquals(0, intOf(result, "matchCount"));
    assertNotNull("没有命中时应当给出排查提示", result.getStr("hint"));
  }

  /** 正则写错要当场报错,不能悄悄当成字面量再搜一遍 */
  @Test
  public void findTextRejectsBadRegex() {
    open();
    RespBodyVo response = service.findText(id, "(", true, 20, 5, null, null);
    assertFalse("非法正则应当失败", response.isOk());
    assertTrue("失败原因要指向正则,实际:" + response.getMsg(), response.getMsg().contains("正则"));
  }

  /** 只在指定元素里找 */
  @Test
  public void findTextRespectsSelector() {
    open();
    Kv inside = data(service.findText(id, "未利用地", false, 10, 5, ".detail", null));
    assertEquals(1, intOf(inside, "matchCount"));
    assertEquals(".detail", inside.getStr("source"));

    Kv outside = data(service.findText(id, "未利用地", false, 10, 5, "#meta", null));
    assertEquals("范围外不该命中", 0, intOf(outside, "matchCount"));
  }

  // ==================== list_tables ====================

  /** 列出的 selector 必须能**原样**交给 extract_markdown,否则这条命令只是好看 */
  @Test
  public void listTablesGivesUsableSelector() {
    open();
    Kv result = data(service.listTables(id, null));
    assertEquals("页面上有两张表", 2, intOf(result, "count"));

    @SuppressWarnings("unchecked")
    List<Kv> tables = (List<Kv>) result.get("tables");
    assertEquals("table >> nth=0", tables.get(0).getStr("selector"));
    assertEquals("table >> nth=1", tables.get(1).getStr("selector"));
    assertEquals("detail", tables.get(1).getStr("className"));
    assertTrue("行数要报出来", intOf(tables.get(1), "rows") >= 3);
    assertTrue("预览要能看出这是哪张表,实际:" + tables.get(1).getStr("preview"),
        tables.get(1).getStr("preview").contains("农用地"));

    // 原样填回去
    Kv markdown = data(service.extractMarkdown(id, tables.get(1).getStr("selector"), null, false, 0, null));
    assertTrue("用 list_tables 给的 selector 应当能直接转出这张表:" + markdown.getStr("markdown"),
        canonical(markdown.getStr("markdown")).contains("|农用地|187.9883|"));
  }

  // ==================== extract_markdown 的 nth ====================

  /** nth 指定第几张表:不指定时取第一张(元数据表),指定 1 才是地类表 */
  @Test
  public void extractMarkdownNthPicksTheRequestedTable() {
    open();
    Kv first = data(service.extractMarkdown(id, "table", null, false, 0, null));
    assertTrue("不传 nth 取第一张(元数据表):" + first.getStr("markdown"),
        first.getStr("markdown").contains("索引号"));

    Kv second = data(service.extractMarkdown(id, "table", null, false, 0, 1));
    assertTrue("nth=1 应当拿到第二张(地类表):" + second.getStr("markdown"),
        canonical(second.getStr("markdown")).contains("|未利用地|0.3431|"));
    assertEquals("source 要写明这次用的是第几个", "table >> nth=1", second.getStr("source"));
  }

  /** nth 越界要说清总数,而不是等到可操作性超时 */
  @Test
  public void extractMarkdownNthOutOfRangeReportsTotal() {
    open();
    RespBodyVo response = service.extractMarkdown(id, "table", null, false, 0, 9);
    assertFalse(response.isOk());
    assertTrue("越界原因要带总数,实际:" + response.getMsg(), response.getMsg().contains("2"));
  }

  // ==================== download_image ====================

  /** 落盘的必须是**原始文件**(字节数、摘要一致),而不是屏幕截图 */
  @Test
  public void downloadImageLandsOriginalBytes() throws Exception {
    open();
    Kv result = data(service.downloadImage(id, null, "#scan", null, "mingxi.png"));

    assertEquals("原始字节数要一致(截图不会是这么多)", (long) png.length, longOf(result, "size"));
    assertEquals("摘要要和服务端算的一致", UploadStore.sha256(png), result.getStr("sha256"));
    assertEquals("文件名会被保留", "mingxi.png", result.getStr("filename"));
    assertEquals("image/png", result.getStr("contentType"));
    assertEquals("pixels", "240", String.valueOf(intOf(result, "naturalWidth")));
    assertTrue("srcUrl 要是绝对地址", String.valueOf(result.getStr("srcUrl")).startsWith(base));

    Path landed = Paths.get(result.getStr("path"));
    assertTrue("文件要真的在", Files.isRegularFile(landed));
    assertEquals(png.length, Files.readAllBytes(landed).length);
    assertNotNull("落在 data/<id>/ 下时应当给出可直接 GET 的 url", result.getStr("url"));
    assertTrue(String.valueOf(result.getStr("url")).startsWith("/data/"));

    Files.deleteIfExists(landed);
  }

  /** 既没给 index 也没给 selector:报错要指名道姓,不能静默 */
  @Test
  public void downloadImageNeedsATarget() {
    open();
    RespBodyVo response = service.downloadImage(id, null, null, null, null);
    assertFalse(response.isOk());
    assertTrue("要提示需要 index 或 selector,实际:" + response.getMsg(),
        response.getMsg().contains("index") && response.getMsg().contains("selector"));
  }

  // ==================== 大图提示 ====================

  /** 页面上有 ≥200×200 的图时,快照要主动提示"内容可能在图里" */
  @Test
  public void browserStateHintsAboutLargeImages() {
    open();
    Kv state = data(service.getBrowserState(id, Boolean.FALSE, 0, Boolean.FALSE, 0, Boolean.FALSE));
    assertTrue("应当报出大图数量", intOf(state, "mediaCount") >= 1);
    assertNotNull("应当给出下一步怎么做", state.getStr("mediaHint"));
    assertTrue("提示里要指出该用哪两条命令", state.getStr("mediaHint").contains("download_image")
        && state.getStr("mediaHint").contains("ocr_image"));
  }

  // ==================== 失败回执带上"现在在哪" ====================

  /** 失败回执里没有地址,调用方就没法判断"这条命令到底动没动页面" */
  @Test
  public void failureReceiptCarriesCurrentLocation() {
    open();
    RespBodyVo response = actions.execute(id, "click_element_by_selector",
        com.alibaba.fastjson2.JSON.parseObject("{\"selector\":\"#nope\"}"));
    assertFalse("选择器一个都不命中时应当失败", response.isOk());
    Kv data = response.getData() instanceof Kv ? (Kv) response.getData() : new Kv();
    assertEquals("失败回执要带当前地址,免得再补一次 get_url", base + "/", data.getStr("urlAfter"));
    assertNotNull(data.getStr("titleAfter"));
  }

  /**
   * 压掉 Markdown 表格里**对齐用的空格**,便于断言列关系
   *
   * <p>
   * 转换器按列宽补空格({@code |  农用地  | 187.9883 |}),那是排版结果、不是内容差异。
   * 这里钉住的应该是"同一行的格子对应同一行数据",不该被"多一个空格"绑住。
   */
  private static String canonical(String markdown) {
    StringBuilder sb = new StringBuilder();
    for (String line : markdown.split("\n", -1)) {
      sb.append(line.replaceAll("[ \t]+", " ").replace(" |", "|").replace("| ", "|").trim()).append('\n');
    }
    return sb.toString();
  }

  private static int intOf(Kv kv, String key) {    Object value = kv.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  private static long longOf(Kv kv, String key) {
    Object value = kv.get(key);
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }
}
