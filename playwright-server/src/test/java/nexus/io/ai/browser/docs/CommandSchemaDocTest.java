package nexus.io.ai.browser.docs;

import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import com.jfinal.kit.Kv;
import nexus.io.ai.browser.actions.registry.CommandSchema;
import nexus.io.ai.browser.actions.registry.CommandTable;

public class CommandSchemaDocTest {
  @Test public void parameterReferenceMatchesRuntimeSchema() throws Exception {
    Path path = Path.of("../.agents/skills/deepseek-browser-use/references/parameters.md");
    StringBuilder text = new StringBuilder("# 参数定义（自动生成）\n\n"
        + "由 CommandTable / CommandSchema 生成；运行时使用 `dsb run list_methods -p details=true -p filter=wait_for_function --json` 查询。\n\n"
        + "`!` 表示必填，`|` 表示至少提供其中一个，逗号分隔不同必选组。类型必须匹配 JSON；*Ms 为毫秒，*Seconds 为秒。\n\n"
        + "公共参数：commandTimeoutMs:integer sensitive:boolean expect:object precondition:object readback:string snapshotVersion:integer retryOnSpurious:boolean async:boolean。"
        + "等待时间范围及断言规则见 [命令可靠性](command-reliability.md)。未声明的参数仍可能通过 unknownParams 提示，不代表支持。\n\n"
        + "| 方法 | 参数 | 必选组 | 枚举 |\n| --- | --- | --- | --- |\n");
    for (Kv entry : CommandSchema.describe(CommandTable.names())) {
      text.append("| `").append(entry.getStr("method")).append("` | ").append(entry.getStr("parameters"))
          .append(" | ").append(entry.getStr("requiredAny").replace("|", " / "))
          .append(" | ").append(entry.get("enums")).append(" |\n");
    }
    if (Boolean.getBoolean("updateSchemaDocs")) { Files.writeString(path, text); }
    assertEquals("Regenerate with -Dtest=CommandSchemaDocTest -DupdateSchemaDocs=true", text.toString(),
        Files.readString(path).replace("\r\n", "\n"));
  }
}
