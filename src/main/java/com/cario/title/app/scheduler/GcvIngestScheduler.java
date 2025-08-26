package com.cario.title.app.scheduler;

import com.cario.title.app.service.VisionOcrService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Log4j2
@RequiredArgsConstructor
public class GcvIngestScheduler {

  private final S3Client s3;
  private final VisionOcrService vision;

  @Value("${aws.s3.bucket}")
  private String bucket;

  @Value("${aws.s3.input-prefix}")
  private String inputPrefix;

  @Value("${aws.s3.gcv-prefix:gcv/}")
  private String gcvPrefix;

  @Value("${gcv.max-per-run:20}")
  private int maxPerRun;

  @Value("${gcv.feature:DOCUMENT_TEXT_DETECTION}")
  private String feature;

  @Scheduled(cron = "${gcv.cron:0 */5 * * * *}")
  public void sweepAndOcr() {
    String prefix = normalize(inputPrefix);
    boolean docMode = "DOCUMENT_TEXT_DETECTION".equalsIgnoreCase(feature);

    log.info(
        "gcv.start bucket={} prefix={} maxPerRun={} feature={}",
        bucket,
        prefix,
        maxPerRun,
        feature);

    int processed = 0;
    String token = null;

    do {
      ListObjectsV2Response page =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .continuationToken(token)
                  .maxKeys(1000)
                  .build());

      List<S3Object> objects = new ArrayList<>();
      for (S3Object o : page.contents()) {
        if (o.key().endsWith("/") || o.size() <= 0) continue;
        objects.add(o);
      }

      for (S3Object obj : objects) {
        if (processed >= maxPerRun) break;

        String key = obj.key();
        String outKey = deriveOutKey(key);

        // skip if output already exists
        if (exists(bucket, outKey)) {
          log.debug("gcv.skip exists {}", outKey);
          continue;
        }

        try {
          byte[] bytes =
              s3.getObject(
                      GetObjectRequest.builder().bucket(bucket).key(key).build(),
                      ResponseTransformer.toBytes())
                  .asByteArray();

          String json = vision.ocrBytes(bytes, docMode);

          put(bucket, outKey, json, "application/json");
          log.info("gcv.ok input={} output={} size={}", key, outKey, json.length());
          processed++;

        } catch (Exception e) {
          log.error("gcv.err key={} msg={}", key, e.getMessage(), e);
          processed++;
        }
      }

      if (processed >= maxPerRun) break;
      token = page.nextContinuationToken();
    } while (token != null);

    log.info("gcv.finish processed={} bucket={} prefix={}", processed, bucket, prefix);
  }

  private boolean exists(String b, String k) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(b).key(k).build());
      return true;
    } catch (S3Exception ex) {
      return false;
    }
  }

  private void put(String b, String k, String content, String ct) {
    s3.putObject(
        PutObjectRequest.builder().bucket(b).key(k).contentType(ct).build(),
        software.amazon.awssdk.core.sync.RequestBody.fromString(content, StandardCharsets.UTF_8));
  }

  private String deriveOutKey(String inKey) {
    String base = inKey;
    int slash = base.lastIndexOf('/');
    if (slash >= 0 && slash + 1 < base.length()) base = base.substring(slash + 1);
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    String p = normalize(gcvPrefix);
    return p + base + "-" + Instant.now().toEpochMilli() + ".json";
  }

  private static String normalize(String p) {
    if (p == null || p.isBlank()) return "";
    return p.endsWith("/") ? p : p + "/";
  }
}
