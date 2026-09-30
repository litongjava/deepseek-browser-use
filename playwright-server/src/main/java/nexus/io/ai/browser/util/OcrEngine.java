package nexus.io.ai.browser.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;

import nexus.io.ai.browser.service.ChromeBrowser;

/**
 * 读图上的文字:默认走 Windows 自带 OCR,也可以配一条**外部命令**顶上
 *
 * <p>
 * <b>为什么要有"可插拔"这一层</b>:{@link WindowsOcr} 走的是 {@code Windows.Media.Ocr},它擅长
 * 印刷体短文本(验证码、维护图),但遇到**整页扫描件、带合并单元格的表格**就力不从心 —— 实测一份
 * 政府公告的"建设用地明细表"图片,系统 OCR 只能吐出一堆错乱的片段,而文档级 OCR 能直接给出可解析的
 * 表格结构。这类能力本机往往已经有了(一个现成的 OCR 命令行工具),服务端不该逼着调用方跳到工具外面去。
 *
 * <p>
 * 所以这里只做一件事:**把"读这张图"这件事交给配置指定的后端**,回执里如实写明这次是谁读的。
 *
 * <table border="1">
 * <caption>配置</caption>
 * <tr><th>键</th><th>默认值</th><th>说明</th></tr>
 * <tr><td>{@code browser.ocr.engine}</td><td>{@code windows}</td>
 * <td>{@code windows} = 系统 OCR;{@code command} = 跑 {@code browser.ocr.command}</td></tr>
 * <tr><td>{@code browser.ocr.command}</td><td>空</td>
 * <td>外部 OCR 命令模板,支持 {@code {input}}(图片路径)、{@code {output}}(结果文本路径)、
 * {@code {language}} 三个占位符。带空格或中文的路径请用双引号包住占位符</td></tr>
 * <tr><td>{@code browser.ocr.timeoutMs}</td><td>{@code 120000}</td><td>外部命令超时(毫秒)</td></tr>
 * </table>
 *
 * <p>
 * 例(用一个现成的文档 OCR 命令行工具):
 *
 * <pre>
 * browser.ocr.engine=command
 * browser.ocr.command=java -jar D:/tools/ocr-cli.jar -i "{input}" -o "{output}"
 * </pre>
 *
 * <p>
 * <b>什么时候仍然用默认的 windows</b>:只要验证码、二维码、系统维护提示图这类短文本 —— 它零配置、
 * 零依赖,而且不会因为外部工具没装而失效。{@code engine=command} 是给"页面上的图就是数据"那类
 * 任务准备的(公告扫描件、票据、表格截图)。
 */
@Slf4j
public final class OcrEngine {

  /** 走哪条路:{@code windows}(默认)或 {@code command} */
  public static final String KEY_ENGINE = "browser.ocr.engine";

  /** 外部 OCR 命令模板,支持 {input} / {output} / {language} */
  public static final String KEY_COMMAND = "browser.ocr.command";

  /** 外部命令超时(毫秒) */
  public static final String KEY_TIMEOUT_MS = "browser.ocr.timeoutMs";

  public static final String ENGINE_WINDOWS = "windows";
  public static final String ENGINE_COMMAND = "command";

  private static final int DEFAULT_TIMEOUT_MS = 120_000;

  private OcrEngine() {
  }

  /** 这次实际会用哪个后端({@code command} 没配命令时退回 {@code windows}) */
  public static String engineId() {
    String configured = trimToNull(ChromeBrowser.config(KEY_ENGINE));
    if (ENGINE_COMMAND.equalsIgnoreCase(configured) && commandTemplate() != null) {
      return ENGINE_COMMAND;
    }
    return ENGINE_WINDOWS;
  }

  /** 供 {@code get_config} 与启动日志用的一句话描述 */
  public static String describe() {
    String engine = engineId();
    if (ENGINE_COMMAND.equals(engine)) {
      return engine + " (" + commandTemplate() + ")";
    }
    if (ENGINE_COMMAND.equalsIgnoreCase(trimToNull(ChromeBrowser.config(KEY_ENGINE)))) {
      // 配了 command 却没给命令:如实说明退回了哪条路,别让人以为配置生效了
      return ENGINE_WINDOWS + " (" + KEY_ENGINE + "=command 但 " + KEY_COMMAND + " 为空,已退回系统 OCR)";
    }
    return ENGINE_WINDOWS;
  }

  public static int timeoutMs() {
    String configured = trimToNull(ChromeBrowser.config(KEY_TIMEOUT_MS));
    if (configured == null) {
      return DEFAULT_TIMEOUT_MS;
    }
    try {
      int parsed = Integer.parseInt(configured);
      return parsed <= 0 ? DEFAULT_TIMEOUT_MS : parsed;
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_MS;
    }
  }

  public static String commandTemplate() {
    return trimToNull(ChromeBrowser.config(KEY_COMMAND));
  }

