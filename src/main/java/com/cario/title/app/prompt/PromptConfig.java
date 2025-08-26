package com.cario.title.app.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

@Data
public class PromptConfig {
  @JsonProperty("system")
  private String systemTemplate;

  @JsonProperty("user")
  private String userTemplate;

  @JsonProperty("rules")
  private Map<String, String> rules;
}
