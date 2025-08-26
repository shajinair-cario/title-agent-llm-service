package com.cario.title.app.repository.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/** Captures the lifecycle of a single processing phase (UPLOAD, TEXTRACT, NLP, PIPELINE, etc.). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class PhaseRecordItem {

  /** Phase status: STARTED | SUCCEEDED | FAILED. */
  private String status;

  /** ISO timestamps. */
  private Instant startedAt;

  private Instant completedAt;

  /** Number of attempts so far for this phase. */
  private Integer attempts;

  /** Wall-clock duration (ms), optional shortcut. */
  private Long durationMs;

  /** Input & output S3 URIs, if applicable. */
  private String inputS3Uri;

  private String outputS3Uri;

  /** Confidence stats (for Textract, OCR, etc.). */
  private Double avgConfidence;

  private Double minConfidence;
  private Double maxConfidence;
  private Integer blockCount;

  /** Model/prompt metadata for AI phases. */
  private String modelName;

  private String promptKey; // e.g. s3://bucket/prompts/title-prompts.yaml
  private String promptVersion; // if you version prompts
  private String schemaName; // JSON schema name used

  /** Free-form messages, warnings, error messages. */
  private List<String> messages;

  /** Artifacts grouped by type, e.g. "json" -> [ArtifactItem, ...]. */
  private Map<String, List<ArtifactItem>> artifactsByType;

  @DynamoDbAttribute("status")
  public String getStatus() {
    return status;
  }

  @DynamoDbAttribute("startedAt")
  public Instant getStartedAt() {
    return startedAt;
  }

  @DynamoDbAttribute("completedAt")
  public Instant getCompletedAt() {
    return completedAt;
  }

  @DynamoDbAttribute("attempts")
  public Integer getAttempts() {
    return attempts;
  }

  @DynamoDbAttribute("durationMs")
  public Long getDurationMs() {
    return durationMs;
  }

  @DynamoDbAttribute("inputS3Uri")
  public String getInputS3Uri() {
    return inputS3Uri;
  }

  @DynamoDbAttribute("outputS3Uri")
  public String getOutputS3Uri() {
    return outputS3Uri;
  }

  @DynamoDbAttribute("avgConfidence")
  public Double getAvgConfidence() {
    return avgConfidence;
  }

  @DynamoDbAttribute("minConfidence")
  public Double getMinConfidence() {
    return minConfidence;
  }

  @DynamoDbAttribute("maxConfidence")
  public Double getMaxConfidence() {
    return maxConfidence;
  }

  @DynamoDbAttribute("blockCount")
  public Integer getBlockCount() {
    return blockCount;
  }

  @DynamoDbAttribute("modelName")
  public String getModelName() {
    return modelName;
  }

  @DynamoDbAttribute("promptKey")
  public String getPromptKey() {
    return promptKey;
  }

  @DynamoDbAttribute("promptVersion")
  public String getPromptVersion() {
    return promptVersion;
  }

  @DynamoDbAttribute("schemaName")
  public String getSchemaName() {
    return schemaName;
  }

  @DynamoDbAttribute("messages")
  public List<String> getMessages() {
    return messages;
  }

  @DynamoDbAttribute("artifactsByType")
  public Map<String, List<ArtifactItem>> getArtifactsByType() {
    return artifactsByType;
  }
}
