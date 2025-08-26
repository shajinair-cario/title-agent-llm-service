package com.cario.title.app.config;

import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import com.cario.title.app.service.*;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.textract.TextractClient;

@Configuration
@RequiredArgsConstructor
public class ServiceConfig {

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final TextractClient textractClient;
  private final ChatClient.Builder chatClientBuilder;
  private final DocProcessStateRepository docProcessStateRepository;
  private final JdbcTemplate jdbcTemplate;
  private final OpenAiEmbeddingModel embeddingModel;

  // -------------------
  // Utility
  // -------------------

  @ConfigurationProperties(prefix = "perplexity.service")
  @Data
  public class PerplexityServiceProperties {
    private boolean enabled;
    private List<String> fields;
  }

  @Bean
  public PromptLoaderService promptLoaderService() {
    return new PromptLoaderService(s3Client);
  }

  // -------------------
  // Core Services
  // -------------------

  @Bean
  public DocUploadService docUploadService() {
    return new DocUploadService(s3Client);
  }

  @Bean
  public TextractService textractService() {
    return new TextractService(s3Client, textractClient, jdbcTemplate, embeddingModel);
  }

  @Bean
  public AiNlpService aiNlpService(PromptLoaderService promptLoaderService) {
    return new AiNlpService(
        chatClientBuilder, s3Client, promptLoaderService, jdbcTemplate, embeddingModel);
  }

  @Bean
  public AiPipelineService aiPipelineService(
      TextractService textractService,
      AiNlpService aiNlpService,
      PerplexityExtractService perplexityExtractService,
      S3Client s3Client,
      PerplexityServiceProperties perplexityProps) {

    return new AiPipelineService(
        textractService, aiNlpService, perplexityExtractService, s3Client, perplexityProps);
  }

  @Bean
  public StatusService statusService() {
    return new StatusService(docProcessStateRepository);
  }

  @Bean
  public PerplexityExtractService perplexityExtractService(
      WebClient.Builder builder, S3Client s3Client) {
    return new PerplexityExtractService(builder, s3Client, s3Presigner);
  }
}
