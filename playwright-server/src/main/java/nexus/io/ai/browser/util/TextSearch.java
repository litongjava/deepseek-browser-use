package nexus.io.ai.browser.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.jfinal.kit.Kv;

/**
 * 在一段文本里找东西,并带回上下文
 *
 * <p>
 * <b>为什么服务端要提供这个</b>:读页面一直是"整页拉回调用方"——{@code get_browser_state} 的
 * {@code data.text}、{@code extract_structured_data} 的正文、{@code extract_markdown} 的结果,
 * 动辄几万字符。而调用方想知道的往往只是**其中一行**:某个文号在不在、某个乡镇有哪几行、某个关键词
 * 出现在哪里。为了这一行把整页塞进上下文,既慢又贵(实测一份政府公告页整页 3.7 万字符,只为找"文号"两字)。
 *
 * <p>
 * 所以把"找"这一步放到服务端:调用方给一个词或一条正则,拿回**命中位置与前后文**,按条数封顶。
 * 匹配逻辑是纯函数,不碰浏览器,可以单独测。
 *
 * <p>
 * 三条边界值得写清:
 * <ul>
 * <li><b>正则非法直接报错</b>,不做"当成字面量再试一次"的兜底 —— 那会让一个写错的正则静默变成
 * 一次字面量搜索,调用方以为自己的正则生效了;</li>
 * <li><b>不重叠匹配</b>:命中后从该次匹配的末尾继续找,{@code aaa} 里找 {@code aa} 只算 1 次
 * (否则会得到一堆互相咬住的片段,上下文全糊在一起);</li>
 * <li><b>空匹配(如正则 {@code a*})要往前推一格</b>,否则会在同一个位置无限循环。</li>
 * </ul>
 */
public final class TextSearch {

  /** 每处命中默认带回的前后文字数 */
  public static final int DEFAULT_CONTEXT_CHARS = 80;

  /** 默认最多回几条命中:再多就不是"看一眼",而是在灌上下文了 */
  public static final int DEFAULT_MAX_MATCHES = 20;

  /** 单条上下文的上限,防止有人传个很大的 contextChars 把回执撑爆 */
  private static final int MAX_CONTEXT_CHARS = 2000;

  private TextSearch() {
  }

  /**
   * 在 {@code text} 里找 {@code needle}
   *
   * @param text         被搜索的文本(可为 null,视为空)
   * @param needle       要找的词;{@code regex} 为 true 时按正则解释
   * @param regex        是否按正则解释
   * @param contextChars 每处命中带回的前后文字数,{@code <=0} 时用 {@link #DEFAULT_CONTEXT_CHARS}
   * @param maxMatches   最多回几条,{@code <=0} 时用 {@link #DEFAULT_MAX_MATCHES}
   * @return {@code {matchCount, returned, truncated, matches:[{index, line, match, before, after}]}}
   * @throws IllegalArgumentException 正则为空或写法非法
   */
  public static Kv find(String text, String needle, boolean regex, int contextChars, int maxMatches) {
    if (needle == null || needle.isEmpty()) {
      throw new IllegalArgumentException("缺少参数 text");
    }
    int context = contextChars <= 0 ? DEFAULT_CONTEXT_CHARS : Math.min(contextChars, MAX_CONTEXT_CHARS);
    int cap = maxMatches <= 0 ? DEFAULT_MAX_MATCHES : maxMatches;
    String haystack = text == null ? "" : text;

    Pattern pattern;
    if (regex) {
      try {
        pattern = Pattern.compile(needle, Pattern.MULTILINE);
      } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException("正则写法非法：" + e.getDescription()
            + "（如果只是想找这个词本身,别传 regex:true）");
      }
    } else {
      pattern = Pattern.compile(Pattern.quote(needle), Pattern.MULTILINE);
    }

    List<Kv> matches = new ArrayList<>();
    int total = 0;
    Matcher matcher = pattern.matcher(haystack);
    int from = 0;
    while (from <= haystack.length() && matcher.find(from)) {
      total++;
      int start = matcher.start();
      int end = matcher.end();
      if (matches.size() < cap) {
        matches.add(Kv.by("index", start).set("line", lineOf(haystack, start))
            .set("match", matcher.group())
            .set("before", haystack.substring(Math.max(0, start - context), start))
            .set("after", haystack.substring(Math.min(haystack.length(), end),
                Math.min(haystack.length(), end + context))));
      }
      // 不重叠:从这次命中的末尾继续;空匹配(如正则 a*)必须往前推一格,否则原地死循环
      from = end > start ? end : start + 1;
    }

    Kv result = Kv.by("matchCount", total).set("returned", matches.size()).set("truncated", total > matches.size())
        .set("pattern", needle).set("regex", regex).set("contextChars", context).set("matches", matches);
    if (total > matches.size()) {
      result.set("hint", "命中 " + total + " 处,这里只回了前 " + matches.size()
          + " 处;要看得更全就调大 maxMatches,或把词写得更具体");
    }
    if (total == 0) {
      result.set("hint", "这段文本里没有命中。确认一下:搜索范围是不是只覆盖了主文档(跨域 iframe 要传 frame)、"
          + "文本是不是取自 innerText 而不是图片里的字");
    }
    return result;
  }

  /** 命中位置在第几行(1 基):排障时比字符下标好读得多 */
  static int lineOf(String text, int index) {
    int line = 1;
    int limit = Math.min(index, text.length());
    for (int i = 0; i < limit; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
