package nexus.io.ai.browser.dom.service;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

/**
 * 把页面 HTML 转成 Markdown
 *
 * <p>
 * 这是给 {@code extract_markdown} 命令用的转换器,解决的是**读页面时表格散架**的问题:
 * {@code get_browser_state} 的结构化文本与 {@code extract_structured_data} 的正文都取自
 * {@code innerText},而 {@code innerText} 会把表格**按单元格逐行摊平** —— 表头与数据格交替出现,
 * 列与列的对应关系只能靠位置猜,合并单元格(colspan/rowspan)更是直接丢失。转成 Markdown 之后
 * 表格是 GFM 表格({@code | 表头 | 表头 |} + 分隔行 + 数据行),行列关系是显式的。
 *
 * <p>
 * <b>为什么要先过一遍 jsoup</b>:直接把手上的 HTML 丢给转换器也能出结果,但页面上有几类节点是
 * **不该进正文**的 ——
 * <ul>
 * <li>{@code <script>} / {@code <style>} / {@code <noscript>} / {@code <template>}:脚本与样式表的
 * 文本内容会被当成正文;<b>实测这是最容易骗过调用方的一类</b>,页面里一个内联 JSON 就能让
 * Markdown 开头出现几百行"正文"。</li>
 * <li>{@code #playwright-highlight-container}:那是 {@code get_browser_state} 画高亮框用的浮层,
 * 属于我们这个工具的产物,不是页面内容(老代码在读正文前只是把它 {@code display:none},
 * 对 {@code innerHTML} 取正文这条路无效,必须真的摘掉)。</li>
 * </ul>
 * jsoup 顺便把不闭合的标签补齐,转换器拿到的是一棵规整的树,输出更稳。
 *
 * <p>
 * <b>每次调用新建一个转换器</b>:{@code FlexmarkHtmlConverter} 内部持有可变的解析状态,而这个服务是
 * 并发处理请求的,共用一份实例要赌它线程安全 —— 这种赌注输了就是"偶发串页",很难查。转换本身只在
 * 调用 {@code extract_markdown} 时发生(不是热路径),每次新建的代价可以忽略。
 */
public class HtmlMarkdown {

  /** 高亮层是 get_browser_state 画的,不属于页面内容,转 Markdown 前要摘掉 */
  private static final String HIGHLIGHT_CONTAINER_ID = "playwright-highlight-container";

  /** 这些标签里的文本是脚本/样式,不是给人读的正文 */
  private static final String NON_CONTENT_SELECTOR = "script, style, noscript, template, head";

  /**
   * 把一段 HTML 转成 Markdown
   *
   * @param html 可以是整页 HTML、{@code document.body.innerHTML},也可以是单个元素的 {@code outerHTML}
   * @return Markdown 文本;{@code html} 为空时返回空串
   */
  public static String toMarkdown(String html) {
    if (html == null || html.isEmpty()) {
      return "";
    }
    Document doc = Jsoup.parse(html);
    doc.select(NON_CONTENT_SELECTOR).remove();
    doc.select("#" + HIGHLIGHT_CONTAINER_ID).remove();

    // body 为空说明传进来的是一段片段(例如单个元素的 outerHTML),那就用整棵树
    Element body = doc.body();
    String source = body != null && !body.html().isEmpty() ? body.html() : doc.html();

    FlexmarkHtmlConverter converter = FlexmarkHtmlConverter.builder().build();
    return tidy(converter.convert(source));
  }

  /**
   * 压掉转换结果里成片的空行
   *
   * <p>
   * 转换器对块级元素之间会保留原始换行,页面里常见的"每行一个标签"写法会转出大量空行:对模型来说
   * 这些空行不携带信息,只烧 token。压成"最多一个空行"——段落之间仍然分得开,不会把两段黏成一段。
   */
  static String tidy(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return "";
    }
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    List<String> kept = new ArrayList<>(lines.length);
    boolean previousBlank = false;
    for (String line : lines) {
      // 行尾空格会让 Markdown 里的"两个空格 = 换行"生效,这里不需要那种语义
      String trimmed = stripTrailing(line);
      boolean blank = trimmed.isEmpty();
      if (blank && previousBlank) {
        continue;
      }
      kept.add(trimmed);
      previousBlank = blank;
    }
    // 整段只有空白时不要返回一个换行
    int start = 0;
    int end = kept.size();
    while (start < end && kept.get(start).isEmpty()) {
      start++;
    }
    while (end > start && kept.get(end - 1).isEmpty()) {
      end--;
    }
    if (start >= end) {
      return "";
    }
    return String.join("\n", kept.subList(start, end));
  }

  private static String stripTrailing(String line) {
    int end = line.length();
    while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
      end--;
    }
    return end == line.length() ? line : line.substring(0, end);
  }
}
