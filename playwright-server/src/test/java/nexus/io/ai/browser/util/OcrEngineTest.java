package nexus.io.ai.browser.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import com.jfinal.kit.Kv;

/**
 * {@link OcrEngine} 的命令解析
 *
 * <p>
 * 只测纯函数部分:<b>带空格与中文的路径必须靠双引号活下来</b>。Windows 上 OCR 工具装在
 * {@code C:\Program Files\…} 是常态,用 {@code split(" ")} 一切就散,而报出来的错会是"找不到文件"
 * 这种指不到根因的话。
 */
public class OcrEngineTest {

  @Test
  public void splitsPlainCommand() {
    List<String> tokens = OcrEngine.splitCommand("java -jar ocr.jar -i a.png");
    assertEquals(List.of("java", "-jar", "ocr.jar", "-i", "a.png"), tokens);
  }

  /** 引号里的空格不切分,引号本身不进入参数 */
  @Test
  public void keepsQuotedPathsWithSpaces() {
    List<String> tokens = OcrEngine.splitCommand(
        "java -jar \"C:/Program Files/ocr-cli/ocr-cli.jar\" -i \"C:/我的文档/明细表.png\" -o \"C:/out/结果.md\"");
    assertEquals("java", tokens.get(0));
    assertEquals("C:/Program Files/ocr-cli/ocr-cli.jar", tokens.get(2));
    assertEquals("C:/我的文档/明细表.png", tokens.get(4));
    assertEquals("C:/out/结果.md", tokens.get(6));
    assertEquals(7, tokens.size());
  }

  @Test
  public void collapsesRepeatedSpacesAndTabs() {
    assertEquals(List.of("a", "b", "c"), OcrEngine.splitCommand("  a \t b   c  "));
  }

  @Test
  public void emptyCommandYieldsNoTokens() {
    assertTrue(OcrEngine.splitCommand("   ").isEmpty());
  }

  /** 没配具体命令时不能装作 engine=command 生效了 */
  @Test
  public void describeFallsBackToWindowsWhenCommandMissing() {
    System.clearProperty(OcrEngine.KEY_ENGINE);
    System.clearProperty(OcrEngine.KEY_COMMAND);
    assertEquals(OcrEngine.ENGINE_WINDOWS, OcrEngine.engineId());
    assertEquals("windows", OcrEngine.describe());
  }

  /** 配了 command 但没给命令:如实说明退回了系统 OCR,而不是让人以为配置生效了 */
  @Test
  public void engineCommandWithoutTemplateFallsBackWithExplanation() {
    System.setProperty(OcrEngine.KEY_ENGINE, OcrEngine.ENGINE_COMMAND);
    System.clearProperty(OcrEngine.KEY_COMMAND);
    try {
      assertEquals(OcrEngine.ENGINE_WINDOWS, OcrEngine.engineId());
      assertTrue("要说清退回了哪条路,实际:" + OcrEngine.describe(),
          OcrEngine.describe().contains(OcrEngine.KEY_COMMAND));
    } finally {
      System.clearProperty(OcrEngine.KEY_ENGINE);
    }
  }

  /** 配齐了就该走外部命令 */
  @Test
  public void engineCommandWithTemplateIsHonored() {
    System.setProperty(OcrEngine.KEY_ENGINE, OcrEngine.ENGINE_COMMAND);
    System.setProperty(OcrEngine.KEY_COMMAND, "ocr -i \"{input}\" -o \"{output}\"");
    try {
      assertEquals(OcrEngine.ENGINE_COMMAND, OcrEngine.engineId());
      assertTrue(OcrEngine.describe().contains("ocr -i"));
    } finally {
      System.clearProperty(OcrEngine.KEY_ENGINE);
      System.clearProperty(OcrEngine.KEY_COMMAND);
    }
  }

