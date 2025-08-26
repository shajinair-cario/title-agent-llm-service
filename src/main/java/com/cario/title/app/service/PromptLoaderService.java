package com.cario.title.app.service;

import com.cario.title.app.prompt.PromptConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

@Log4j2
@RequiredArgsConstructor
public class PromptLoaderService {

  private final S3Client s3Client;

  // YAML + JSON mappers
  private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
  private final ObjectMapper jsonMapper = new ObjectMapper();

  public PromptConfig load(String bucket, String key) {
    try {
      ResponseBytes<?> bytes =
          s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
      String raw = bytes.asString(StandardCharsets.UTF_8);

      // Try YAML first
      try {
        Map<String, Object> map = yamlMapper.readValue(raw, new TypeReference<>() {});
        PromptConfig cfg = new PromptConfig();
        cfg.setSystemTemplate(asString(map.get("system")));
        cfg.setUserTemplate(asString(map.get("user")));
        cfg.setRules(toStringMap(map.get("rules"))); // <-- convert to Map<String,String>
        log.info("Prompt loaded from s3://{}/{} (YAML)", bucket, key);
        return cfg;
      } catch (Exception yamlErr) {
        log.warn("YAML parse failed, trying JSON. Reason={}", yamlErr.getMessage());
      }

      // Fallback: JSON directly into POJO (assumes rules are already Map<String,String>)
      PromptConfig cfg = jsonMapper.readValue(raw, PromptConfig.class);
      if (cfg.getRules() == null) {
        cfg.setRules(Map.of());
      }
      log.info("Prompt loaded from s3://{}/{} (JSON)", bucket, key);
      return cfg;

    } catch (Exception e) {
      log.error("Failed to load prompts from s3://{}/{}", bucket, key, e);
      throw new RuntimeException("Failed to load prompts from s3://" + bucket + "/" + key, e);
    }
  }

  private static String asString(Object o) {
    return (o == null) ? null : o.toString();
  }

  /** Convert arbitrary YAML values to Map<String,String> for PromptConfig.setRules signature. */
  @SuppressWarnings("unchecked")
  private static Map<String, String> toStringMap(Object node) {
    if (node == null) return Map.of();
    if (node instanceof Map<?, ?> src) {
      Map<String, String> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : src.entrySet()) {
        String k = Objects.toString(e.getKey(), "");
        String v = (e.getValue() == null) ? null : e.getValue().toString();
        out.put(k, v);
      }
      return out;
    }
    // If rules isn’t a map, return empty (or log/throw if you prefer strictness)
    return Map.of();
  }
}
