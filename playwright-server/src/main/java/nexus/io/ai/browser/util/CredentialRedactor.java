package nexus.io.ai.browser.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

/**
 * Masks credential values that travel inside free-form text such as HTTP request bodies.
 *
 * <p>
 * Structured envelope fields are handled by the trace redaction; this class covers credentials that sit inside a
 * string: a JSON body ({@code {"apiKey":"..."}}) or a form-encoded body ({@code a=1&password=...}). Only values whose
 * key matches {@link #secretKey(String)} are replaced, so the rest of the body stays readable.
 */
public final class CredentialRedactor {

  /** Default replacement for a masked value. */
  public static final String MASK = "***";

  /** Bodies larger than this are not parsed as a whole; the quoted-pair fallback still applies. */
  private static final int MAX_PARSE_CHARS = 2_000_000;

  /** Lower-case fragments that must appear somewhere before any work is done (cheap pre-check). */
  private static final String[] HINTS = { "pass", "authorization", "token", "apikey", "api_key", "api-key", "secret",
      "cookie" };

  /** A quoted JSON string pair; used when the text is not valid JSON (for example a truncated body). */
  private static final Pattern QUOTED_PAIR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"(\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"");

  private CredentialRedactor() {
  }

  /**
   * Whether a field name denotes a credential: password, Authorization, token, API key, secret or Cookie.
   * Separators {@code -} and {@code _} and letter case are ignored; {@code cookies} (the list) is not a secret.
   */
  public static boolean secretKey(String name) {
    if (name == null) {
      return false;
    }
    String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    if (normalized.equals("cookies")) {
      return false;
    }
    return normalized.equals("token") || normalized.contains("password") || normalized.contains("passwd")
        || normalized.contains("authorization") || normalized.contains("accesstoken")
        || normalized.contains("refreshtoken") || normalized.contains("idtoken") || normalized.contains("apikey")
        || normalized.contains("secret") || normalized.contains("cookie");
  }

  /** Same as {@link #redactBody(String, String)} with the default mask. */
  public static String redactBody(String text) {
    return redactBody(text, MASK);
  }

  /**
   * Masks credential values inside a JSON or form-encoded text.
   *
   * @return the same instance when nothing was masked, otherwise the masked text
   */
  public static String redactBody(String text, String mask) {
    if (text == null || text.length() < 3 || !mayContainSecret(text)) {
      return text;
    }
    String trimmed = text.strip();
    char first = trimmed.isEmpty() ? ' ' : trimmed.charAt(0);
    if (first == '{' || first == '[') {
      if (trimmed.length() <= MAX_PARSE_CHARS) {
        Object parsed = parseJson(trimmed);
        if (parsed != null) {
          if (!redactNode(parsed, mask)) {
            return text;
          }
          return JSON.toJSONString(parsed, JSONWriter.Feature.WriteMapNullValue);
        }
      }
      return redactQuotedPairs(text, mask);
    }
    if (looksFormEncoded(trimmed)) {
      return redactForm(text, mask);
    }
    return redactQuotedPairs(text, mask);
  }

  /**
   * Masks credential fields of an already parsed JSON tree in place.
   *
   * @return whether anything was masked
   */
  public static boolean redactNode(Object node, String mask) {
    boolean changed = false;
    if (node instanceof JSONObject object) {
      boolean namedSecret = object.get("name") instanceof String name && secretKey(name);
      for (String key : new ArrayList<>(object.keySet())) {
        Object value = object.get(key);
        if (value != null && !"".equals(value) && (secretKey(key) || (namedSecret && "value".equals(key)))) {
          if (!mask.equals(value)) {
            object.put(key, mask);
            changed = true;
          }
        } else if (value instanceof String string) {
          String redacted = redactBody(string, mask);
          if (redacted != string) {
            object.put(key, redacted);
            changed = true;
          }
        } else if (redactNode(value, mask)) {
          changed = true;
        }
      }
    } else if (node instanceof JSONArray array) {
      for (int i = 0; i < array.size(); i++) {
        Object value = array.get(i);
        if (value instanceof String string) {
          String redacted = redactBody(string, mask);
          if (redacted != string) {
            array.set(i, redacted);
            changed = true;
          }
        } else if (redactNode(value, mask)) {
          changed = true;
        }
      }
    }
    return changed;
  }

  private static boolean mayContainSecret(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    for (String hint : HINTS) {
      if (lower.contains(hint)) {
        return true;
      }
    }
    return false;
  }

  private static Object parseJson(String text) {
    try {
      Object parsed = JSON.parse(text);
      if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
        return parsed;
      }
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Form bodies have no whitespace and every non-empty piece is {@code key=value}. */
  private static boolean looksFormEncoded(String text) {
    if (text.isEmpty() || text.indexOf('=') < 0) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    for (String piece : text.split("&")) {
      if (!piece.isEmpty() && piece.indexOf('=') <= 0) {
        return false;
      }
    }
    return true;
  }

  private static String redactForm(String text, String mask) {
    String[] pieces = text.split("&", -1);
    boolean changed = false;
    for (int i = 0; i < pieces.length; i++) {
      int eq = pieces[i].indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = pieces[i].substring(0, eq);
      String value = pieces[i].substring(eq + 1);
      if (!value.isEmpty() && !value.equals(mask) && secretKey(decode(key))) {
        pieces[i] = key + "=" + mask;
        changed = true;
      }
    }
    return changed ? String.join("&", pieces) : text;
  }

  private static String redactQuotedPairs(String text, String mask) {
    Matcher matcher = QUOTED_PAIR.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    boolean changed = false;
    while (matcher.find()) {
      if (secretKey(matcher.group(1)) && !matcher.group(3).isEmpty() && !matcher.group(3).equals(mask)) {
        String replacement = "\"" + matcher.group(1) + "\"" + matcher.group(2) + "\"" + mask + "\"";
        matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        changed = true;
      } else {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
      }
    }
    if (!changed) {
      return text;
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return value;
    }
  }
}
