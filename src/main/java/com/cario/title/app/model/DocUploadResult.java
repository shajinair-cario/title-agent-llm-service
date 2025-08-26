package com.cario.title.app.model;

import lombok.Builder;
import lombok.Data;

/** Returned after a successful document upload to S3. */
@Data
@Builder
public class DocUploadResult {
  private String bucket;
  private String key;
  private String eTag; // S3 ETag from PutObjectResponse
  private String contentType; // As stored on S3
  private long size; // Bytes uploaded
  private String s3Uri; // s3://bucket/key
}
