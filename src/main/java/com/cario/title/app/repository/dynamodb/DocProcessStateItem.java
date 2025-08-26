package com.cario.title.app.repository.dynamodb;

import java.time.Instant;
import java.util.Map;
import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/**
 * Document-level record that holds current state and all phase summaries. Use alongside an
 * append-only event log if you need full audit history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class DocProcessStateItem {

  /** Partition key: unique doc id (could be input S3 key, UUID, etc.). */
  private String documentId;

  /** Optional correlation id used across services for a single run. */
  private String correlationId;

  /** Overall status: PENDING | RUNNING | COMPLETED | FAILED. */
  private String overallStatus;

  /** Final output pointers (e.g., normalized JSON). */
  private String finalOutputKey;

  private String finalOutputS3Uri;

  /** Creation/update times. */
  private Instant createdAt;

  private Instant updatedAt;

  /** Per-phase records keyed by phase name (UPLOAD, TEXTRACT, NLP, PIPELINE...). */
  private Map<String, PhaseRecordItem> phases;

  // ---------- DynamoDB mapping ----------

  @DynamoDbPartitionKey
  @DynamoDbAttribute("documentId")
  public String getDocumentId() {
    return documentId;
  }

  @DynamoDbAttribute("correlationId")
  public String getCorrelationId() {
    return correlationId;
  }

  @DynamoDbAttribute("overallStatus")
  public String getOverallStatus() {
    return overallStatus;
  }

  @DynamoDbAttribute("finalOutputKey")
  public String getFinalOutputKey() {
    return finalOutputKey;
  }

  @DynamoDbAttribute("finalOutputS3Uri")
  public String getFinalOutputS3Uri() {
    return finalOutputS3Uri;
  }

  @DynamoDbAttribute("createdAt")
  public Instant getCreatedAt() {
    return createdAt;
  }

  @DynamoDbAttribute("updatedAt")
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  @DynamoDbAttribute("phases")
  public Map<String, PhaseRecordItem> getPhases() {
    return phases;
  }
}
