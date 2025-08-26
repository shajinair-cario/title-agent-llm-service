package com.cario.title.app.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the combined output of a Textract processing operation. Includes:
 *
 * <ul>
 *   <li>S3 location where results are stored
 *   <li>Raw JSON output (as stored in S3)
 *   <li>Confidence statistics for high-confidence blocks
 *   <li>Count of extracted blocks above the threshold
 * </ul>
 *
 * @author
 * @version 1.0
 * @since 2025-08-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextractResult {

  /** Name of the S3 bucket where results are stored. */
  private String outputBucket;

  /** Key (path) in S3 where the results are stored. */
  private String outputKey;

  /** Combined S3 URI (s3://bucket/key). */
  private String s3Uri;

  /** The JSON string as stored in S3 (filtered high-confidence blocks). */
  private String jsonOutput;

  /** Number of high-confidence blocks extracted. */
  private int blockCount;

  /** Average confidence score of high-confidence blocks. */
  private double averageConfidence;

  /** Minimum confidence score among high-confidence blocks. */
  private double minConfidence;

  /** Maximum confidence score among high-confidence blocks. */
  private double maxConfidence;

  /** List of confidence scores for each high-confidence block (optional). */
  private List<Float> confidenceScores;
}
