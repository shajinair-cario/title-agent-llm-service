package com.cario.title.app.model;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TesseractResult {

  /** Name of the S3 bucket where result is stored (optional if not saved). */
  private String outputBucket;

  /** S3 key of the result file (optional if not saved). */
  private String outputKey;

  /** Full s3:// URI for quick reference (optional if not saved). */
  private String s3Uri;

  /** OCR tokens recognized from the image. */
  private List<Token> tokens;

  /** Raw JSON string of the OCR tokens (optional if saved). */
  private String jsonOutput;

  /** Total number of tokens. */
  private int tokenCount;

  /** Average confidence of tokens (0–100). */
  private double averageConfidence;

  /** Minimum confidence among tokens. */
  private double minConfidence;

  /** Maximum confidence among tokens. */
  private double maxConfidence;

  @Data
  @Builder
  public static class Token {
    /** Recognized text content (may be empty). */
    private String text;

    /** Engine confidence (0–100). */
    private float conf;

    /** Bounding box x coordinate (pixels). */
    private int x;

    /** Bounding box y coordinate (pixels). */
    private int y;

    /** Bounding box width (pixels). */
    private int w;

    /** Bounding box height (pixels). */
    private int h;
  }
}
