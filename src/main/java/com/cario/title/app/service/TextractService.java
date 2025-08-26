package com.cario.title.app.service;

import com.cario.title.app.model.TextractResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.postgresql.util.PGobject;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

/**
 * Service that wraps AWS Textract: - Sync for images - Async for PDFs (FORMS + TABLES + QUERIES)
 */
@Log4j2
public class TextractService {

  @Value("${aws.s3.bucket}")
  private String outputBucket;

  @Value("${aws.s3.textract-prefix}")
  private String outputPrefix;

  /** Queries are injected from application.yml */
  @Value(
      "#{'${aws.textract.queries:VIN,Title Number,Certificate Type,Owner,Owner Address,First Lienholder,Odometer Reading,Sale Date}'.split(',')}")
  private List<String> configuredQueries;

  private final S3Client s3Client;
  private final TextractClient textractClient;

  private final JdbcTemplate jdbcTemplate;

  private final OpenAiEmbeddingModel embeddingModel;

  // Use Jackson instead of Gson
  private final ObjectMapper mapper =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  public TextractService(
      final S3Client s3Client,
      final TextractClient textractClient,
      final JdbcTemplate jdbcTemplate,
      OpenAiEmbeddingModel embeddingModel) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
    this.textractClient = Objects.requireNonNull(textractClient, "textractClient must not be null");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "EmbeddingModel must not be null");
  }

  /** Processes a document stored in S3 with AWS Textract. */
  public TextractResult processFile(
      String inputBucket, String inputKey, Float confidenceThreshold) {

    float threshold = (confidenceThreshold != null) ? confidenceThreshold : 90.0f;

    // Normalize input key (decode %2F, spaces, etc.)
    String normalizedKey = URLDecoder.decode(inputKey, StandardCharsets.UTF_8);

    // Preserve folder hierarchy in output key
    String outputKey = outputPrefix + normalizedKey + ".json";
    String s3Uri = "s3://" + outputBucket + "/" + outputKey;

    // --- NEW: Check if result already exists ---
    try {
      HeadObjectResponse head =
          s3Client.headObject(
              HeadObjectRequest.builder().bucket(outputBucket).key(outputKey).build());

      log.info("Skipping Textract: result already exists at {}", s3Uri);

      String existingJson =
          s3Client
              .getObjectAsBytes(
                  GetObjectRequest.builder().bucket(outputBucket).key(outputKey).build())
              .asUtf8String();

      return TextractResult.builder()
          .outputBucket(outputBucket)
          .outputKey(outputKey)
          .s3Uri(s3Uri)
          .jsonOutput(existingJson)
          .blockCount(0) // You could parse block count if needed
          .averageConfidence(0.0)
          .minConfidence(0.0)
          .maxConfidence(0.0)
          .confidenceScores(Collections.emptyList())
          .build();

    } catch (Exception e) {
      log.info("Textract result not found in {}, proceeding with new analysis", s3Uri);
    }

    log.info(
        "Invoking AWS Textract for s3://{}/{} with threshold {}",
        inputBucket,
        normalizedKey,
        threshold);

    Document document =
        Document.builder()
            .s3Object(
                software.amazon.awssdk.services.textract.model.S3Object.builder()
                    .bucket(inputBucket)
                    .name(normalizedKey)
                    .build())
            .build();

    List<Block> blocks;
    if (normalizedKey.toLowerCase().endsWith(".pdf")) {
      log.info("Detected PDF, using async StartDocumentAnalysis API with FORMS+TABLES+QUERIES");
      blocks = processAsync(document, threshold);
    } else {
      // Sync for images
      AnalyzeDocumentResponse response =
          textractClient.analyzeDocument(
              AnalyzeDocumentRequest.builder()
                  .document(document)
                  .featureTypes(FeatureType.FORMS, FeatureType.TABLES)
                  .build());

      blocks =
          response.blocks().stream()
              .filter(b -> b.confidence() != null && b.confidence() >= threshold)
              .toList();
    }

    if (blocks.isEmpty()) {
      log.warn("No high-confidence blocks found for s3://{}/{}", inputBucket, normalizedKey);
    }

    // Convert blocks into serializable maps
    List<Map<String, Object>> serializableBlocks =
        blocks.stream().map(this::flattenBlock).collect(Collectors.toList());

    List<TextractToken> vectorTokens = extractTokensWithMetadata(serializableBlocks);

    // tokenize and store in db
    try {
      indexTextractBlocks(normalizedKey, serializableBlocks);
    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    String json;
    try {
      json = mapper.writeValueAsString(serializableBlocks);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize Textract blocks", e);
    }

    // Save JSON to S3
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(outputBucket)
            .key(outputKey)
            .contentType("application/json")
            .build(),
        RequestBody.fromString(json));

    log.info("Textract result written to {}", s3Uri);

    // Build confidence stats
    List<Float> confidences = blocks.stream().map(Block::confidence).toList();
    double avgConf = confidences.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
    double minConf = confidences.stream().mapToDouble(Float::doubleValue).min().orElse(0.0);
    double maxConf = confidences.stream().mapToDouble(Float::doubleValue).max().orElse(0.0);

    return TextractResult.builder()
        .outputBucket(outputBucket)
        .outputKey(outputKey)
        .s3Uri(s3Uri)
        .jsonOutput(json)
        .blockCount(confidences.size())
        .averageConfidence(avgConf)
        .minConfidence(minConf)
        .maxConfidence(maxConf)
        .confidenceScores(confidences)
        .build();
  }

  /** Flatten Block into serializable map */
  private Map<String, Object> flattenBlock(Block b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("BlockType", b.blockTypeAsString());
    m.put("Confidence", b.confidence());
    m.put("Text", b.text());
    m.put("Id", b.id());
    m.put("Page", b.page());

    // Geometry
    if (b.geometry() != null) {
      Map<String, Object> geomMap = new LinkedHashMap<>();
      if (b.geometry().boundingBox() != null) {
        BoundingBox bb = b.geometry().boundingBox();
        Map<String, Float> bbMap = new LinkedHashMap<>();
        bbMap.put("Width", bb.width());
        bbMap.put("Height", bb.height());
        bbMap.put("Left", bb.left());
        bbMap.put("Top", bb.top());
        geomMap.put("BoundingBox", bbMap);
      }
      if (b.geometry().polygon() != null) {
        List<Map<String, Float>> points =
            b.geometry().polygon().stream().map(p -> Map.of("X", p.x(), "Y", p.y())).toList();
        geomMap.put("Polygon", points);
      }
      m.put("Geometry", geomMap);
    }

    // Relationships
    if (b.relationships() != null && !b.relationships().isEmpty()) {
      List<Map<String, Object>> rels =
          b.relationships().stream()
              .map(
                  r -> {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("Type", r.typeAsString());
                    rm.put("Ids", r.ids());
                    return rm;
                  })
              .toList();
      m.put("Relationships", rels);
    }

    // EntityTypes
    if (b.entityTypes() != null && !b.entityTypes().isEmpty()) {
      m.put("EntityTypes", b.entityTypes());
    }

    // Selection status (checkboxes, etc.)
    if (b.selectionStatusAsString() != null) {
      m.put("SelectionStatus", b.selectionStatusAsString());
    }

    return m;
  }

  /** Async Textract flow for PDFs with FORMS, TABLES, and QUERIES. */
  private List<Block> processAsync(Document document, float threshold) {
    // Build queries config
    List<Query> queries =
        configuredQueries.stream().map(q -> Query.builder().text(q.trim()).build()).toList();

    StartDocumentAnalysisResponse startResponse =
        textractClient.startDocumentAnalysis(
            StartDocumentAnalysisRequest.builder()
                .documentLocation(DocumentLocation.builder().s3Object(document.s3Object()).build())
                .featureTypes(FeatureType.FORMS, FeatureType.TABLES, FeatureType.QUERIES)
                .queriesConfig(QueriesConfig.builder().queries(queries).build())
                .build());

    String jobId = startResponse.jobId();
    log.info("Started async Textract jobId={} with {} queries", jobId, queries.size());

    GetDocumentAnalysisResponse result;

    // Poll until job finishes
    while (true) {
      try {
        Thread.sleep(Duration.ofSeconds(5).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Polling interrupted", e);
      }

      result =
          textractClient.getDocumentAnalysis(
              GetDocumentAnalysisRequest.builder().jobId(jobId).build());
      String status = result.jobStatusAsString();
      log.debug("Polling jobId={} status={}", jobId, status);

      if ("SUCCEEDED".equals(status)) {
        break;
      } else if ("FAILED".equals(status)) {
        throw new RuntimeException("Textract async job failed: jobId=" + jobId);
      }
    }

    // Collect blocks across all pages
    List<Block> blocks = new ArrayList<>();
    blocks.addAll(
        result.blocks().stream()
            .filter(b -> b.confidence() != null && b.confidence() >= threshold)
            .toList());

    String nextToken = result.nextToken();
    while (nextToken != null) {
      result =
          textractClient.getDocumentAnalysis(
              GetDocumentAnalysisRequest.builder().jobId(jobId).nextToken(nextToken).build());
      blocks.addAll(
          result.blocks().stream()
              .filter(b -> b.confidence() != null && b.confidence() >= threshold)
              .toList());
      nextToken = result.nextToken();
    }

    return blocks;
  }

  private static class TextractToken {
    public String id;
    public String text;
    public String type;
    public int page;
    public double confidence;
    public Map<String, Object> metadata = new HashMap<>();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private List<TextractToken> extractTokensWithMetadata(List<Map<String, Object>> blocks) {
    // ---------- index blocks by Id and relationships ----------
    Map<String, Map<String, Object>> byId = new HashMap<>();
    for (Map<String, Object> b : blocks) {
      byId.put(Objects.toString(b.get("Id"), ""), b);
    }

    // map: parentId -> ordered childIds (as they appear)
    Map<String, List<String>> children = new HashMap<>();
    for (Map<String, Object> b : blocks) {
      List<Map<String, Object>> rels = (List<Map<String, Object>>) b.get("Relationships");
      if (rels == null) continue;
      String id = Objects.toString(b.get("Id"), "");
      for (Map<String, Object> r : rels) {
        String rt = Objects.toString(r.get("Type"), "");
        if (!"CHILD".equals(rt)) continue;
        List<Object> ids = (List<Object>) r.get("Ids");
        if (ids == null) continue;
        for (Object o : ids) {
          String childId = Objects.toString(o, "");
          children.computeIfAbsent(id, k -> new ArrayList<>()).add(childId);
        }
      }
    }

    // ---------- helpers ----------
    java.util.function.Function<Map<String, Object>, String> textFromWordChildren =
        (blk) -> {
          String id = Objects.toString(blk.get("Id"), "");
          List<String> kids = children.getOrDefault(id, List.of());
          StringBuilder sb = new StringBuilder();
          for (String kid : kids) {
            Map<String, Object> child = byId.get(kid);
            if (child == null) continue;
            String bt = Objects.toString(child.get("BlockType"), "");
            if ("WORD".equals(bt)) {
              String w = Objects.toString(child.get("Text"), "");
              if (!w.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(w);
              }
            } else if ("SELECTION_ELEMENT".equals(bt)) {
              String sel = Objects.toString(child.get("SelectionStatus"), "");
              if (!sel.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append("Checkbox:").append(sel);
              }
            }
          }
          return sb.toString().trim().replaceAll("\\s+", " ");
        };

    java.util.function.Function<Map<String, Object>, Double> avgChildWordConfidence =
        (blk) -> {
          String id = Objects.toString(blk.get("Id"), "");
          List<String> kids = children.getOrDefault(id, List.of());
          double sum = 0.0;
          int n = 0;
          for (String kid : kids) {
            Map<String, Object> child = byId.get(kid);
            if (child == null) continue;
            if ("WORD".equals(Objects.toString(child.get("BlockType"), ""))) {
              Number c = (Number) child.get("Confidence");
              if (c != null) {
                sum += c.doubleValue();
                n++;
              }
            }
          }
          if (n == 0) {
            Number c = (Number) blk.get("Confidence");
            return c == null ? 0.0 : c.doubleValue();
          }
          return sum / n;
        };

    // tableId -> list of its cells
    Map<String, List<Map<String, Object>>> tableCells = new HashMap<>();
    for (Map<String, Object> b : blocks) {
      String type = Objects.toString(b.get("BlockType"), "");
      if ("CELL".equals(type) || "MERGED_CELL".equals(type)) {
        String parentId = Objects.toString(b.get("ParentId"), "");
        tableCells.computeIfAbsent(parentId, k -> new ArrayList<>()).add(b);
      }
    }

    List<TextractToken> tokens = new ArrayList<>();

    for (Map<String, Object> b : blocks) {
      String type = Objects.toString(b.get("BlockType"), "");
      String id = Objects.toString(b.get("Id"), UUID.randomUUID().toString());
      int page = ((Number) b.getOrDefault("Page", 0)).intValue();

      // normalize entityTypes safely
      List<String> entityTypes = new ArrayList<>();
      Object entityTypesObj = b.get("EntityTypes");
      if (entityTypesObj instanceof List<?>) {
        for (Object e : (List<?>) entityTypesObj) {
          entityTypes.add(String.valueOf(e));
        }
      }

      // ---- 1) LINE ----
      if ("LINE".equals(type)) {
        String text = textFromWordChildren.apply(b);
        if (text.isBlank() || text.length() < 3) continue;

        TextractToken t = new TextractToken();
        t.id = id;
        t.type = "LINE";
        t.page = page;
        t.text = text;
        t.confidence = avgChildWordConfidence.apply(b);
        t.metadata.put("bbox", b.get("Geometry"));
        t.metadata.put("entityTypes", entityTypes);
        t.metadata.put("relationships", b.get("Relationships"));
        tokens.add(t);
        continue;
      }

      // ---- 2) KEY_VALUE_SET VALUE ----
      if ("KEY_VALUE_SET".equals(type)) {
        boolean isValue = entityTypes.stream().anyMatch(et -> "VALUE".equalsIgnoreCase(et));
        if (!isValue) continue;

        String text = textFromWordChildren.apply(b);
        if (text.isBlank()) continue;

        TextractToken t = new TextractToken();
        t.id = id;
        t.type = "KV_VALUE";
        t.page = page;
        t.text = text;
        t.confidence = avgChildWordConfidence.apply(b);
        t.metadata.put("bbox", b.get("Geometry"));
        t.metadata.put("entityTypes", entityTypes);
        t.metadata.put("relationships", b.get("Relationships"));
        tokens.add(t);
        continue;
      }

      // ---- 3) CELL ----
      if ("CELL".equals(type) || "MERGED_CELL".equals(type)) {
        String text = textFromWordChildren.apply(b);
        if (text.isBlank()) continue;

        TextractToken t = new TextractToken();
        t.id = id;
        t.type = "CELL";
        t.page = page;
        t.text = text;
        t.confidence = avgChildWordConfidence.apply(b);
        t.metadata.put("bbox", b.get("Geometry"));
        t.metadata.put("rowIndex", b.get("RowIndex"));
        t.metadata.put("columnIndex", b.get("ColumnIndex"));
        t.metadata.put("rowSpan", b.get("RowSpan"));
        t.metadata.put("columnSpan", b.get("ColumnSpan"));
        t.metadata.put("parentTableId", b.get("ParentId"));
        tokens.add(t);
        continue;
      }

      // ---- 4) TABLE ----
      if ("TABLE".equals(type)) {
        List<Map<String, Object>> cells = tableCells.getOrDefault(id, List.of());
        if (!cells.isEmpty()) {
          Map<Integer, List<Map<String, Object>>> rows = new TreeMap<>();
          for (Map<String, Object> cell : cells) {
            int row = ((Number) cell.getOrDefault("RowIndex", 0)).intValue();
            rows.computeIfAbsent(row, k -> new ArrayList<>()).add(cell);
          }
          StringBuilder sb = new StringBuilder();
          for (var e : rows.entrySet()) {
            e.getValue()
                .sort(Comparator.comparingInt(c -> ((Number) c.get("ColumnIndex")).intValue()));
            if (sb.length() > 0) sb.append("\n");
            List<String> cols = new ArrayList<>();
            for (Map<String, Object> cell : e.getValue()) {
              cols.add(textFromWordChildren.apply(cell));
            }
            sb.append(String.join(" | ", cols));
          }
          String text = sb.toString().trim().replaceAll("\\s+", " ");
          if (!text.isBlank()) {
            TextractToken t = new TextractToken();
            t.id = id;
            t.type = "TABLE";
            t.page = page;
            t.text = text;
            t.confidence = ((Number) b.getOrDefault("Confidence", 0)).doubleValue();
            t.metadata.put("bbox", b.get("Geometry"));
            t.metadata.put("cellCount", cells.size());
            tokens.add(t);
          }
        }
        continue;
      }
    }

    return tokens;
  }

  private void indexTextractBlocks(String docId, List<Map<String, Object>> blocks)
      throws Exception {

    // --- Check if rows already exist for this docId ---
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM textract_index WHERE doc_id = ?", Integer.class, docId);

    if (count != null && count > 0) {
      log.info("Skipping indexing: docId={} already has {} rows in textract_index", docId, count);
      return; // skip re-index
    }

    List<TextractToken> tokens = extractTokensWithMetadata(blocks);

    for (TextractToken t : tokens) {

      // Generate embedding via Spring AI
      float[] response = embeddingModel.embed(t.text);

      // Wrap in PGvector (pgvector-jdbc dependency required)
      PGvector pgVector = new PGvector(response);

      // Convert metadata -> JSONB
      PGobject jsonbObject = new PGobject();
      jsonbObject.setType("jsonb");
      jsonbObject.setValue(mapper.writeValueAsString(t.metadata));

      jdbcTemplate.update(
          "INSERT INTO textract_index (id, doc_id, text, vector, page, type, confidence, metadata) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          ps -> {
            ps.setString(1, t.id);
            ps.setString(2, docId);
            ps.setString(3, t.text);
            ps.setObject(4, pgVector); // proper pgvector
            ps.setInt(5, t.page);
            ps.setString(6, t.type);
            ps.setDouble(7, t.confidence);
            ps.setObject(8, jsonbObject); // proper jsonb
          });
    }
  }
}
