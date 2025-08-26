package com.cario.title.app.repository.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/** Small helper to append well-formed phase entries/artifacts consistently. */
@Log4j2
@Component
@RequiredArgsConstructor
public class DocArtifactAppender {

  private final DocProcessStateRepository repo;

  public void recordTextractSuccess(
      String docId,
      String inputS3Uri,
      String outputS3Uri,
      double avgConf,
      double minConf,
      double maxConf,
      int blockCount,
      List<ArtifactItem> jsonArtifacts) {

    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .startedAt(Instant.now()) // ideally pass real start time
            .completedAt(Instant.now())
            .attempts(1)
            .durationMs(null) // set if you track elapsed
            .inputS3Uri(inputS3Uri)
            .outputS3Uri(outputS3Uri)
            .avgConfidence(avgConf)
            .minConfidence(minConf)
            .maxConfidence(maxConf)
            .blockCount(blockCount)
            .artifactsByType(Map.of("json", jsonArtifacts))
            .messages(List.of("Textract high-confidence blocks saved"))
            .build();

    repo.upsertPhase(docId, "TEXTRACT", phase);
    repo.setOverall(docId, "TEXTRACT_COMPLETED", null, null);
  }

  public void recordNlpSuccess(
      String docId,
      String inputS3Uri,
      String outputS3Uri,
      String modelName,
      String promptKey,
      String promptVersion,
      String schemaName,
      List<ArtifactItem> jsonArtifacts) {

    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("SUCCEEDED")
            .startedAt(Instant.now())
            .completedAt(Instant.now())
            .attempts(1)
            .inputS3Uri(inputS3Uri)
            .outputS3Uri(outputS3Uri)
            .modelName(modelName)
            .promptKey(promptKey)
            .promptVersion(promptVersion)
            .schemaName(schemaName)
            .artifactsByType(Map.of("json", jsonArtifacts))
            .messages(List.of("NLP normalization completed"))
            .build();

    repo.upsertPhase(docId, "NLP", phase);
    repo.setOverall(docId, "NLP_COMPLETED", null, null);
  }

  public void recordFailure(String docId, String phaseName, String message) {
    PhaseRecordItem phase =
        PhaseRecordItem.builder()
            .status("FAILED")
            .completedAt(Instant.now())
            .attempts(1)
            .messages(List.of(message))
            .build();
    repo.upsertPhase(docId, phaseName, phase);
    repo.setOverall(docId, "FAILED", null, null);
  }
}
