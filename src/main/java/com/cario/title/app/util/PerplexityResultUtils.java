package com.cario.title.app.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities to normalize Perplexity API responses into a single "business JSON" object.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Understands multiple response shapes (message.parsed, message.content as String/Map/List).
 *   <li>Defensive JSON parsing with repair passes (remove BOM, escape raw newlines in strings,
 *       strip trailing commas).
 *   <li>Merging of multiple pages with list concatenation, object merge, and leaf {@code {"value",
 *       "confidence"}} preference by highest confidence.
 * </ul>
 */
public final class PerplexityResultUtils {
  private static final Logger log = LoggerFactory.getLogger(PerplexityResultUtils.class);

  private PerplexityResultUtils() {}

  /* =========================
   * Public entry points
   * ========================= */

  /** Collapse many page envelopes (by page key) into a single business JSON map. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> collapsePerplexityResultsToBusinessJson(
      Map<String, Object> envelopesByPage, ObjectMapper mapper) {

    Map<String, Object> merged = new LinkedHashMap<>();
    if (envelopesByPage == null || envelopesByPage.isEmpty()) return merged;

    for (Map.Entry<String, Object> e : envelopesByPage.entrySet()) {
      Object v = e.getValue();
      if (!(v instanceof Map<?, ?> pageEnvelope)) {
        log.warn("Skipping non-map page entry: {}", e.getKey());
        continue;
      }
      Map<String, Object> business =
          extractBusinessFromEnvelope((Map<String, Object>) pageEnvelope, mapper);
      if (business == null || business.isEmpty()) {
        log.warn("No business JSON extracted for {}", e.getKey());
        continue;
      }
      mergeBusinessJson(merged, business);
    }
    return merged;
  }

  /** Collapse a list of envelopes into a single business JSON map. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> collapsePerplexityResultsToBusinessJson(
      List<Map<String, Object>> envelopes, ObjectMapper mapper) {

    Map<String, Object> merged = new LinkedHashMap<>();
    if (envelopes == null || envelopes.isEmpty()) return merged;

    int i = 0;
    for (Object v : envelopes) {
      i++;
      if (!(v instanceof Map<?, ?> envelope)) {
        log.warn("Skipping non-map entry at index {}", i - 1);
        continue;
      }
      Map<String, Object> business =
          extractBusinessFromEnvelope((Map<String, Object>) envelope, mapper);
      if (business == null || business.isEmpty()) {
        log.warn("No business JSON extracted at index {}", i - 1);
        continue;
      }
      mergeBusinessJson(merged, business);
    }
    return merged;
  }

  /** Extract just the business JSON from a single Perplexity envelope. */
  public static Map<String, Object> extractBusinessFromEnvelope(
      Map<String, Object> envelope, ObjectMapper mapper) {
    return _extractBusinessFromEnvelope(envelope, mapper);
  }

