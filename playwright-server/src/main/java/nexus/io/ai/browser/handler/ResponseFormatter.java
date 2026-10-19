package nexus.io.ai.browser.handler;

import java.util.ArrayList;
import java.util.Map;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import nexus.io.model.body.RespBodyVo;

/**
 * Optional wire projection. Never traverses arbitrary website/script payloads.
 */
public final class ResponseFormatter {
  private ResponseFormatter() {
  }

  public static RespBodyVo format(RespBodyVo response, String requestBody) {
    JSONObject request;
    try {
      request = JSONObject.parseObject(requestBody);
    } catch (RuntimeException e) {
      return response;
    }
    if (request == null || !"compact".equals(request.getString("responseMode")) || !response.isOk()) {
      return response;
    }
    response.setData(project(request.getString("method"), response.getData(),
        Boolean.TRUE.equals(request.getBoolean("diagnostics"))));
    return response; // Keep ok/code/error/msg, including nulls, exactly as before.
  }

  static Object project(String method, Object data, boolean diagnostics) {
    if (!(data instanceof Map) || diagnostics) {
      return data;
    }
    Map<?, ?> original = (Map<?, ?>) data;
    if (original.get("execution") instanceof Map<?, ?> execution
        && (!"completed".equals(execution.get("status"))
            || !"completed".equals(execution.get("actionStatus"))
            || !Boolean.TRUE.equals(execution.get("observationCompleted"))
            || !Boolean.FALSE.equals(execution.get("workerRunning"))
            || Boolean.TRUE.equals(execution.get("cancelled"))
            || Boolean.TRUE.equals(execution.get("cancelRequested")))) {
      return data;
    }
    if (original.containsKey("errorCode") || "unknown".equals(original.get("actionStatus"))
        || Boolean.FALSE.equals(original.get("observationComplete"))
        || Boolean.TRUE.equals(original.get("capture_degraded"))
        || original.containsKey("screenshot_error") || Boolean.TRUE.equals(original.get("fallbackUsed"))) {
      return data;
    }
    Kv result = Kv.create();
    original.forEach((key, value) -> result.put(String.valueOf(key), value));
    if ("commands".equals(method) && original.get("results") instanceof Iterable) {
      ArrayList<Object> steps = new ArrayList<>();
      for (Object item : (Iterable<?>) original.get("results")) {
        if (!(item instanceof Map)) {
          steps.add(item);
          continue;
        }
        Map<?, ?> step = (Map<?, ?>) item;
        Kv copy = Kv.create();
        step.forEach((key, value) -> copy.put(String.valueOf(key), value));
        if (!Boolean.FALSE.equals(step.get("ok"))) {
          copy.put("data", project(String.valueOf(step.get("command")), step.get("data"), diagnostics));
        }
        steps.add(copy);
      }
      result.put("results", steps);
    }
    // Only remove fields owned by the command protocol, never body/result/request
    // payloads.
    result.remove("screenshot_path");
    if ("get_browser_state".equals(method) || "get_page_snapshot".equals(method)) {
      result.remove("browser_state");
      if (result.containsKey("tabs")) {
        result.remove("url");
        result.remove("title");
      }
    }
    if (method != null && (method.startsWith("click_") || "double_click_element_by_index".equals(method)
        || "hover_and_click".equals(method))) {
      for (String key : new String[] { "urlBefore", "urlAfter", "tabCountBefore", "tabCountAfter", "textLengthBefore",
          "textLengthAfter", "outerHtml" }) {
        result.remove(key);
      }
    }
    // Keep uncertain execution and exceptional capture details available to callers.
    if (original.get("execution") instanceof Map<?, ?> execution
        && "completed".equals(execution.get("status"))
        && "completed".equals(execution.get("actionStatus"))
        && Boolean.TRUE.equals(execution.get("observationCompleted"))
        && Boolean.FALSE.equals(execution.get("workerRunning"))
        && !Boolean.TRUE.equals(execution.get("cancelled"))
        && !Boolean.TRUE.equals(execution.get("cancelRequested"))) {
      result.remove("execution");
    }
    if (original.get("retryBudget") instanceof Map<?, ?> budget
        && budget.get("retries") instanceof Number retries && retries.intValue() == 0) {
      result.remove("retryBudget");
    }
    if (Boolean.FALSE.equals(original.get("capture_degraded"))) {
      result.remove("capture_degraded");
    }
    if (original.get("capture") instanceof Map<?, ?> capture
        && "complete".equals(capture.get("stage")) && !capture.containsKey("errorCode")
        && !Boolean.TRUE.equals(original.get("fallbackUsed"))
        && !Boolean.TRUE.equals(original.get("capture_degraded"))
        && capture.get("retries") instanceof Number retries && retries.intValue() == 0) {
      result.remove("capture");
    }
    if (("screenshot".equals(method) || "get_element_screenshot".equals(method))
        && Boolean.TRUE.equals(original.get("base64Omitted"))) {
      result.remove("base64Omitted");
      result.remove("inline");
      result.remove("note");
    }
    return result;
  }
}
