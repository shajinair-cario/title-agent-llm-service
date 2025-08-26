package com.cario.title.app.service;

import com.cario.title.app.model.DocUploadResult;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** Service for uploading documents to S3 and returning document metadata. */
@Log4j2
public class DocUploadService {

  private final S3Client s3;

  /** Default bucket (used when callers pass null). */
  @Value("${aws.s3.bucket}")
  private String defaultBucket;

  /** Optional prefix for generated keys, e.g. "uploads/". */
  @Value("${aws.s3.input-prefix:input/}")
  private String defaultPrefix;

  public DocUploadService(S3Client s3) {
    this.s3 = Objects.requireNonNull(s3, "S3Client must not be null");
  }

  // ------------------ Public API ------------------

  /** Upload a MultipartFile to S3. Generates a key if null. */
  public DocUploadResult upload(MultipartFile file, String bucket, String key) {
    try (InputStream in = file.getInputStream()) {
      String resolvedBucket = (bucket == null || bucket.isBlank()) ? defaultBucket : bucket;
      String resolvedKey =
          (key == null || key.isBlank())
              ? generatedKey(defaultPrefix, file.getOriginalFilename())
              : key;

      String contentType =
          (file.getContentType() == null || file.getContentType().isBlank())
              ? "application/octet-stream"
              : file.getContentType();

      PutObjectRequest req =
          PutObjectRequest.builder()
              .bucket(resolvedBucket)
              .key(resolvedKey)
              .contentType(contentType)
              .contentLength(file.getSize())
              .metadata(java.util.Map.of("original-filename", safe(file.getOriginalFilename())))
              .build();

      PutObjectResponse resp = s3.putObject(req, RequestBody.fromInputStream(in, file.getSize()));

      DocUploadResult result =
          DocUploadResult.builder()
              .bucket(resolvedBucket)
              .key(resolvedKey)
              .eTag(resp.eTag())
              .contentType(contentType)
              .size(file.getSize())
              .s3Uri("s3://" + resolvedBucket + "/" + resolvedKey)
              .build();

      log.info(
          "s3.upload ok bucket={} key={} size={} eTag={}",
          result.getBucket(),
          logKey(result.getKey()),
          result.getSize(),
          result.getETag());
      return result;
    } catch (Exception e) {
      log.error("s3.upload error bucket={} key={} msg={}", bucket, key, e.getMessage(), e);
      throw new RuntimeException("Failed to upload to S3: " + e.getMessage(), e);
    }
  }

  /** Upload raw bytes to S3. Generates a key if null. */
  public DocUploadResult upload(byte[] bytes, String bucket, String key, String contentType) {
    try {
      String resolvedBucket = (bucket == null || bucket.isBlank()) ? defaultBucket : bucket;
      String resolvedKey = (key == null || key.isBlank()) ? generatedKey(defaultPrefix, null) : key;

      String ct =
          (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

      PutObjectRequest req =
          PutObjectRequest.builder()
              .bucket(resolvedBucket)
              .key(resolvedKey)
              .contentType(ct)
              .contentLength((long) bytes.length)
              .build();

      PutObjectResponse resp = s3.putObject(req, RequestBody.fromBytes(bytes));

      DocUploadResult result =
          DocUploadResult.builder()
              .bucket(resolvedBucket)
              .key(resolvedKey)
              .eTag(resp.eTag())
              .contentType(ct)
              .size(bytes.length)
              .s3Uri("s3://" + resolvedBucket + "/" + resolvedKey)
              .build();

      log.info(
          "s3.upload ok bucket={} key={} size={} eTag={}",
          result.getBucket(),
          logKey(result.getKey()),
          result.getSize(),
          result.getETag());
      return result;
    } catch (Exception e) {
      log.error("s3.upload error bucket={} key={} msg={}", bucket, key, e.getMessage(), e);
      throw new RuntimeException("Failed to upload to S3: " + e.getMessage(), e);
    }
  }

  // ------------------ Helpers ------------------

  private static String generatedKey(String prefix, String originalFilename) {
    String base = UUID.randomUUID().toString();
    String ext = (originalFilename == null) ? "" : FilenameUtils.getExtension(originalFilename);
    String suffix = (ext == null || ext.isBlank()) ? "" : ("." + ext.toLowerCase());
    String p = (prefix == null) ? "" : prefix;
    if (!p.isEmpty() && !p.endsWith("/")) p = p + "/";
    return p + base + suffix;
  }

  private static String safe(String s) {
    if (s == null) return "";
    try {
      return URLEncoder.encode(s, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return s;
    }
  }

  private static String logKey(String key) {
    if (key == null) return null;
    return key.length() > 120 ? key.substring(0, 120) + "…(truncated)" : key;
  }
}
