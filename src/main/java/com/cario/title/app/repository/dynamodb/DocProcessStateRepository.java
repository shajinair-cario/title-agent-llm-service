package com.cario.title.app.repository.dynamodb;

import java.time.Instant;
import java.util.*;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Repository over the Enhanced DynamoDB table for DocProcessStateItem. */
@Log4j2
@Repository
public class DocProcessStateRepository {

  private final DynamoDbEnhancedClient enhanced;
  private final DynamoDbTable<DocProcessStateItem> table;

  public DocProcessStateRepository(
      DynamoDbClient ddb,
      @Value("${aws.dynamodb.docstate.table:DocProcessState}") String tableName) {
    this.enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();
    this.table = enhanced.table(tableName, TableSchema.fromBean(DocProcessStateItem.class));
  }

  // -------- Basic CRUD --------
  public DocProcessStateItem get(String documentId) {
    if (documentId == null) return null;
    return table.getItem(Key.builder().partitionValue(documentId).build());
  }

  /** Upsert (blind put). */
  public void saveRaw(DocProcessStateItem item) {
    Objects.requireNonNull(item, "item");
    if (item.getCreatedAt() == null) item.setCreatedAt(Instant.now());
    item.setUpdatedAt(Instant.now());
    if (item.getPhases() == null) item.setPhases(new HashMap<>());
    table.putItem(item);
    log.debug("docstate.saveRaw docId={} status={}", item.getDocumentId(), item.getOverallStatus());
  }

  // -------- Convenience helpers --------

  /** Initialize doc state if not exists; returns current state. */
  public DocProcessStateItem initIfAbsent(String documentId, String correlationId) {
    DocProcessStateItem existing = get(documentId);
    if (existing != null) return existing;
    DocProcessStateItem fresh =
        DocProcessStateItem.builder()
            .documentId(documentId)
            .correlationId(correlationId)
            .overallStatus("PENDING")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .phases(new HashMap<>())
            .build();
    table.putItem(fresh);
    log.info("docstate.init docId={} corr={}", documentId, correlationId);
    return fresh;
  }

  /** Upsert/update a single phase with merge semantics. */
  public void upsertPhase(String documentId, String phaseName, PhaseRecordItem phase) {
    table.updateItem(
        item -> item.ignoreNulls(true).item(mergePhase(getOrCreate(documentId), phaseName, phase)));
    log.info("docstate.upsertPhase docId={} phase={}", documentId, phaseName);
  }

  /** Append an artifact under a phase/type. */
  public void appendArtifact(
      String documentId, String phaseName, String type, ArtifactItem artifact) {
    DocProcessStateItem state = getOrCreate(documentId);
    state
        .getPhases()
        .computeIfAbsent(
            phaseName,
            k ->
                PhaseRecordItem.builder()
                    .status("STARTED")
                    .startedAt(Instant.now())
                    .attempts(1)
                    .build());
    PhaseRecordItem phase = state.getPhases().get(phaseName);
    Map<String, List<ArtifactItem>> byType =
        phase.getArtifactsByType() == null
            ? new HashMap<>()
            : new HashMap<>(phase.getArtifactsByType());
    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(artifact);
    phase.setArtifactsByType(byType);
    state.setUpdatedAt(Instant.now());
    table.putItem(state);
    log.info(
        "docstate.appendArtifact docId={} phase={} type={} key={}",
        documentId,
        phaseName,
        type,
        artifact.getKey());
  }

  /** Set top-level status + final output pointers. */
  public void setOverall(String documentId, String status, String finalKey, String finalS3Uri) {
    DocProcessStateItem state = getOrCreate(documentId);
    state.setOverallStatus(status);
    state.setFinalOutputKey(finalKey);
    state.setFinalOutputS3Uri(finalS3Uri);
    state.setUpdatedAt(Instant.now());
    table.putItem(state);
    log.info("docstate.setOverall docId={} status={} finalKey={}", documentId, status, finalKey);
  }

  // -------- Internals --------

  private DocProcessStateItem getOrCreate(String documentId) {
    DocProcessStateItem s = get(documentId);
    if (s != null) return s;
    s =
        DocProcessStateItem.builder()
            .documentId(documentId)
            .overallStatus("PENDING")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .phases(new HashMap<>())
            .build();
    table.putItem(s);
    return s;
  }

  private DocProcessStateItem mergePhase(
      DocProcessStateItem base, String name, PhaseRecordItem incoming) {
    Map<String, PhaseRecordItem> phases =
        base.getPhases() == null ? new HashMap<>() : new HashMap<>(base.getPhases());
    PhaseRecordItem merged = phases.getOrDefault(name, PhaseRecordItem.builder().build());

    // merge simple fields (prefer incoming non-null)
    if (incoming.getStatus() != null) merged.setStatus(incoming.getStatus());
    if (incoming.getStartedAt() != null) merged.setStartedAt(incoming.getStartedAt());
    if (incoming.getCompletedAt() != null) merged.setCompletedAt(incoming.getCompletedAt());
    if (incoming.getAttempts() != null) merged.setAttempts(incoming.getAttempts());
    if (incoming.getDurationMs() != null) merged.setDurationMs(incoming.getDurationMs());
    if (incoming.getInputS3Uri() != null) merged.setInputS3Uri(incoming.getInputS3Uri());
    if (incoming.getOutputS3Uri() != null) merged.setOutputS3Uri(incoming.getOutputS3Uri());

    if (incoming.getAvgConfidence() != null) merged.setAvgConfidence(incoming.getAvgConfidence());
    if (incoming.getMinConfidence() != null) merged.setMinConfidence(incoming.getMinConfidence());
    if (incoming.getMaxConfidence() != null) merged.setMaxConfidence(incoming.getMaxConfidence());
    if (incoming.getBlockCount() != null) merged.setBlockCount(incoming.getBlockCount());

    if (incoming.getModelName() != null) merged.setModelName(incoming.getModelName());
    if (incoming.getPromptKey() != null) merged.setPromptKey(incoming.getPromptKey());
    if (incoming.getPromptVersion() != null) merged.setPromptVersion(incoming.getPromptVersion());
    if (incoming.getSchemaName() != null) merged.setSchemaName(incoming.getSchemaName());

    if (incoming.getMessages() != null && !incoming.getMessages().isEmpty()) {
      List<String> mergedMsgs =
          new ArrayList<>(Optional.ofNullable(merged.getMessages()).orElseGet(ArrayList::new));
      mergedMsgs.addAll(incoming.getMessages());
      merged.setMessages(mergedMsgs);
    }

    if (incoming.getArtifactsByType() != null && !incoming.getArtifactsByType().isEmpty()) {
      Map<String, List<ArtifactItem>> mergedArtifacts =
          new HashMap<>(Optional.ofNullable(merged.getArtifactsByType()).orElseGet(HashMap::new));
      incoming
          .getArtifactsByType()
          .forEach((k, v) -> mergedArtifacts.computeIfAbsent(k, kk -> new ArrayList<>()).addAll(v));
      merged.setArtifactsByType(mergedArtifacts);
    }

    phases.put(name, merged);
    base.setPhases(phases);
    base.setUpdatedAt(Instant.now());
    return base;
  }
}
