package com.cario.title.app.api;

import com.cario.title.app.model.DocUploadResult;
import com.cario.title.app.model.TextractResult;
import com.cario.title.app.repository.dynamodb.ArtifactItem;
import com.cario.title.app.repository.dynamodb.DocProcessStateItem;
import com.cario.title.app.service.AiNlpService;
import com.cario.title.app.service.AiPipelineService;
import com.cario.title.app.service.DocUploadService;
import com.cario.title.app.service.StatusService;
import com.cario.title.app.service.TextractService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Log4j2
@Validated
@RestController
@RequestMapping("/docai")
@RequiredArgsConstructor
public class DocAiController {

  private final DocUploadService docUploadService;
  private final TextractService textractService;
  private final AiNlpService aiNlpService;
  private final AiPipelineService aiPipelineService;
  private final StatusService statusService;
  private final ObjectMapper om; // injected from Spring

  @Value("${aws.s3.bucket}")
  private String bucket;

  @Value("${aws.s3.output-prefix}")
  private String outputPrefix;

  @Value("${aws.s3.open-ai-prompt-prefix}")
  private String promptPrefix;

  @Value("${aws.s3.open-ai-prompt-file}")
  private String promptFile;

  @Value("${app.nlp.model:gpt-4o-mini}")
  private String nlpModelName;

