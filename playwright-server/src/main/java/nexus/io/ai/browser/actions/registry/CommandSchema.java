package nexus.io.ai.browser.actions.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import nexus.io.ai.browser.service.PlaywrightService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

/** Static protocol validation only. Never probes a page or executes user JavaScript. */
public final class CommandSchema {
  private static final Map<String, String> DEFINITIONS = new LinkedHashMap<>();
  private static final String COMMON = "commandTimeoutMs:integer sensitive:boolean expect:object precondition:object readback:string snapshotVersion:integer retryOnSpurious:boolean async:boolean";

  static void register(String method, String parameters) {
    DEFINITIONS.put(method, parameters);
  }

  public static List<Kv> describe(Collection<String> methods) {
    List<Kv> result = new ArrayList<>();
    for (String method : methods) {
      result.add(Kv.by("method", method).set("parameters", DEFINITIONS.get(method))
          .set("common", COMMON).set("requiredAny", alternatives(method))
          .set("units", "*Ms: milliseconds; *Seconds: seconds")
          .set("enums", enums(method)));
    }
    return result;
  }

  private static String alternatives(String method) {
    return switch (method) {
      case "execute_js" -> "body|bodyFile";
      case "press_key" -> "key|keys";
      case "get_page_diagnostics" -> "";
      case "upload_file" -> "index|selector|trigger,path|contentBase64|url";
      case "get_element_text", "get_element_listeners", "is_visible", "is_enabled", "is_checked",
          "clear_text", "hover_and_click", "download_image", "get_element_screenshot" -> "index|selector";
      default -> "";
    };
  }

