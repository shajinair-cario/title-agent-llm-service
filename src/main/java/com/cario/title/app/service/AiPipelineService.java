package com.cario.title.app.service;

import com.cario.title.app.config.ServiceConfig.PerplexityServiceProperties;
import com.cario.title.app.model.TextractResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

/**
 * AiPipelineService
 *
 * <ol>
 *   <li>Invoke AWS Textract on an S3 object via {@link TextractService#processFile} (saves filtered
 *       blocks JSON to S3).
 *   <li>Hand the Textract JSON S3 key to {@link AiNlpService#normalizeFromTextractS3} to produce
 *       business JSON.
 *   <li>Return the complete normalized business JSON (optionally saved by AiNlpService with a
 *       provided output key).
 * </ol>
 *
 * Fully S3-based: no local OCR or template extraction.
 */
@Log4j2
public class AiPipelineService {

  private final TextractService textractService;
  private final AiNlpService aiNlpService;
  private final PerplexityExtractService perplexityService;
  private final PerplexityServiceProperties perplexityProps;

  private final S3Client s3Client;

  /** Default LINE-confidence threshold (0..100) if the caller doesn’t provide one. */
  @Value("${app.textract.min-confidence:90.0}")
  private float defaultMinConfidence;

  @Value("${perplexity.service.enabled}")
  private boolean perplexityServiceEnabled;

  @Value("${perplexity.s3.bucket}")
  private String pBucket;

  // @Value("#{'${perplexity.service.fields}'.split(',')}")
  // private List<String> perplexityFields;

  public AiPipelineService(
      TextractService textractService,
      AiNlpService aiNlpService,
      PerplexityExtractService perplexityExtractService,
      S3Client s3Client,
      PerplexityServiceProperties perplexityProps) {
    this.textractService = Objects.requireNonNull(textractService);
    this.aiNlpService = Objects.requireNonNull(aiNlpService);
    this.perplexityService = perplexityExtractService;
    this.s3Client = s3Client;
    this.perplexityProps = perplexityProps;
  }

  /** Convenience overload: default confidence and no normalized JSON save. */
  public Map<String, Object> processFromS3(String inputBucket, String inputKey) {
    return processFromS3(inputBucket, inputKey, null, null);
  }

  /**
   * End-to-end pipeline using S3 as the IO layer, returning the normalized business JSON.
   *
   * @param inputBucket S3 bucket containing the input file (PDF/Image)
   * @param inputKey S3 key to the input file
   * @param nlpOutputKey Optional S3 key (in AiNlpService-configured output bucket) to store the
   *     normalized JSON
   * @param minConfidence Optional minimum confidence (0..100) for including Textract LINE blocks;
   *     if null, uses {@code app.textract.min-confidence}
   * @return Normalized business JSON produced by {@link AiNlpService}
   */
  public Map<String, Object> processFromS3(
      String inputBucket, String inputKey, String nlpOutputKey, Float minConfidence) {

    String reqId = UUID.randomUUID().toString();
    long t0 = System.nanoTime();
    String safeS3 = safeS3Uri(inputBucket, inputKey);

    log.info("aipipeline.start id={} s3={}", reqId, safeS3);

    try {

      // If perplexity service enabled
      if (perplexityServiceEnabled) {
        String safeKey = safeS3Key(inputKey);

        log.info(
            "Copying file from s3://{}/{} to s3://{}/{}", inputBucket, inputKey, pBucket, safeKey);

        var response =
            s3Client.copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(inputBucket)
                    .sourceKey(inputKey)
                    .destinationBucket(pBucket)
                    .destinationKey(safeKey)
                    .build());

        log.info("CopyObject completed: {}", response.copyObjectResult());

        // 1) Call Perplexity Extract Service
        Map<String, Object> result =
            perplexityService.extractFromS3(safeKey, perplexityProps.getFields());

        log.info("Perplexity extraction complete for s3://{}/{}", pBucket, safeKey);
        s3Client.deleteObject(b -> b.bucket(pBucket).key(safeKey));
        log.info("Deleted temporary file s3://{}/{}", pBucket, safeKey);
        return result;
      }

      // 1) Run Textract and persist filtered blocks JSON to S3
      float threshold = (minConfidence == null ? defaultMinConfidence : minConfidence);
      TextractResult texResult = textractService.processFile(inputBucket, inputKey, threshold);

      log.info(
          "aipipeline.textract id={} input={} resultKey={} blockCount={} avgConf={}",
          reqId,
          safeS3,
          texResult.getOutputKey(),
          texResult.getBlockCount(),
          String.format("%.2f", texResult.getAverageConfidence()));

      // 2) Normalize via AI using the Textract JSON key
      Map<String, Object> businessJson =
          aiNlpService.normalizeFromTextractS3(
              texResult.getOutputKey(), // Textract JSON key produced by TextractService
              nlpOutputKey, // optional normalized JSON save location
              threshold); // same threshold for collapsing LINE text

      long ms = (System.nanoTime() - t0) / 1_000_000;
      log.info(
          "aipipeline.success id={} s3={} durationMs={} businessSize={}",
          reqId,
          safeS3,
          ms,
          businessJson.size());

      return businessJson;

    } catch (RuntimeException e) {
      long ms = (System.nanoTime() - t0) / 1_000_000;
      log.error(
          "aipipeline.error id={} s3={} durationMs={} msg={}",
          reqId,
          safeS3,
          ms,
          e.getMessage(),
          e);
      throw e;
    }
  }

  private static String safeS3Uri(String bucket, String key) {
    try {
      return "s3://" + bucket + "/" + URLEncoder.encode(key, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "s3://" + bucket + "/" + key;
    }
  }

  private String safeS3Key(String key) {
    // normalize key (strip unsafe chars, keep hierarchy)
    return key.replace(" ", "_");
  }
}
