package nexus.io.ai.browser.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import nexus.io.ai.browser.actions.registry.CommandFlags;
import nexus.io.ai.browser.actions.registry.CommandTable;

/**
 * 命令表与四个「手写集合」的一致性。
 *
 * <p>
 * {@code PAGE_CHANGING} / {@code SPURIOUS_RETRY_SAFE} / {@code SPURIOUS_VERIFIABLE} /
 * {@code LONG_RUNNING} 都是**逐条手写**的:加一个命令、或给某个命令改名字时漏改一个,后果是
 * **静默**的行为变化 —— 那条命令不再自动截图、不再重试,或者超时分类错了,而命令表本身完全正常。
 *
 * <p>
 * 这个测试就是那条不变式:集合里的名字必须都在命令表里。它也是把 CommandSpec 改成「集合由声明
 * 派生」之前的**前置条件** —— 派生前后都必须满足它,否则派生只是把错误换了个地方写。
 */
public class CommandSetInvariantsTest {

  private static void assertOnlyRealCommands(String label, Set<String> names) {
    assertFalse(label + " 不该是空的", names.isEmpty());
    Set<String> known = new TreeSet<>(CommandTable.names());
    known.addAll(CommandFlags.ROUTED_OUTSIDE_COMMAND_TABLE);
    Set<String> unknown = new TreeSet<>(names);
    unknown.removeAll(known);
    assertTrue(label + " 里有命令表里不存在的名字(改过名?还是漏删?):" + unknown, unknown.isEmpty());
  }

  @Test
  public void profileCloneIsLongRunningWithoutAutomaticRetry() {
    assertTrue(ActionService.LONG_RUNNING.contains("clone_profile"));
    assertFalse(ActionService.SPURIOUS_RETRY_SAFE.contains("clone_profile"));
    assertFalse(ActionService.PAGE_CHANGING.contains("clone_profile"));
  }

  @Test
  public void declaredFlagsOnlyNameRealCommands() {
    // CommandFlags 现在是四条标志名单的唯一声明处:里面写错一个名字,派生出来的名单就少一条命令,
    // 而命令表本身完全正常 —— 加这一条是为了让「写错名字」在测试里就露出来。
    Set<String> known = new TreeSet<>(CommandTable.names());
    known.addAll(CommandFlags.ROUTED_OUTSIDE_COMMAND_TABLE);
    Set<String> unknown = new TreeSet<>(CommandFlags.declaredNames());
    unknown.removeAll(known);
    assertTrue("CommandFlags 里声明了不存在的命令:" + unknown, unknown.isEmpty());
  }

  @Test
  public void exceptionTableShouldStayMinimal() {
    // 例外表里的名字如果其实已经在命令表里了,就说明这张表可以缩小 —— 每多一项,
    // 「命令表是唯一的方法分发处」这条前提就少一分。
    Set<String> unexpectedlyInTable = new TreeSet<>(CommandFlags.ROUTED_OUTSIDE_COMMAND_TABLE);
    unexpectedlyInTable.retainAll(CommandTable.names());
    assertTrue("例外表里的名字其实在命令表里,该把它从例外表删掉:" + unexpectedlyInTable,
        unexpectedlyInTable.isEmpty());
  }

  @Test
  public void everyHandWrittenSetOnlyNamesRealCommands() {
    assertOnlyRealCommands("PAGE_CHANGING", ActionService.PAGE_CHANGING);
    assertOnlyRealCommands("SPURIOUS_RETRY_SAFE", ActionService.SPURIOUS_RETRY_SAFE);
    assertOnlyRealCommands("SPURIOUS_VERIFIABLE", ActionService.SPURIOUS_VERIFIABLE);
    assertOnlyRealCommands("LONG_RUNNING", ActionService.LONG_RUNNING);
  }
}
