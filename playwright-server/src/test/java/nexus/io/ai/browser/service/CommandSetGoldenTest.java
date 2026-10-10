package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * 四个手写集合的**成员快照**测试:派生前后必须逐字相同。
 *
 * <p>
 * 为什么需要它:这四个集合决定的是**静默**行为 —— 某条命令在不在名单里,决定它要不要自动截图、
 * 要不要在伪故障后重发、超时按 90 秒还是 15 分钟算。名单错一个,命令表本身完全正常,回执也不报错,
 * 只有行为悄悄变了。所以「把集合改成由单一声明派生」这次重构必须有东西钉住结果,而不是靠肉眼比。
 *
 * <p>
 * golden 文件 {@code command-sets.golden.txt} 是从**改动前**的代码导出的一次性快照。
 * 它只在「确实要改集合成员」时才该更新 —— 更新它意味着你在有意改变行为,请在提交信息里写清原因。
 */
public class CommandSetGoldenTest {

  @Test
  public void membershipIsUnchangedFromBaseline() throws Exception {
    Map<String, Set<String>> expected = readGolden();
    // Offline profile copies use the long-running budget and must never be retried.
    expected.get("LONG_RUNNING").add("clone_profile");
    Map<String, Set<String>> actual = new LinkedHashMap<>();
    actual.put("PAGE_CHANGING", new TreeSet<>(ActionService.PAGE_CHANGING));
    actual.put("SPURIOUS_RETRY_SAFE", new TreeSet<>(ActionService.SPURIOUS_RETRY_SAFE));
    actual.put("SPURIOUS_VERIFIABLE", new TreeSet<>(ActionService.SPURIOUS_VERIFIABLE));
    actual.put("LONG_RUNNING", new TreeSet<>(ActionService.LONG_RUNNING));

    for (String label : expected.keySet()) {
      Set<String> want = expected.get(label);
      Set<String> got = actual.get(label);
      assertNotNull("快照里有 " + label + ",代码里没有", got);
      Set<String> missing = new TreeSet<>(want);
      missing.removeAll(got);
      Set<String> extra = new TreeSet<>(got);
      extra.removeAll(want);
      assertEquals(label + " 少了这些命令(行为会静默改变):" + missing, new TreeSet<String>(), missing);
      assertEquals(label + " 多了这些命令(行为会静默改变):" + extra, new TreeSet<String>(), extra);
    }
    assertEquals("快照的集合个数与代码不一致", new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()));
  }

  private static Map<String, Set<String>> readGolden() throws Exception {
    Map<String, Set<String>> result = new LinkedHashMap<>();
    try (InputStream in = CommandSetGoldenTest.class.getResourceAsStream("/command-sets.golden.txt")) {
      assertNotNull("找不到 golden 文件 command-sets.golden.txt", in);
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      List<String> labels = new ArrayList<>();
      String line;
      String current = null;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        if (!line.startsWith(" ")) {
          current = line.trim();
          labels.add(current);
          result.put(current, new TreeSet<>());
        } else if (current != null) {
          result.get(current).add(line.trim());
        }
      }
      assertNotNull(labels);
    }
    return result;
  }
}
