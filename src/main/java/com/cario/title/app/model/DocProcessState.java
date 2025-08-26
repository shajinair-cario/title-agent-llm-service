package com.cario.title.app.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the complete processing state of a document in the system.
 *
 * <p>This model is used at the application layer to track a document's life cycle through multiple
 * processing phases, including:
 *
 * <ul>
 *   <li>Ingestion
 *   <li>Validation
 *   <li>Extraction
 *   <li>Transformation
 *   <li>Summarization
 *   <li>Loading
 *   <li>Archival
 * </ul>
 *
 * <p>It stores:
 *
 * <ul>
 *   <li>The current and overall status
 *   <li>Time stamps for creation and last update
 *   <li>Details for each phase (status, artifacts)
 *   <li>Latest outputs grouped by artifact type
 * </ul>
 *
 * <p>Defensive copies are used for mutable fields such as {@link Instant} and {@link Map} to
 * prevent exposing internal state.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocProcessState {

  /** Enumeration of document processing phases. */
  public enum Phase {
    INGEST,
    VALIDATE,
    EXTRACT,
    TRANSFORM,
    SUMMARIZE,
    LOAD,
    ARCHIVE
  }

  /** Enumeration of document processing statuses. */
  public enum Status {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
  }

  /** Unique identifier of the document (e.g., S3 path or generated UUID). */
  private String documentId;

  /** Overall status of the document processing workflow. */
  private Status overallStatus;

  /** Current phase in which the document is being processed. */
  private Phase currentPhase;

  /** Timestamp when this document record was created. */
  private Instant createdAt;

  /** Timestamp when this document record was last updated. */
  private Instant updatedAt;

  /** Optional S3 key or URI of the final output after processing. */
  private String finalOutputKey;

  /** Map of processing phase → corresponding phase record. */
  @Builder.Default private Map<Phase, PhaseRecord> phases = new EnumMap<>(Phase.class);

  /** Map of artifact type → latest artifact produced for that type. */
  @Builder.Default private Map<String, Artifact> latestOutputs = new HashMap<>();

  // ---- Defensive copy overrides ----

  public Instant getCreatedAt() {
    return createdAt == null ? null : Instant.from(createdAt);
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt == null ? null : Instant.from(createdAt);
  }

  public Instant getUpdatedAt() {
    return updatedAt == null ? null : Instant.from(updatedAt);
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt == null ? null : Instant.from(updatedAt);
  }

  public Map<Phase, PhaseRecord> getPhases() {
    return phases == null ? null : new EnumMap<>(phases);
  }

  public void setPhases(Map<Phase, PhaseRecord> phases) {
    this.phases = phases == null ? null : new EnumMap<>(phases);
  }

  public Map<String, Artifact> getLatestOutputs() {
    return latestOutputs == null ? null : new HashMap<>(latestOutputs);
  }

  public void setLatestOutputs(Map<String, Artifact> latestOutputs) {
    this.latestOutputs = latestOutputs == null ? null : new HashMap<>(latestOutputs);
  }

  /**
   * Creates a new {@code DocProcessState} initialized with {@code PENDING} status and {@code
   * INGEST} phase.
   *
   * @param documentId the document identifier
   * @return a new initialized instance
   */
  public static DocProcessState newState(String documentId) {
    return DocProcessState.builder()
        .documentId(documentId)
        .overallStatus(Status.PENDING)
        .currentPhase(Phase.INGEST)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }
}