  private static Map<String, List<String>> enums(String method) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    if (Set.of("navigate", "go_to_url").contains(method)) {
      result.put("waitUntil", List.of("load", "domcontentloaded", "networkidle", "commit"));
    }
    if ("wait_for_load".equals(method)) {
      result.put("state", List.of("load", "domcontentloaded", "networkidle"));
    }
    return result;
  }

  public static List<String> validate(String method, JSONObject params, String path) {
    List<String> errors = new ArrayList<>();
    if (CommandTable.get(method) == null) {
      errors.add(path + ": unsupported method; suggestions=" + CommandTable.suggest(method, 3));
      return errors;
    }
    JSONObject args = params == null ? new JSONObject() : params;
    String definition = DEFINITIONS.get(method);
    fields(definition + " " + COMMON, args, path, errors);
    for (String group : alternatives(method).split(",")) {
      if (!group.isEmpty() && Arrays.stream(group.split("\\|")).noneMatch(k -> present(args.get(k)))) {
        errors.add(path + "." + group + " required");
      }
    }
    if ("upload_file".equals(method) && present(args.get("trigger")) && !present(args.get("path"))) {
      errors.add(path + ".path required with trigger");
    }
    enums(method).forEach((key, allowed) -> {
      if (present(args.get(key)) && !allowed.contains(args.get(key))) {
        errors.add(path + "." + key + " must be one of " + allowed);
      }
    });
    if (args.containsKey("timeout")) {
      errors.add(path + ".timeout is unsupported; use the documented timeoutMs / timeoutSeconds");
    }
    if ("wait_for_function".equals(method) && args.containsKey("body")) {
      errors.add(path + ".body is unsupported; use expression");
    }
    if (definition.contains("timeoutMs:number") && errors.isEmpty()) {
      try { CommandTable.waitSeconds(args); }
      catch (IllegalArgumentException error) { errors.add(path + "." + error.getMessage()); }
    }
    if (args.get("popupTimeoutMs") instanceof Number n && (n.longValue() < 0 || n.longValue() > 120000)) {
      errors.add(path + ".popupTimeoutMs must be 0..120000");
    }
    if (args.get("commandTimeoutMs") instanceof Number n && (n.longValue() < 1 || n.longValue() > 900000)) {
      errors.add(path + ".commandTimeoutMs must be 1..900000");
    }
    for (String key : List.of("expect", "precondition")) {
      if (args.get(key) instanceof JSONObject expectation) {
        expectation(expectation, path + "." + key, errors);
      }
    }
    return errors;
  }

  private static boolean present(Object value) {
    return value != null && (!(value instanceof String text) || !text.isBlank());
  }

  private static void fields(String definition, JSONObject args, String path, List<String> errors) {
    for (String field : definition.trim().split(" +")) {
      if (field.isEmpty()) { continue; }
      String[] parts = field.split(":");
      String key = parts[0], type = parts[1].replace("!", "");
      Object value = args.get(key);
      if (value == null) {
        if (parts[1].endsWith("!")) { errors.add(path + "." + key + " required"); }
        continue;
      }
      boolean valid = switch (type) {
        case "string" -> {
          // `-p text=2026` arrives as a JSON number; the caller clearly meant the literal text.
          if (value instanceof java.math.BigDecimal decimal) {
            args.put(key, decimal.toPlainString());
            yield true;
          }
          if (value instanceof Number || value instanceof Boolean) {
            args.put(key, String.valueOf(value));
            yield true;
          }
          yield value instanceof String;
        }
        case "boolean" -> value instanceof Boolean;
        case "object" -> value instanceof JSONObject;
        case "array" -> value instanceof JSONArray;
        case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
        case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue())
            && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) <= (Set.of("since", "expiresAt", "snapshotVersion", "commandTimeoutMs", "maxDurationMs").contains(key)
                ? Long.MAX_VALUE : Integer.MAX_VALUE);
        default -> false;
      };
      if (!valid || (parts[1].endsWith("!") && value instanceof String text && text.isEmpty())) {
        errors.add(path + "." + key + " must be " + type + (parts[1].endsWith("!") ? " (required)" : ""));
      }
    }
  }

  private static void expectation(JSONObject args, String path, List<String> errors) {
    fields("js:string! timeoutMs:integer min:number max:number truthy:boolean", args, path, errors);
    if (args.get("js") instanceof String js && js.isBlank()) { errors.add(path + ".js must not be blank"); }
    if (args.get("timeoutMs") instanceof Number n && (n.longValue() < 0 || n.longValue() > 120000)) {
      errors.add(path + ".timeoutMs must be 0..120000");
    }
  }

  public static List<String> batch(JSONObject params) {
    List<String> errors = new ArrayList<>();
    if (params == null) { return List.of("params.commands required"); }
    fields("commands:array! stopOnError:boolean stopOnExpectFailure:boolean async:boolean maxDurationMs:integer " + COMMON,
        params, "params", errors);
    if (!(params.get("commands") instanceof JSONArray steps)) { return errors; }
    if (steps.isEmpty()) { errors.add("params.commands must not be empty"); }
    if (steps.size() > PlaywrightService.commandMaxPerBatch()) {
      errors.add("params.commands exceeds batch limit");
      return errors;
    }
    for (int i = 0; i < steps.size(); i++) {
      String path = "steps[" + i + "]";
      if (!(steps.get(i) instanceof JSONObject step)) { errors.add(path + " must be an object"); continue; }
      List<String> names = new ArrayList<>(step.keySet());
      names.remove("expect");
      if (names.size() != 1) { errors.add(path + " requires one command and optional expect"); continue; }
      String method = names.get(0);
      Object args = step.get(method);
      if ("commands".equals(method)) { errors.add(path + ".commands cannot be nested"); }
      else if (args != null && !(args instanceof JSONObject)) { errors.add(path + "." + method + " must be an object"); }
      else { errors.addAll(validate(method, (JSONObject) args, path + "." + method)); }
      if (step.get("expect") instanceof JSONObject expect) { expectation(expect, path + ".expect", errors); }
      else if (step.get("expect") != null) { errors.add(path + ".expect must be an object"); }
    }
    return errors;
  }

  private CommandSchema() {}
}
