package com.cario.title.app.service;

import com.cario.title.app.repository.dynamodb.ArtifactItem;
import com.cario.title.app.repository.dynamodb.DocProcessStateItem;
import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import com.cario.title.app.repository.dynamodb.PhaseRecordItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * Central status/audit writer for document processing.
 *
 * <p>Uses DocProcessStateRepository's helper methods to: - init state (idempotent) - upsert
 * per-phase records (UPLOAD, TEXTRACT, NLP, PIPELINE, ...) - append artifacts under phases - set
 * overall status/final pointers
 *
 * <p>Back-compat methods (saveFileStatus, saveTextractPhase) now delegate to repo helpers.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class StatusService {

  // ---- Phase names (keys in the 'phases' map) ----
  public static final String PHASE_UPLOAD = "UPLOAD";
  public static final String PHASE_TEXTRACT = "TEXTRACT";
  public static final String PHASE_NLP = "NLP";
  public static final String PHASE_PIPELINE = "PIPELINE";

  // ---- Common artifact constants ----
  public static final String ARTIFACT_JSON = "json";
  public static final String CONTENT_TYPE_JSON = "application/json";

  private final DocProcessStateRepository repo;

  // =====================================================================
  // Read helper used by controller
  // =====================================================================

  /** Fetch the full processing state for a document (or null if not found). */
  public DocProcessStateItem getState(String documentId) {
    validateId(documentId);
    return repo.get(documentId);
  }

  // =====================================================================
  // Backward-compat methods (kept, but now implemented via repo helpers)
  // =====================================================================

  /**
   * Sets top-level status + final output key (idempotent). Preserves createdAt and existing phases.
   */
  public void saveFileStatus(String documentId, String status, String outputKey) {
    validateId(documentId);

    DocProcessStateItem current = repo.initIfAbsent(documentId, /*correlationId*/ null);
    String s3Uri = current.getFinalOutputS3Uri(); // preserve if already set

    repo.setOverall(documentId, status, outputKey, s3Uri);
    log.info("status.overall docId={} status={} finalKey={}", documentId, status, outputKey);
  }

  /** Records a simple TEXTRACT phase with a single JSON artifact and confidence. */
  public void saveTextractPhase(
      String documentId, String status, String outputKey, float confidence) {
    validateId(documentId);

    ArtifactItem artifact =
        ArtifactItem.builder()
            .type(ARTIFACT_JSON)
            .key(outputKey)
            .s3Uri(null) // set s3:// if available
            .contentType(CONTENT_TYPE_JSON)
            .createdAt(Instant.now())
            .meta(Map.of("confidence", String.valueOf(confidence)))
            .build();

    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status(status)
            .startedAt(Instant.now())
            .completedAt(Instant.now())
            .attempts(1)
            .artifactsByType(Map.of(ARTIFACT_JSON, List.of(artifact)))
            .messages(List.of("Textract phase recorded via saveTextractPhase"))
            .build();

    repo.upsertPhase(documentId, PHASE_TEXTRACT, phase);
    log.info(
        "phase.textract docId={} status={} key={} conf={}",
        documentId,
        status,
        outputKey,
        confidence);
  }

  // =====================================================================
  // Recommended high-level helpers (use these in your services/controllers)
  // =====================================================================

  /** Initialize a document record if missing (sets correlationId, overall=PENDING). */
  public DocProcessStateItem initRun(String documentId, String correlationId) {
    validateId(documentId);
    DocProcessStateItem state = repo.initIfAbsent(documentId, correlationId);
    log.info("status.init docId={} corr={}", documentId, correlationId);
    return state;
  }

  /** Mark overall status and (optionally) final output locations. */
  public void markOverall(
      String documentId, String overallStatus, String finalOutputKey, String finalOutputS3Uri) {
    validateId(documentId);
    repo.setOverall(documentId, overallStatus, finalOutputKey, finalOutputS3Uri);
    log.info(
        "status.markOverall docId={} status={} finalKey={} finalUri={}",
        documentId,
        overallStatus,
        finalOutputKey,
        finalOutputS3Uri);
  }

  // -------------------- UPLOAD --------------------

  public void recordUploadStarted(String documentId, String inputS3Uri) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("STARTED")
            .startedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .messages(List.of("Upload started"))
            .build();
    repo.upsertPhase(documentId, PHASE_UPLOAD, phase);
    log.info("phase.upload.started docId={} input={}", documentId, inputS3Uri);
  }

  public void recordUploadSucceeded(
      String documentId, String inputS3Uri, ArtifactItem storedObject) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .artifactsByType(Map.of(storedObject.getType(), List.of(storedObject)))
            .messages(List.of("Upload succeeded"))
            .build();
    repo.upsertPhase(documentId, PHASE_UPLOAD, phase);
    log.info("phase.upload.succeeded docId={} uri={}", documentId, inputS3Uri);
  }

  public void recordUploadFailed(String documentId, String inputS3Uri, String error) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("FAILED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .messages(List.of(error))
            .build();
    repo.upsertPhase(documentId, PHASE_UPLOAD, phase);
    repo.setOverall(documentId, "FAILED", null, null);
    log.warn("phase.upload.failed docId={} err={}", documentId, error);
  }

  // -------------------- TEXTRACT --------------------

  public void recordTextractStarted(String documentId, String inputS3Uri, Float threshold) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("STARTED")
            .startedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .messages(
                List.of(
                    "Textract started", "threshold=" + (threshold == null ? "default" : threshold)))
            .build();
    repo.upsertPhase(documentId, PHASE_TEXTRACT, phase);
    log.info(
        "phase.textract.started docId={} input={} threshold={}", documentId, inputS3Uri, threshold);
  }

  public void recordTextractSucceeded(
      String documentId,
      String inputS3Uri,
      String outputS3Uri,
      double avgConf,
      double minConf,
      double maxConf,
      int blockCount,
      ArtifactItem jsonArtifact) {

    validateId(documentId);

    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .outputS3Uri(outputS3Uri)
            .avgConfidence(avgConf)
            .minConfidence(minConf)
            .maxConfidence(maxConf)
            .blockCount(blockCount)
            .artifactsByType(Map.of(ARTIFACT_JSON, List.of(jsonArtifact)))
            .messages(List.of("Textract high-confidence blocks saved"))
            .build();

    repo.upsertPhase(documentId, PHASE_TEXTRACT, phase);
    log.info(
        "phase.textract.succeeded docId={} output={} blocks={} avgConf={}",
        documentId,
        outputS3Uri,
        blockCount,
        avgConf);
  }

  public void recordTextractFailed(String documentId, String inputS3Uri, String error) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("FAILED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .messages(List.of(error))
            .build();
    repo.upsertPhase(documentId, PHASE_TEXTRACT, phase);
    repo.setOverall(documentId, "FAILED", null, null);
    log.warn("phase.textract.failed docId={} err={}", documentId, error);
  }

  // -------------------- NLP --------------------

  public void recordNlpStarted(
      String documentId, String inputS3Uri, String modelName, String promptKey) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("STARTED")
            .startedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .modelName(modelName)
            .promptKey(promptKey)
            .messages(List.of("NLP normalization started"))
            .build();
    repo.upsertPhase(documentId, PHASE_NLP, phase);
    log.info(
        "phase.nlp.started docId={} input={} model={} promptKey={}",
        documentId,
        inputS3Uri,
        modelName,
        promptKey);
  }

  public void recordNlpSucceeded(
      String documentId,
      String inputS3Uri,
      String outputS3Uri,
      String modelName,
      String promptKey,
      String promptVersion,
      String schemaName,
      ArtifactItem jsonArtifact) {

    validateId(documentId);

    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .outputS3Uri(outputS3Uri)
            .modelName(modelName)
            .promptKey(promptKey)
            .promptVersion(promptVersion)
            .schemaName(schemaName)
            .artifactsByType(Map.of(ARTIFACT_JSON, List.of(jsonArtifact)))
            .messages(List.of("NLP normalization completed"))
            .build();

    repo.upsertPhase(documentId, PHASE_NLP, phase);
    log.info(
        "phase.nlp.succeeded docId={} output={} model={} schema={}",
        documentId,
        outputS3Uri,
        modelName,
        schemaName);
  }

  public void recordNlpFailed(
      String documentId, String inputS3Uri, String modelName, String error) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("FAILED")
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .modelName(modelName)
            .messages(List.of(error))
            .build();
    repo.upsertPhase(documentId, PHASE_NLP, phase);
    repo.setOverall(documentId, "FAILED", null, null);
    log.warn("phase.nlp.failed docId={} err={}", documentId, error);
  }

  // -------------------- PIPELINE (wrapper phase) --------------------

  public void recordPipelineStarted(String documentId) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("STARTED")
            .startedAt(Instant.now())
            .attempts(1)
            .messages(List.of("Pipeline started"))
            .build();
    repo.upsertPhase(documentId, PHASE_PIPELINE, phase);
    log.info("phase.pipeline.started docId={}", documentId);
  }

  public void recordPipelineSucceeded(
      String documentId, String finalOutputKey, String finalOutputS3Uri) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .completedAt(Instant.now())
            .attempts(1)
            .messages(List.of("Pipeline completed"))
            .build();
    repo.upsertPhase(documentId, PHASE_PIPELINE, phase);

    repo.setOverall(documentId, "COMPLETED", finalOutputKey, finalOutputS3Uri);
    log.info(
        "phase.pipeline.succeeded docId={} finalKey={} finalUri={}",
        documentId,
        finalOutputKey,
        finalOutputS3Uri);
  }

  public void recordPipelineFailed(String documentId, String error) {
    validateId(documentId);
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("FAILED")
            .completedAt(Instant.now())
            .attempts(1)
            .messages(List.of(error))
            .build();
    repo.upsertPhase(documentId, PHASE_PIPELINE, phase);
    repo.setOverall(documentId, "FAILED", null, null);
    log.warn("phase.pipeline.failed docId={} err={}", documentId, error);
  }

  // -------------------- Generic artifact append --------------------

  public void appendArtifact(
      String documentId, String phaseName, String artifactType, ArtifactItem artifact) {
    validateId(documentId);
    Objects.requireNonNull(phaseName, "phaseName");
    Objects.requireNonNull(artifactType, "artifactType");
    Objects.requireNonNull(artifact, "artifact");

    repo.appendArtifact(documentId, phaseName, artifactType, artifact);
    log.info(
        "phase.artifact.appended docId={} phase={} type={} key={}",
        documentId,
        phaseName,
        artifactType,
        artifact.getKey());
  }

  // =====================================================================

  private static void validateId(String documentId) {
    if (documentId == null || documentId.isBlank()) {
      throw new IllegalArgumentException("documentId must not be null/blank");
    }
  }
}