  @Test
  public void timeoutFallsBackOnGarbage() {
    System.setProperty(OcrEngine.KEY_TIMEOUT_MS, "abc");
    try {
      assertEquals(120_000, OcrEngine.timeoutMs());
    } finally {
      System.clearProperty(OcrEngine.KEY_TIMEOUT_MS);
    }
  }

  /** BOM 必须清掉:它能骗过肉眼,却能毁掉每一次比对与每一次填表 */
  @Test
  public void bomIsStripped() {
    assertEquals("1234", OcrEngine.stripBom("\uFEFF1234"));
    assertEquals("1234", OcrEngine.stripBom("1234"));
    assertEquals("", OcrEngine.stripBom("\uFEFF"));
    assertEquals(null, OcrEngine.stripBom(null));
    // trim() 吃不掉 U+FEFF —— 这正是当初要单独处理它的原因
    assertEquals("\uFEFF", "\uFEFF".trim());
  }

  /**
   * 外部命令把结果写进 {@code {output}} 时,以**文件内容**为准
   *
   * <p>
   * 多数 OCR 命令行工具把长文本写文件,stdout 只留进度信息 —— 所以"文件优先"这条契约必须钉住,
   * 否则整页文档的识别结果会被 stdout 里的几行日志顶掉。
   *
   * <p>用 `cmd` 而不是真的 OCR 工具:这条要测的是**命令后端本身**能不能跑通、能不能把结果取回来,
   * 不该依赖机器上装了哪个 OCR、也不该联网。
   */
  @Test
  public void commandBackendReadsOutputFile() throws Exception {
    Assume.assumeTrue("这条用 cmd 造一个会写结果文件的命令,只在 Windows 上有意义",
        System.getProperty("os.name", "").toLowerCase().contains("win"));
    java.nio.file.Path image = java.nio.file.Files.createTempFile("ocr-cmd-test", ".png");
    java.nio.file.Files.write(image, new byte[] {1, 2, 3});
    System.setProperty(OcrEngine.KEY_ENGINE, OcrEngine.ENGINE_COMMAND);
    // 文本用 ASCII:cmd 的 echo 按控制台代码页写文件,中文读回来会是乱码,那是编码问题不是本测试要测的
    System.setProperty(OcrEngine.KEY_COMMAND, "cmd /c echo OCR-OK> \"{output}\"");
    try {
      Kv result = OcrEngine.read(image, "zh-Hans-CN");
      assertEquals("命令应当成功:" + result.get("error"), true, result.get("ok"));
      assertEquals(OcrEngine.ENGINE_COMMAND, result.getStr("engine"));
      assertTrue("结果要取自 {output} 文件,实际:" + result.getStr("text"),
          String.valueOf(result.getStr("text")).contains("OCR-OK"));
    } finally {
      System.clearProperty(OcrEngine.KEY_ENGINE);
      System.clearProperty(OcrEngine.KEY_COMMAND);
      java.nio.file.Files.deleteIfExists(image);
    }
  }

  /** 命令跑完却什么都没产出:按失败报,并说明是"没有产出",不是静默回一个空串 */
  @Test
  public void commandBackendReportsEmptyOutput() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
    java.nio.file.Path image = java.nio.file.Files.createTempFile("ocr-cmd-empty", ".png");
    java.nio.file.Files.write(image, new byte[] {1});
    System.setProperty(OcrEngine.KEY_ENGINE, OcrEngine.ENGINE_COMMAND);
    System.setProperty(OcrEngine.KEY_COMMAND, "cmd /c exit 0");
    try {
      Kv result = OcrEngine.read(image, null);
      assertEquals(false, result.get("ok"));
      assertTrue("要说清是没有产出,实际:" + result.get("error"),
          String.valueOf(result.get("error")).contains("没有产出"));
    } finally {
      System.clearProperty(OcrEngine.KEY_ENGINE);
      System.clearProperty(OcrEngine.KEY_COMMAND);
      java.nio.file.Files.deleteIfExists(image);
    }
  }
}
