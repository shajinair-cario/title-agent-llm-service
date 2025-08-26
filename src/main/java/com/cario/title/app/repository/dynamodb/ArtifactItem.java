package com.cario.title.app.repository.dynamodb;

import java.time.Instant;
import java.util.Map;
import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/** Represents a single produced artifact (e.g., Textract JSON, normalized JSON). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class ArtifactItem {

  /** Logical artifact type (json, image, csv, pdf, txt, etc). */
  private String type;

  /** S3 key or logical identifier of the artifact within the phase. */
  private String key;

  /** Full S3 URI if available (s3://bucket/key). */
  private String s3Uri;

  /** MIME type. */
  private String contentType;

  /** Size in bytes (if known). */
  private Long size;

  /** S3 ETag (if known). */
  private String eTag;

  /** When this artifact was created. */
  private Instant createdAt;

  /** Free-form metadata (confidence, model, prompt, etc.). */
  private Map<String, String> meta;

  @DynamoDbAttribute("type")
  public String getType() {
    return type;
  }

  @DynamoDbAttribute("key")
  public String getKey() {
    return key;
  }

  @DynamoDbAttribute("s3Uri")
  public String getS3Uri() {
    return s3Uri;
  }

  @DynamoDbAttribute("contentType")
  public String getContentType() {
    return contentType;
  }

  @DynamoDbAttribute("size")
  public Long getSize() {
    return size;
  }

  @DynamoDbAttribute("eTag")
  public String getETag() {
    return eTag;
  }

  @DynamoDbAttribute("createdAt")
  public Instant getCreatedAt() {
    return createdAt;
  }

  @DynamoDbAttribute("meta")
  public Map<String, String> getMeta() {
    return meta;
  }
}
