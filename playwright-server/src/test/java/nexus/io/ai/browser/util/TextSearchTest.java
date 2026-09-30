package nexus.io.ai.browser.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import com.jfinal.kit.Kv;

/**
 * {@link TextSearch} 的行为固定
 *
 * <p>它是纯函数,不起浏览器 —— 搜索范围、上下文长度、条数上限与正则边界都在这里钉住。
 */
public class TextSearchTest {

  private static final String TEXT = "第一行：项目名称\n第二行：批准文号 自然资函〔2022〕955号\n第三行：未利用地 0.3431\n";

  @Test
  public void literalMatchCarriesContextAndLine() {
    Kv result = TextSearch.find(TEXT, "955号", false, 10, 5);
    assertEquals(1, intOf(result, "matchCount"));
    List<Kv> matches = matches(result);
    assertEquals(1, matches.size());
    Kv first = matches.get(0);
    assertEquals("955号", first.getStr("match"));
    assertEquals("命中在第 2 行", 2, intOf(first, "line"));
    assertTrue("前文要带上文号前缀", first.getStr("before").contains("自然资函"));
    assertTrue("后文要带上结尾", first.getStr("after").contains("号") || first.getStr("after").contains("第三行"));
    assertEquals("命中的字符下标要说清", TEXT.indexOf("955号"), intOf(first, "index"));
  }

  @Test
  public void multipleMatchesAreCounted() {
    Kv result = TextSearch.find(TEXT, "：", false, 0, 10);
    assertEquals(3, intOf(result, "matchCount"));
    assertEquals("上下文长度没传时用默认值", TextSearch.DEFAULT_CONTEXT_CHARS, intOf(result, "contextChars"));
  }

  @Test
  public void maxMatchesCapsReturnedButKeepsTotal() {
    Kv result = TextSearch.find(TEXT, "第", false, 5, 1);
    assertEquals("总数仍然是全部命中", 3, intOf(result, "matchCount"));
    assertEquals("但只回 1 条", 1, intOf(result, "returned"));
    assertEquals(true, result.get("truncated"));
    assertNotNull("截断时要提示怎么拿全", result.getStr("hint"));
  }

  @Test
  public void regexModeWorks() {
    Kv result = TextSearch.find(TEXT, "〔\\d{4}〕|未利用地", true, 5, 10);
    assertEquals(2, intOf(result, "matchCount"));
    assertEquals(true, result.get("regex"));
  }

  /** 写错的正则必须当场失败:静默退化成字面量搜索会让调用方以为正则生效了 */
  @Test
  public void badRegexFailsLoudly() {
    try {
      TextSearch.find(TEXT, "(", true, 10, 5);
      fail("非法正则应当抛异常");
    } catch (IllegalArgumentException e) {
      assertTrue("原因要指向正则,实际:" + e.getMessage(), e.getMessage().contains("正则"));
    }
  }

  @Test
  public void emptyNeedleIsRejected() {
    try {
      TextSearch.find(TEXT, "", false, 10, 5);
      fail("空的搜索词应当抛异常");
    } catch (IllegalArgumentException e) {
      assertEquals("缺少参数 text", e.getMessage());
    }
  }

  @Test
  public void noHitExplainsWhatToCheck() {
    Kv result = TextSearch.find(TEXT, "这句不存在", false, 10, 5);
    assertEquals(0, intOf(result, "matchCount"));
    assertNotNull("没命中时要给出排查方向(图里的字 / iframe / 范围)", result.getStr("hint"));
  }

  @Test
  public void nullTextIsTreatedAsEmpty() {
    Kv result = TextSearch.find(null, "甲", false, 10, 5);
    assertEquals(0, intOf(result, "matchCount"));
  }

  /** 不重叠:"aaa" 里找 "aa" 只算 1 次,否则会得到一串互相咬住的片段 */
  @Test
  public void matchesDoNotOverlap() {
    assertEquals(1, intOf(TextSearch.find("aaa", "aa", false, 2, 10), "matchCount"));
  }

  /** 空匹配(如 {@code a*})必须往前推一格,否则就在原地打转 */
  @Test
  public void emptyMatchPatternTerminates() {
    Kv result = TextSearch.find("abc", "x*", true, 2, 50);
    assertTrue("空匹配也要能正常结束", intOf(result, "matchCount") >= 0);
  }

  /** 上下文超过上限时收紧,免得一条回执被撑爆 */
  @Test
  public void contextCharsIsClamped() {
    Kv result = TextSearch.find(TEXT, "955号", false, 999_999, 1);
    assertEquals(2000, intOf(result, "contextChars"));
  }

  @SuppressWarnings("unchecked")
  private static List<Kv> matches(Kv result) {
    return (List<Kv>) result.get("matches");
  }

  private static int intOf(Kv kv, String key) {
    Object value = kv.get(key);
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }
}
