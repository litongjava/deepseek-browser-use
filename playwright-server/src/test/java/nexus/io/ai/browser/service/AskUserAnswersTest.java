package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

/**
 * {@code ask_user} 答复整形（{@code answers:[{id, selected[], custom?}]}）的用例
 *
 * <p>
 * 起因:向用户「要一个值」这件事在实操里出现得极频繁（银行卡号、身份证号、验证码、两张卡选一张），
 * 而当时只能借 {@code request_human_input} 的壳子说「请输入图片验证码」这种话 —— 语义是拧的。
 * 现在 {@code ask_user} 与 agent 侧的同名工具同形,答复也回成同一个结构。
 *
 * <p>
 * 这里钉的是**最容易悄悄错掉的那一步**:调用方按 {@code id} 回填一个字符串时,到底算「选了某个选项」
 * 还是「自己打了一段字」。分错了下游就得自己猜,而这个信息在回填时是明确的。
 */
public class AskUserAnswersTest {

  private static Kv question(String id, String... optionLabels) {
    Kv step = Kv.by("stepId", id).set("prompt", id).set("status", "answered");
    if (optionLabels.length > 0) {
      JSONArray options = new JSONArray();
      for (String label : optionLabels) {
        options.add(new JSONObject().fluentPut("label", label));
      }
      step.set("options", options);
    }
    return step;
  }

  /** 回填的字符串正好是某个选项 → 算「选了那一项」,不是自由输入 */
  @Test
  public void anAnswerMatchingAnOptionIsReportedAsSelected() {
    Kv step = question("card", "尾号 0196", "尾号 8031");
    step.set("answer", "尾号 0196");

    List<Kv> steps = new ArrayList<>();
    steps.add(step);
    JSONObject answer = PlaywrightService.askUserAnswers(steps).getJSONObject(0);

    assertEquals("card", answer.getString("id"));
    assertEquals(List.of("尾号 0196"), answer.getJSONArray("selected").toJavaList(String.class));
    assertFalse("对上选项时不该同时报成自由输入", answer.containsKey("custom"));
  }

  /** 回填的字符串对不上任何选项 → 算「自己输入的」 */
  @Test
  public void anAnswerNotMatchingAnyOptionIsReportedAsCustom() {
    Kv step = question("card", "尾号 0196", "尾号 8031");
    step.set("answer", "6212842471750140196");

    List<Kv> steps = new ArrayList<>();
    steps.add(step);
    JSONObject answer = PlaywrightService.askUserAnswers(steps).getJSONObject(0);

    assertEquals("6212842471750140196", answer.getString("custom"));
    assertTrue("自由输入时 selected 应当为空:" + answer.get("selected"),
        answer.getJSONArray("selected").isEmpty());
  }

  /** 多选题回填数组:整份进 selected,不再逐项去比选项 */
  @Test
  public void anArrayAnswerGoesIntoSelected() {
    Kv step = question("scope", "教育培训", "信息技术", "工具服务及在线查询");
    JSONArray picked = new JSONArray();
    picked.add("教育培训");
    picked.add("信息技术");
    step.set("answer", picked);

    List<Kv> steps = new ArrayList<>();
    steps.add(step);
    JSONObject answer = PlaywrightService.askUserAnswers(steps).getJSONObject(0);

    assertEquals(List.of("教育培训", "信息技术"), answer.getJSONArray("selected").toJavaList(String.class));
    assertFalse(answer.containsKey("custom"));
  }

  /** 没答的问题也要出现在结果里,且 selected 是空数组 —— 下游可以直接按 id 对齐,不用判空 */
  @Test
  public void unansweredQuestionsStillAppearWithAnEmptySelection() {
    Kv pending = question("phone");
    Kv answered = question("code");
    answered.set("answer", "301073");

    List<Kv> steps = new ArrayList<>();
    steps.add(pending);
    steps.add(answered);
    JSONArray answers = PlaywrightService.askUserAnswers(steps);

    assertEquals("两个问题都要在结果里", 2, answers.size());
    assertEquals("phone", answers.getJSONObject(0).getString("id"));
    assertTrue(answers.getJSONObject(0).getJSONArray("selected").isEmpty());
    assertEquals("code", answers.getJSONObject(1).getString("id"));
    assertEquals("301073", answers.getJSONObject(1).getString("custom"));
  }

  /** 选项写成纯字符串也要认(两种形态都得支持) */
  @Test
  public void plainStringOptionsAreRecognized() {
    Kv step = Kv.by("stepId", "q").set("status", "answered");
    JSONArray options = new JSONArray();
    options.add("是");
    options.add("否");
    step.set("options", options);
    step.set("answer", "是");

    List<Kv> steps = new ArrayList<>();
    steps.add(step);
    JSONObject answer = PlaywrightService.askUserAnswers(steps).getJSONObject(0);

    assertEquals(List.of("是"), answer.getJSONArray("selected").toJavaList(String.class));
  }
}
