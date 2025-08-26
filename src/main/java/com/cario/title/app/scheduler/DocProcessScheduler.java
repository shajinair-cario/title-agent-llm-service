package com.cario.title.app.scheduler;

import com.cario.title.app.repository.dynamodb.DocProcessStateItem;
import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import com.cario.title.app.service.AiPipelineService;
import com.cario.title.app.service.StatusService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

@Log4j2
@RequiredArgsConstructor
public class DocProcessScheduler {

  private final S3Client s3;
  private final AiPipelineService pipeline;
  private final StatusService status;
  private final DocProcessStateRepository stateRepo;

  @Value("${aws.s3.bucket}")
  private String bucket;

  @Value("${aws.s3.input-prefix}")
  private String inputPrefix;

  @Value("${aws.s3.open-ai-prefix}")
  private String openAiPrefix;

  @Value("${scheduled.ingest.max-per-run:25}")
  private int maxPerRun;

  @Value("${scheduled.ingest.min-confidence:90.0}")
  private Float minConfidence;

  @Value("${scheduled.ingest.dry-run:false}")
  private boolean dryRun;

  @Scheduled(cron = "${scheduled.ingest.cron:0 * * * * *}")
  public void sweepAndProcess() {
    final String prefix = normalizePrefix(inputPrefix);
    log.info(
        "scheduler.start bucket={} prefix={} maxPerRun={} dryRun={} minConf={}",
        bucket,
        prefix,
        maxPerRun,
        dryRun,
        minConfidence);

    int processed = 0;
    String continuation = null;

    do {
      ListObjectsV2Response page =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .continuationToken(continuation)
                  .maxKeys(1000)
                  .build());

      List<S3Object> candidates = new ArrayList<>();
      for (S3Object obj : page.contents()) {
        String key = obj.key();
        if (key.endsWith("/") || obj.size() <= 0) {
          continue;
        }
        candidates.add(obj);
      }

      for (S3Object obj : candidates) {
        if (processed >= maxPerRun) {
          log.info("scheduler.limit reached maxPerRun={}, stopping this cycle", maxPerRun);
          break;
        }

        final String key = obj.key();
        final String docId = bucket + "/" + key;
        DocProcessStateItem state = stateRepo.get(docId);

        if (state != null && "COMPLETED".equalsIgnoreCase(nullToEmpty(state.getOverallStatus()))) {
          log.debug("scheduler.skip completed docId={}", docId);
          continue;
        }

        if (state != null && state.getCreatedAt() != null) {
          log.debug("scheduler.skip already tracked docId={}", docId);
          continue;
        }

        String corrId = UUID.randomUUID().toString();
        status.initRun(docId, corrId);
        status.recordPipelineStarted(docId);

        if (dryRun) {
          log.info("scheduler.dryRun would process docId={} key={}", docId, key);
          status.saveFileStatus(docId, "PENDING", null);
          processed++;
          continue;
        }

        try {
          String outputKey = deriveOpenAiOutputKey(key);
          log.info("scheduler.process docId={} key={} -> outputKey={}", docId, key, outputKey);

          // Run pipeline -> returns business JSON (not used here, since we persist to S3 inside
          // pipeline)
          Map<String, Object> businessJson =
              pipeline.processFromS3(bucket, key, outputKey, minConfidence);

          // record success: store the outputKey
          status.recordPipelineSucceeded(docId, outputKey, "s3://" + bucket + "/" + outputKey);
          processed++;

        } catch (RuntimeException ex) {
          log.error("scheduler.error docId={} key={} msg={}", docId, key, ex.getMessage(), ex);
          status.recordPipelineFailed(docId, ex.getMessage());
          processed++;
        }
      }

      if (processed >= maxPerRun) {
        break;
      }

      continuation = page.nextContinuationToken();

    } while (continuation != null);

    log.info("scheduler.finish processed={} bucket={} prefix={}", processed, bucket, prefix);
  }

  // ---------- helpers ----------

  private static String normalizePrefix(String p) {
    if (p == null || p.isBlank()) return "";
    return p.endsWith("/") ? p : p + "/";
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private String deriveOpenAiOutputKey(String inputKey) {
    String base = inputKey;
    int slash = base.lastIndexOf('/');
    if (slash >= 0 && slash + 1 < base.length()) {
      base = base.substring(slash + 1);
    }
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      base = base.substring(0, dot);
    }
    String prefix = normalizePrefix(openAiPrefix);
    return prefix + base + "-" + Instant.now().toEpochMilli() + ".json";
  }
}