  // ------------------------------------------------------------
  // /docai/status/{documentId}
  // ------------------------------------------------------------
  @GetMapping(path = "/status/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DocProcessStateItem> getStatus(
      @PathVariable("documentId") String documentId) {
    DocProcessStateItem state = statusService.getState(documentId);
    if (state == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(state);
  }

  // ------------------------------------------------------------
  // /docai/upload
  // ------------------------------------------------------------
  @PostMapping(
      path = "/upload",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DocUploadResult> upload(
      @RequestPart("file") @NotNull MultipartFile file,
      @RequestParam(name = "bucket", required = false) String overrideBucket,
      @RequestParam(name = "key", required = false) String key,
      @RequestParam(name = "documentId", required = false) String documentId) {

    String corrId = UUID.randomUUID().toString();
    String docId = ensureDocId(documentId, overrideBucket, key, file.getOriginalFilename());
    log.info(
        "docai.upload docId={} filename={} bucket={} key={}",
        docId,
        file.getOriginalFilename(),
        overrideBucket,
        key);

    statusService.initRun(docId, corrId);
    statusService.recordUploadStarted(docId, null);

    try {
      DocUploadResult result = docUploadService.upload(file, overrideBucket, key);

      ArtifactItem artifact =
          ArtifactItem.builder()
              .type(result.getContentType() == null ? "binary" : result.getContentType())
              .key(result.getKey())
              .s3Uri(result.getS3Uri())
              .contentType(result.getContentType())
              .createdAt(Instant.now())
              .meta(Map.of("note", "Uploaded via /docai/upload"))
              .build();

      statusService.recordUploadSucceeded(docId, result.getS3Uri(), artifact);
      return ResponseEntity.ok(result);

    } catch (RuntimeException ex) {
      statusService.recordUploadFailed(docId, null, ex.getMessage());
      throw ex;
    }
  }

  // ------------------------------------------------------------
  // /docai/extract
  // ------------------------------------------------------------
  @PostMapping(
      path = "/extract",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TextractResult> extract(
      @RequestBody @Validated ExtractRequest req,
      @RequestParam(name = "documentId", required = false) String documentId) {

    String docId = ensureDocId(documentId, req.getInputBucket(), req.getInputKey(), null);
    String inputS3Uri = s3Uri(req.getInputBucket(), req.getInputKey());
    Float threshold = req.getConfidenceThreshold();

    log.info("docai.extract docId={} input={} threshold={}", docId, inputS3Uri, threshold);
    statusService.recordTextractStarted(docId, inputS3Uri, threshold);

    try {
      TextractResult result =
          textractService.processFile(req.getInputBucket(), req.getInputKey(), threshold);

      ArtifactItem artifact =
          ArtifactItem.builder()
              .type("json")
              .key(result.getOutputKey())
              .s3Uri(result.getS3Uri())
              .contentType("application/json")
              .createdAt(Instant.now())
              .meta(Map.of("note", "Textract high-confidence JSON"))
              .build();

      statusService.recordTextractSucceeded(
          docId,
          inputS3Uri,
          result.getS3Uri(),
          result.getAverageConfidence(),
          result.getMinConfidence(),
          result.getMaxConfidence(),
          result.getBlockCount(),
          artifact);

      return ResponseEntity.ok(result);

    } catch (RuntimeException ex) {
      statusService.recordTextractFailed(docId, inputS3Uri, ex.getMessage());
      throw ex;
    }
  }

  // ------------------------------------------------------------
  // /docai/analyze
  // ------------------------------------------------------------
  @PostMapping(
      path = "/analyze",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> analyze(
      @RequestBody @Validated AnalyzeRequest req,
      @RequestParam(name = "documentId", required = false) String documentId) {

    String docId = ensureDocId(documentId, bucket, req.getTextractKey(), null);
    String textractJsonS3 = s3Uri(bucket, req.getTextractKey());
    String promptS3 = s3Uri(bucket, ensureSlash(promptPrefix) + promptFile);

    log.info(
        "docai.analyze docId={} textractKey={} outputKey={} minConfidence={}",
        docId,
        req.getTextractKey(),
        req.getOutputKey(),
        req.getMinConfidence());

    statusService.recordNlpStarted(docId, textractJsonS3, nlpModelName, promptS3);

    try {
      Map<String, Object> businessJson =
          aiNlpService.normalizeFromTextractS3(
              req.getTextractKey(), req.getOutputKey(), req.getMinConfidence());

      return ResponseEntity.ok(businessJson);

    } catch (RuntimeException ex) {
      statusService.recordNlpFailed(docId, textractJsonS3, nlpModelName, ex.getMessage());
      throw ex;
    }
  }

  // ------------------------------------------------------------
  // /docai/pipeline
  // ------------------------------------------------------------
  @PostMapping(
      path = "/pipeline",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> runPipeline(
      @RequestBody @Validated PipelineRequest req,
      @RequestParam(name = "documentId", required = false) String documentId) {

    String docId = ensureDocId(documentId, req.getInputBucket(), req.getInputKey(), null);
    String corrId = UUID.randomUUID().toString();
    log.info(
        "docai.pipeline docId={} inputBucket={} inputKey={} nlpOutputKey={} minConfidence={}",
        docId,
        req.getInputBucket(),
        req.getInputKey(),
        req.getNlpOutputKey(),
        req.getMinConfidence());

    statusService.initRun(docId, corrId);
    statusService.recordPipelineStarted(docId);

    try {
      Map<String, Object> businessJson =
          aiPipelineService.processFromS3(
              req.getInputBucket(),
              req.getInputKey(),
              req.getNlpOutputKey(),
              req.getMinConfidence());

      return ResponseEntity.ok(businessJson);

    } catch (RuntimeException ex) {
      statusService.recordPipelineFailed(docId, ex.getMessage());
      throw ex;
    }
  }

  // ============================================================
  // DTOs
  // ============================================================
  @Data
  public static class ExtractRequest {
    @NotBlank private String inputBucket;
    @NotBlank private String inputKey;

    @Min(0)
    @Max(100)
    private Float confidenceThreshold;
  }

  @Data
  public static class AnalyzeRequest {
    @NotBlank private String textractKey;
    private String outputKey;

    @Min(0)
    @Max(100)
    private Float minConfidence;
  }

  @Data
  public static class PipelineRequest {
    @NotBlank private String inputBucket;
    @NotBlank private String inputKey;
    private String nlpOutputKey;

    @Min(0)
    @Max(100)
    private Float minConfidence;
  }

  // ============================================================
  // Helpers
  // ============================================================
  private static String s3Uri(String bucket, String key) {
    if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) return null;
    return "s3://" + bucket + "/" + key;
  }

  private static String ensureDocId(String documentId, String bucket, String key, String filename) {
    if (documentId != null && !documentId.isBlank()) return documentId;
    if (bucket != null && key != null) return bucket + "/" + key;
    if (filename != null) return "upload:" + filename;
    return UUID.randomUUID().toString();
  }

  private static String ensureSlash(String prefix) {
    return prefix.endsWith("/") ? prefix : prefix + "/";
  }
}
