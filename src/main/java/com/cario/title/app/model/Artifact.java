package com.cario.title.app.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an artifact produced during document processing.
 *
 * <p>An artifact is typically an output file or asset generated during one of the processing
 * phases, such as JSON results from TEXTRACT, a CSV export, or a summary text file.
 *
 * <p>Examples of fields:
 *
 * <ul>
 *   <li>{@code key} — Full S3 URI or storage key of the artifact.
 *   <li>{@code type} — Logical artifact type (e.g., {@code "JSON"}, {@code "CSV"}, {@code
 *       "summary"}).
 *   <li>{@code contentType} — MIME type (e.g., {@code "application/json"}).
 *   <li>{@code sizeBytes} — File size in bytes.
 *   <li>{@code checksum} — Optional checksum for integrity verification.
 *   <li>{@code createdAt} — Creation time stamp.
 *   <li>{@code meta} — Optional metadata map with custom attributes.
 * </ul>
 *
 * <p>Defensive copies are made for {@link Instant} and {@link Map} fields to prevent exposing
 * internal mutable state.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Artifact {

  /** Full storage key or S3 URI for the artifact (e.g., {@code s3://bucket/file.json}). */
  private String key;

  /** Logical artifact type (e.g., {@code "JSON"}, {@code "CSV"}). */
  private String type;

  /** MIME type for the artifact content. */
  private String contentType;

  /** Size of the artifact in bytes. */
  private Long sizeBytes;

  /** Optional checksum for verifying artifact integrity. */
  private String checksum;

  /** Artifact version number (if version applies). */
  private Integer version;

  /** Time stamp when the artifact was created. */
  private Instant createdAt;

  /** Optional metadata for the artifact (e.g., processing parameters, tags). */
  private Map<String, String> meta;

  /**
   * Returns a defensive copy of the creation time stamp.
   *
   * @return copy of {@link #createdAt} or {@code null} if not set
   */
  public Instant getCreatedAt() {
    return createdAt == null ? null : Instant.from(createdAt);
  }

  /**
   * Sets the creation time stamp with a defensive copy.
   *
   * @param createdAt the creation time, may be {@code null}
   */
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt == null ? null : Instant.from(createdAt);
  }

  /**
   * Returns a defensive copy of the metadata map.
   *
   * @return unmodifiable copy of metadata or {@code null} if not set
   */
  public Map<String, String> getMeta() {
    return meta == null ? null : Map.copyOf(meta);
  }

  /**
   * Sets the metadata map, making a defensive copy.
   *
   * @param meta metadata map to set, may be {@code null}
   */
  public void setMeta(Map<String, String> meta) {
    this.meta = meta == null ? null : new HashMap<>(meta);
  }
}
