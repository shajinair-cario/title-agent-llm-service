package com.cario.title.app.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatGPT configuration for Spring AI.
 *
 * <p>Provides a {@link ChatClient.Builder} bean that can be injected into services like {@code
 * AiNlpService}.
 *
 * <p>Spring AI will autoconfigure the {@link OpenAiChatModel} using properties in application.yaml
 * (spring.ai.openai.api-key, etc.).
 */
@Configuration
public class ChatGptConfig {

  @Bean
  public ChatClient.Builder chatClientBuilder(OpenAiChatModel openAiChatModel) {
    return ChatClient.builder(openAiChatModel);
  }
}