  /** Pretty-print any object via the provided ObjectMapper. */
  public static String toPrettyJson(Object obj, ObjectMapper mapper) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    } catch (Exception e) {
      throw new RuntimeException("Failed to pretty-print JSON", e);
    }
  }

  /* =========================
   * Core logic
   * ========================= */

  @SuppressWarnings("unchecked")
  private static Map<String, Object> _extractBusinessFromEnvelope(
      Map<String, Object> envelope, ObjectMapper mapper) {

    // If it already looks like "business JSON", return as-is.
    if (looksLikeBusiness(envelope)) return envelope;

    Object choicesObj = envelope.get("choices");
    if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
      Object c0 = choices.get(0);
      if (c0 instanceof Map<?, ?> choice) {
        // OpenAI-like shape: choices[0].message.{parsed|content}
        Object messageObj = ((Map<?, ?>) choice).get("message");
        if (messageObj instanceof Map<?, ?> message) {
          // 1) response_format=json_object/json_schema may produce 'parsed'
          Object parsed = ((Map<?, ?>) message).get("parsed");
          if (parsed instanceof Map<?, ?> parsedMap) {
            return (Map<String, Object>) parsedMap;
          }

          // 2) content as Map (rare)
          Object contentObj = ((Map<?, ?>) message).get("content");
          if (contentObj instanceof Map<?, ?> contentAsMap) {
            return (Map<String, Object>) contentAsMap;
          }

          // 3) content as String (possibly with ```json fences)
          if (contentObj instanceof String contentStr) {
            String jsonText = extractJsonString(contentStr);
            Map<String, Object> m = tryParseJsonString(mapper, jsonText);
            if (m != null) return m;
          }

          // 4) content as list of parts with "text"
          if (contentObj instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
              if (p instanceof Map<?, ?> part) {
                Object text = ((Map<?, ?>) part).get("text");
                if (text instanceof String s && !s.isBlank()) sb.append(s).append("\n");
              }
            }
            String jsonText = extractJsonString(sb.toString());
            Map<String, Object> m = tryParseJsonString(mapper, jsonText);
            if (m != null) return m;
          }
        }

        // Some providers may put the "parsed" at the choice level
        Object parsedAtChoice = ((Map<?, ?>) choice).get("parsed");
        if (parsedAtChoice instanceof Map<?, ?> parsedMap2) {
          return (Map<String, Object>) parsedMap2;
        }
      }
    }

    // Fallback: stringify the envelope, extract the biggest JSON-looking block, and parse
    // defensively
    String raw = safeToString(envelope);
    String candidate = extractJsonString(raw);
    Map<String, Object> m = tryParseJsonString(mapper, candidate);
    return m != null ? m : Collections.emptyMap();
  }

  /**
   * Merge src into dest with array-concat, object-merge, and leaf {value,confidence} resolution.
   */
  @SuppressWarnings("unchecked")
  public static void mergeBusinessJson(Map<String, Object> dest, Map<String, Object> src) {
    if (src == null || src.isEmpty()) return;
    for (Map.Entry<String, Object> e : src.entrySet()) {
      String key = e.getKey();
      Object sVal = e.getValue();
      Object dVal = dest.get(key);

      if (dVal == null) {
        dest.put(key, cloneValue(sVal));
        continue;
      }

      // List + List => concat
      if (dVal instanceof List<?> dl && sVal instanceof List<?> sl) {
        List<Object> merged = new ArrayList<>(dl);
        merged.addAll(sl);
        dest.put(key, merged);
        continue;
      }

      // Map + Map => recurse or pick by confidence if leaf
      if (dVal instanceof Map<?, ?> dm && sVal instanceof Map<?, ?> sm) {
        if (isLeafValueConf((Map<?, ?>) dm) && isLeafValueConf((Map<?, ?>) sm)) {
          dest.put(key, pickByConfidence((Map<String, Object>) dm, (Map<String, Object>) sm));
        } else {
          Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) dm);
          mergeBusinessJson(merged, (Map<String, Object>) sm);
          dest.put(key, merged);
        }
        continue;
      }

      // Scalar/other => overwrite with non-null if present
      dest.put(key, sVal != null ? sVal : dVal);
    }
  }

  /* =========================
   * Helpers
   * ========================= */

  private static boolean looksLikeBusiness(Map<String, Object> m) {
    return m.containsKey("title_information")
        || m.containsKey("owner_information")
        || m.containsKey("lien_information")
        || m.containsKey("assignment_of_vehicle")
        || m.containsKey("officials");
  }

  private static boolean isLeafValueConf(Map<?, ?> m) {
    return m.containsKey("value") && m.containsKey("confidence") && m.size() <= 3;
  }

  private static Map<String, Object> pickByConfidence(
      Map<String, Object> a, Map<String, Object> b) {
    int ca = toInt(a.get("confidence"));
    int cb = toInt(b.get("confidence"));
    Object va = a.get("value");
    Object vb = b.get("value");

    if (cb > ca) return b;
    if (ca > cb) return a;
    if (va == null && vb != null) return b; // equal confidence: prefer non-null
    return a;
  }

  private static int toInt(Object o) {
    if (o instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(String.valueOf(o));
    } catch (Exception e) {
      return 0;
    }
  }

  @SuppressWarnings("unchecked")
  private static Object cloneValue(Object v) {
    if (v instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
    if (v instanceof List<?> l) return new ArrayList<>(l);
    return v;
  }

  private static String safeToString(Object o) {
    try {
      return String.valueOf(o);
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Extract JSON from triple-backtick fences (```json ... ```). If none are found, fallback to the
   * largest brace block in the text.
   */
  private static String extractJsonString(String content) {
    if (content == null) return null;
    String s = content.trim();

    // fenced code blocks
    Pattern fenced = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```");
    Matcher m = fenced.matcher(s);
    String candidate = null;
    while (m.find()) {
      candidate = m.group(1); // last fenced block wins
    }
    if (candidate != null) return candidate;

    // largest brace block
    int first = s.indexOf('{');
    int last = s.lastIndexOf('}');
    if (first >= 0 && last > first) {
      return s.substring(first, last + 1);
    }
    return s; // as-is
  }

  /**
   * Defensive JSON parsing:
   *
   * <ol>
   *   <li>Try direct parse.
   *   <li>If it fails, remove BOM, escape raw newlines in strings, and strip trailing commas.
   * </ol>
   */
  private static Map<String, Object> tryParseJsonString(ObjectMapper mapper, String json) {
    if (json == null || json.isBlank()) return null;

    // First attempt (fast path).
    try {
      JsonNode node = mapper.readTree(json);
      if (node.isObject()) {
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
      }
      return null;
    } catch (Exception first) {
      // Defensive repair pass.
      String repaired = removeBOM(json);
      repaired = escapeNewlinesInsideStrings(repaired);
      repaired = stripTrailingCommas(repaired);
      try {
        JsonNode node = mapper.readTree(repaired);
        if (node.isObject()) {
          return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        }
        return null;
      } catch (Exception second) {
        log.warn("JSON parse failed (len={}): {}", json.length(), second.toString());
        return null;
      }
    }
  }

  /** Remove UTF-8 BOM if present. */
  private static String removeBOM(String s) {
    if (s == null) return null;
    if (s.startsWith("\uFEFF")) return s.substring(1);
    return s;
  }

  /** Replace literal CR/LF that appear inside JSON strings with escaped {@code \\r}/{@code \\n}. */
  private static String escapeNewlinesInsideStrings(String s) {
    if (s == null || s.isEmpty()) return s;
    StringBuilder out = new StringBuilder(s.length());
    boolean inStr = false, esc = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (esc) {
          out.append(c);
          esc = false;
          continue;
        }
        if (c == '\\') {
          out.append(c);
          esc = true;
          continue;
        }
        if (c == '"') {
          out.append(c);
          inStr = false;
          continue;
        }
        if (c == '\n') {
          out.append("\\n");
          continue;
        }
        if (c == '\r') {
          out.append("\\r");
          continue;
        }
        out.append(c);
      } else {
        if (c == '"') inStr = true;
        out.append(c);
      }
    }
    return out.toString();
  }

  /** Remove trailing commas like {@code {"a":1,}} and {@code [1,2,]}. */
  private static String stripTrailingCommas(String s) {
    if (s == null || s.isEmpty()) return s;
    // Removes commas before } or ]
    return s.replaceAll(",\\s*([}\\]])", "$1");
  }
}
