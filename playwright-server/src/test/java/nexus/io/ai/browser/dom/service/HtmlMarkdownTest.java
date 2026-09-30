package nexus.io.ai.browser.dom.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link HtmlMarkdown} 的行为固定
 *
 * <p>
 * 全部是纯字符串用例,不起浏览器 —— 转换逻辑与页面无关,唯一的外部输入就是一段 HTML。
 */
public class HtmlMarkdownTest {

  /**
   * 表格必须转成 GFM 表格
   *
   * <p>
   * 这是这个命令存在的理由:{@code innerText} 会把单元格**逐行摊平**,列对应关系丢失。所以这里断言
   * 的不只是"文字还在",而是**表头在同一行、且有分隔行** —— 那才是行列关系被保住的证据。
   */
  @Test
  public void tableBecomesGfmTable() {
    String html = "<table>"
        + "<tr><th>地类</th><th>面积(公顷)</th></tr>"
        + "<tr><td>农用地</td><td>187.9883</td></tr>"
        + "<tr><td>建设用地</td><td>2.8867</td></tr>"
        + "<tr><td>未利用地</td><td>0.3431</td></tr>"
        + "</table>";
    String markdown = HtmlMarkdown.toMarkdown(html);

    assertTrue("表头没有落在同一行(GFM 表格要求 | a | b |):" + markdown,
        canonical(markdown).contains("|地类|面积(公顷)|"));
    assertTrue("缺少 GFM 分隔行:" + markdown, separatorLine(markdown));
    assertTrue("数据行没有保住列关系:" + markdown, canonical(markdown).contains("|农用地|187.9883|"));
    assertTrue("数据行没有保住列关系:" + markdown, canonical(markdown).contains("|未利用地|0.3431|"));
  }

  /** 合并单元格也要留下痕迹:colspan 的表头至少不能把后面的列错位 */
  @Test
  public void mergedTableCellsKeepColumnCount() {
    String html = "<table>"
        + "<tr><th colspan='2'>用途明细</th></tr>"
        + "<tr><th>用途名称</th><th>面积</th></tr>"
        + "<tr><td>公路用地</td><td>0.356529</td></tr>"
        + "</table>";
    String markdown = HtmlMarkdown.toMarkdown(html);

    assertTrue("用途明细丢了:" + markdown, canonical(markdown).contains("用途明细"));
    assertTrue("第二行表头没有保住列关系:" + markdown, canonical(markdown).contains("|用途名称|面积|"));
    assertTrue("数据行没有保住列关系:" + markdown, canonical(markdown).contains("|公路用地|0.356529|"));
  }

  /**
   * 脚本与样式不能进正文
   *
   * <p>
   * 这是最容易骗过调用方的一类:页面里一个内联 JSON 就能让 Markdown 开头出现几百行"正文"。
   */
  @Test
  public void scriptAndStyleAreDropped() {
    String html = "<html><head><style>.a{color:red}</style></head><body>"
        + "<script>window.__DATA__ = {\"secret\": \"不该出现在正文里\"};</script>"
        + "<p>真正的正文</p>"
        + "<noscript>请开启 JavaScript</noscript>"
        + "</body></html>";
    String markdown = HtmlMarkdown.toMarkdown(html);

    assertTrue("正文丢了:" + markdown, markdown.contains("真正的正文"));
    assertFalse("script 内容进了正文:" + markdown, markdown.contains("__DATA__"));
    assertFalse("script 内容进了正文:" + markdown, markdown.contains("不该出现在正文里"));
    assertFalse("style 内容进了正文:" + markdown, markdown.contains("color:red"));
    assertFalse("noscript 内容进了正文:" + markdown, markdown.contains("请开启 JavaScript"));
  }

  /**
   * 自己的高亮层必须被摘掉
   *
   * <p>
   * 老代码读正文前只把它 {@code display:none},而 {@code extract_markdown} 走的是 {@code innerHTML},
   * 隐藏样式对取值无效 —— 不真的删掉,高亮序号就会混进 Markdown。
   */
  @Test
  public void highlightContainerIsDropped() {
    String html = "<body><p>正文</p>"
        + "<div id='playwright-highlight-container'><div>[0] 登录</div><div>[1] 注册</div></div>"
        + "</body>";
    String markdown = HtmlMarkdown.toMarkdown(html);

    assertTrue("正文丢了:" + markdown, markdown.contains("正文"));
    assertFalse("高亮层进了正文:" + markdown, markdown.contains("[0] 登录"));
    assertFalse("高亮层进了正文:" + markdown, markdown.contains("playwright-highlight-container"));
  }

  /** 单个元素的 outerHTML(片段)也要能转,不能因为"没有 body"就转出空串 */
  @Test
  public void elementFragmentIsConverted() {
    String markdown = HtmlMarkdown.toMarkdown("<table><tr><th>甲</th><th>乙</th></tr>"
        + "<tr><td>1</td><td>2</td></tr></table>");

    assertTrue("片段没转出来:" + markdown, markdown.contains("| 甲 | 乙 |"));
    assertTrue("片段没转出来:" + markdown, markdown.contains("| 1 | 2 |"));
  }

  /** 空输入返回空串,不抛异常 */
  @Test
  public void emptyInputReturnsEmpty() {
    assertEquals("", HtmlMarkdown.toMarkdown(null));
    assertEquals("", HtmlMarkdown.toMarkdown(""));
  }

  /** 成片的空行压成一个:不携带信息,只烧 token */
  @Test
  public void blankLinesAreCollapsed() {
    assertEquals("甲\n\n乙", HtmlMarkdown.tidy("甲\n\n\n\n\n乙"));
    assertEquals("甲\n\n乙", HtmlMarkdown.tidy("\n\n甲\n\n乙\n\n\n"));
    assertEquals("", HtmlMarkdown.tidy("   \n\t\n  "));
  }

  /** 行尾空格会触发 Markdown 的"两空格 = 换行"语义,这里不需要 */
  @Test
  public void trailingSpacesAreStripped() {
    assertEquals("甲\n乙", HtmlMarkdown.tidy("甲   \n乙\t"));
  }

  /**
   * 把转换结果规范化,便于断言"列关系"
   *
   * <p>
   * 转换器会按列宽给单元格补空格做对齐({@code |  地类  |  面积(公顷)  |}),那是它排版的结果、
   * 不是内容差异。断言列关系时把这些**对齐用的空格**压掉,免得测试被"多一个空格"绑住 ——
   * 真正要钉住的是"表头在同一行"与"同一行的格子对应同一行数据"。
   */
  private static String canonical(String markdown) {
    StringBuilder sb = new StringBuilder();
    for (String line : markdown.split("\n", -1)) {
      sb.append(line.replaceAll("[ \t]+", " ").replace(" |", "|").replace("| ", "|").trim()).append('\n');
    }
    return sb.toString();
  }

  /** GFM 表格必须有分隔行(第二行形如 {@code |---|---|}) */
  private static boolean separatorLine(String markdown) {
    for (String line : markdown.split("\n", -1)) {
      String candidate = line.replaceAll("[ \t]+", "");
      if (candidate.matches("\\|(?:-{2,}\\|)+")) {
        return true;
      }
    }
    return false;
  }
}