  /**
   * 读一张图上的文字
   *
   * @param image    图片路径(服务端本地文件)
   * @param language 识别语言,空则用 {@link WindowsOcr#DEFAULT_LANGUAGE};外部命令里以 {@code {language}} 注入
   * @return {@code {ok, text, engine, ...}};失败时 {@code ok=false} 且带 {@code error}
   */
  public static Kv read(Path image, String language) {
    if (image == null || !Files.isRegularFile(image)) {
      return Kv.by("ok", false).set("error", "找不到要识别的图片:" + image);
    }
    if (ENGINE_COMMAND.equals(engineId())) {
      Kv result = readByCommand(image, language);
      // 外部命令失败时**不静默退回**系统 OCR:调用方明确要的是文档级识别,
      // 悄悄换一个识别质量差一截的后端,只会让人以为"这张图读不出来"
      return result;
    }
    Kv result = WindowsOcr.read(image, language);
    result.set("engine", ENGINE_WINDOWS);
    return result;
  }

  /**
   * 跑外部命令读图
   *
   * <p>
   * 结果优先取 {@code {output}} 指向的文件:多数 OCR 命令行工具把长文本写文件,stdout 只留进度信息。
   * 模板里没有 {@code {output}} 时改用 stdout。
   */
  private static Kv readByCommand(Path image, String language) {
    String template = commandTemplate();
    Kv result = new Kv().set("engine", ENGINE_COMMAND);
    boolean expectsOutputFile = template.contains("{output}");
    Path outFile = null;
    try {
      if (expectsOutputFile) {
        outFile = Files.createTempFile("dsh-ocr-cmd-", ".txt");
      }
      String resolved = template.replace("{input}", image.toAbsolutePath().toString())
          .replace("{output}", outFile == null ? "" : outFile.toAbsolutePath().toString())
          .replace("{language}", language == null || language.isBlank() ? WindowsOcr.DEFAULT_LANGUAGE : language.trim());
      List<String> command = splitCommand(resolved);
      if (command.isEmpty()) {
        return result.set("ok", false).set("error", "OCR 命令模板解析后为空:" + template);
      }
      long startedAt = System.currentTimeMillis();
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      Process process = builder.start();
      String console = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int timeout = timeoutMs();
      if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return result.set("ok", false).set("error", "OCR 命令超时(" + timeout + " 毫秒)").set("command", command.get(0));
      }
      result.set("ms", System.currentTimeMillis() - startedAt);
      String text = "";
      if (outFile != null && Files.isRegularFile(outFile)) {
        text = new String(Files.readAllBytes(outFile), StandardCharsets.UTF_8);
      }
      if (text.isBlank()) {
        text = console;
      }
      text = stripBom(text).trim();
      if (process.exitValue() != 0 && text.isEmpty()) {
        return result.set("ok", false).set("exitCode", process.exitValue())
            .set("error", "OCR 命令以退出码 " + process.exitValue() + " 结束,且没有输出");
      }
      if (text.isEmpty()) {
        return result.set("ok", false).set("exitCode", process.exitValue())
            .set("error", "OCR 命令没有产出任何文字");
      }
      return result.set("ok", true).set("text", text)
          .set("lineCount", text.split("\\r?\\n").length)
          .set("exitCode", process.exitValue());
    } catch (IOException e) {
      return result.set("ok", false).set("error", "调用 OCR 命令失败:" + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return result.set("ok", false).set("error", "OCR 命令被中断");
    } finally {
      if (outFile != null) {
        try {
          Files.deleteIfExists(outFile);
        } catch (IOException e) {
          log.debug("清理 OCR 临时文件失败:{}", e.getMessage());
        }
      }
    }
  }

  /**
   * 把命令模板切成参数表
   *
   * <p>
   * 只认双引号:**路径里有空格或中文时必须靠它**(Windows 上 {@code C:\Program Files\...} 是常态),
   * 所以不能简单地 {@code split(" ")}。分隔符是空白(空格/制表符),引号本身不进入参数。
   */
  static List<String> splitCommand(String command) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    boolean started = false;
    for (int i = 0; i < command.length(); i++) {
      char c = command.charAt(i);
      if (c == '"') {
        quoted = !quoted;
        started = true;
        continue;
      }
      if (!quoted && (c == ' ' || c == '\t')) {
        if (started) {
          tokens.add(current.toString());
          current.setLength(0);
          started = false;
        }
        continue;
      }
      current.append(c);
      started = true;
    }
    if (started) {
      tokens.add(current.toString());
    }
    tokens.removeIf(String::isEmpty);
    return tokens;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * 去掉文本开头的 BOM
   *
   * <p>
   * <b>为什么必须有这一步</b>:外部工具(以及 Windows PowerShell 自己)写结果文件时常常带 UTF-8 BOM,
   * 而 {@code String.trim()} 只吃 {@code <= U+0020} 的字符,**吃不掉 {@code U+FEFF}**。于是识别结果会
   * 变成 {@code "\ufeff1234"} —— 肉眼看着一模一样,拿去比对/填表单却必然失败(实测验证码就是这样:
   * 界面显示 4 位,粘进输入框却永远提示错误)。这种"看不见的字符"必须在这一层清掉。
   */
  static String stripBom(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
  }
}
