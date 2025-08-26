package com.cario.title.app.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Utilities to delete S3 objects referenced by presigned GET URLs. */
public final class S3SignedUrlCleanupUtils {
  private static final Logger log = LoggerFactory.getLogger(S3SignedUrlCleanupUtils.class);

  // e.g. my-bucket.s3.amazonaws.com or my-bucket.s3.us-east-1.amazonaws.com
  private static final Pattern VHOST =
      Pattern.compile("^([^.]+)\\.(?:s3|s3-accelerate)(?:[.-][a-z0-9-]+)?\\.amazonaws\\.com$");
  // e.g. s3.amazonaws.com or s3.us-east-1.amazonaws.com
  private static final Pattern PATH =
      Pattern.compile("^(?:s3|s3-accelerate)(?:[.-][a-z0-9-]+)?\\.amazonaws\\.com$");

  private S3SignedUrlCleanupUtils() {}

  /**
   * Delete all S3 objects referenced by the given (presigned) URLs. Groups by bucket and deletes in
   * batches of 1000 keys (S3 limit).
   *
   * @param s3Client configured S3 client
   * @param signedUrls list of presigned GET URLs
   */
  public static void deleteObjectsFromSignedUrls(S3Client s3Client, List<String> signedUrls) {
    if (s3Client == null) throw new IllegalArgumentException("s3Client is null");
    if (signedUrls == null || signedUrls.isEmpty()) return;

    // bucket -> distinct keys
    Map<String, LinkedHashSet<String>> byBucket = new HashMap<>();
    for (String url : signedUrls) {
      parseBucketKey(url)
          .ifPresent(
              bk -> byBucket.computeIfAbsent(bk.bucket, __ -> new LinkedHashSet<>()).add(bk.key));
    }

    for (Map.Entry<String, LinkedHashSet<String>> e : byBucket.entrySet()) {
      String bucket = e.getKey();
      List<String> keys = new ArrayList<>(e.getValue());
      batchDelete(s3Client, bucket, keys);
    }
  }

  /** Convenience: delete a single presigned URL target. */
  public static void deleteObjectFromSignedUrl(S3Client s3Client, String signedUrl) {
    parseBucketKey(signedUrl)
        .ifPresent(
            bk -> {
              try {
                s3Client.deleteObject(b -> b.bucket(bk.bucket).key(bk.key));
                log.info("Deleted s3://{}/{}", bk.bucket, bk.key);
              } catch (S3Exception ex) {
                log.warn(
                    "Failed to delete s3://{}/{}: {} {}",
                    bk.bucket,
                    bk.key,
                    ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorCode() : "S3Exception",
                    ex.awsErrorDetails() != null
                        ? ex.awsErrorDetails().errorMessage()
                        : ex.getMessage());
                throw ex;
              }
            });
  }

  /* ---------------------- internal helpers ---------------------- */

  private static void batchDelete(S3Client s3Client, String bucket, List<String> keys) {
    if (keys.isEmpty()) return;
    for (int i = 0; i < keys.size(); i += 1000) {
      int end = Math.min(i + 1000, keys.size());
      List<ObjectIdentifier> objs = new ArrayList<>(end - i);
      for (String key : keys.subList(i, end)) {
        objs.add(ObjectIdentifier.builder().key(key).build());
      }

      DeleteObjectsRequest req =
          DeleteObjectsRequest.builder()
              .bucket(bucket)
              .delete(Delete.builder().objects(objs).build())
              .build();

      try {
        DeleteObjectsResponse resp = s3Client.deleteObjects(req);
        int deleted = resp.deleted() != null ? resp.deleted().size() : 0;
        int errors = resp.errors() != null ? resp.errors().size() : 0;
        log.info(
            "Deleted {} objects ({} errors) from bucket {} ({}..{})",
            deleted,
            errors,
            bucket,
            i,
            end - 1);
        if (errors > 0) {
          for (S3Error e : resp.errors()) {
            log.warn("Delete error s3://{}/{} -> {} {}", bucket, e.key(), e.code(), e.message());
          }
        }
      } catch (S3Exception ex) {
        log.error(
            "Batch delete failed for s3://{} [{}..{}]: {}",
            bucket,
            i,
            end - 1,
            ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorMessage() : ex.getMessage());
        throw ex;
      }
    }
  }

  /** Parse bucket/key from an S3-style URL. Returns empty for non-S3 hosts. */
  private static Optional<BucketKey> parseBucketKey(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      String rawPath = uri.getRawPath(); // keeps %XX
      String path = rawPath == null ? "" : URLDecoder.decode(rawPath, StandardCharsets.UTF_8);

      if (host == null) return Optional.empty();

      // virtual-hosted
      var vhostMatcher = VHOST.matcher(host);
      if (vhostMatcher.matches()) {
        String bucket = vhostMatcher.group(1); // captured bucket name
        String key = stripLeadingSlash(path);
        if (!bucket.isEmpty() && !key.isEmpty()) {
          return Optional.of(new BucketKey(bucket, key));
        }
        return Optional.empty();
      }

      // path-style
      if (PATH.matcher(host).matches()) {
        String p = stripLeadingSlash(path);
        int slash = p.indexOf('/');
        if (slash > 0 && slash < p.length() - 1) {
          String bucket = p.substring(0, slash);
          String key = p.substring(slash + 1);
          return Optional.of(new BucketKey(bucket, key));
        }
        return Optional.empty();
      }

      // Not a standard S3 endpoint (could be CloudFront/custom domain)
      return Optional.empty();

    } catch (Exception ex) {
      log.warn("Could not parse S3 URL: {}", url, ex);
      return Optional.empty();
    }
  }

  private static String stripLeadingSlash(String s) {
    return (s != null && s.startsWith("/")) ? s.substring(1) : (s == null ? "" : s);
  }

  private record BucketKey(String bucket, String key) {}
}
