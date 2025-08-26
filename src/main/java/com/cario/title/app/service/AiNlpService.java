package com.cario.title.app.service;

import com.cario.title.app.model.NlpOutput;
import com.cario.title.app.prompt.PromptConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.pgvector.PGvector;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Log4j2
public class AiNlpService {

  private final ChatClient chat;
  private final S3Client s3;
  private final PromptLoaderService promptLoader;
  private final ObjectMapper om = new ObjectMapper();
  private final JdbcTemplate jdbcTemplate;
  private final OpenAiEmbeddingModel embeddingModel;

  String userTask =
      """
		    You are an expert at interpreting US vehicle title documents.
		    Using the provided evidence text and structured JSON blocks,
		    extract the fields required by the NlpOutput schema so that
		    the downstream BusinessSchemaMapper can populate:

		    - title_information (VIN, year, make, model, body type, prior title state, issue dates, odometer reading/status/date)
		    - owner_information (owner name, firm name if business, full address with line1, line2, city, state, zip)
		    - lien_information (first lienholder firm name and address, lien release status/date/authorized_by; second lienholder if present)
		    - assignment_of_vehicle (if present, else leave empty)
		    - officials (secretary of transportation or equivalent signatures if available)

		    STRICT RULES:
		    - Always return a complete NlpOutput JSON object conforming to the given schema.
		    - Use evidence for VIN, year, make, model, owner, lienholders, addresses, and dates.
		    - If a field is not found, set it to null (or [] for arrays).
		    - Never hallucinate: values must come from evidence or be null.
		    """;

  @Value("${aws.s3.bucket}")
  private String bucket;

  @Value("${aws.s3.input-prefix}")
  private String inputPrefix;

  @Value("${aws.s3.output-prefix}")
  private String outputPrefix;

  @Value("${aws.s3.textract-prefix}")
  private String textractPrefix;

  @Value("${aws.s3.open-ai-prefix}")
  private String openAiPrefix;

  @Value("${aws.s3.open-ai-prompt-prefix}")
  private String promptPrefix;

  @Value("${aws.s3.open-ai-prompt-file}")
  private String promptFile;

  @Value("${nlp.input}")
  private String nlpInput;

  public AiNlpService(
      ChatClient.Builder builder,
      S3Client s3Client,
      PromptLoaderService loader,
      JdbcTemplate jdbcTemplate,
      OpenAiEmbeddingModel embeddingModel) {
    this.chat = builder.build();
    this.s3 = s3Client;
    this.promptLoader = loader;
    this.jdbcTemplate = jdbcTemplate;
    this.embeddingModel = embeddingModel;
  }

  // ============================================================
  // Public API
  // ============================================================

  public Map<String, Object> normalizeFromTextractS3(String textractKey, Float minConfidence) {
    return normalizeFromTextractS3(textractKey, null, minConfidence);
  }

  public Map<String, Object> normalizeFromTextractS3(
      String textractKey, String outputKey, Float minConfidence) {

    float threshold = (minConfidence == null ? 70.0f : minConfidence);

    try {
      String resolvedTextractKey = resolveTextractKey(textractKey);
      if (nlpInput.equalsIgnoreCase("vector")) {

        return normalizeFromVectorDb(
            resolvedTextractKey, outputKey + "-vector", threshold, userTask);

      } else {
        List<Map<String, Object>> blocks = getTextractBlocksFromS3(bucket, resolvedTextractKey);

        // Log block type counts
        Map<String, Long> counts =
            blocks.stream()
                .collect(
                    Collectors.groupingBy(
                        b -> Objects.toString(b.get("BlockType"), "UNKNOWN"),
                        Collectors.counting()));
        log.info("ainlp textract block counts={}", counts);

        // Pre-parse minimal candidates (telemetry + fallback fields)
        Map<String, Object> candidates = preParseFields(blocks, threshold);

        // String extractedFieldsJson =
        // om.writerWithDefaultPrettyPrinter().writeValueAsString(candidates);

        // log.info("ainlp extracted fields json= \n{}", extractedFieldsJson);

        // log.info("ainlp textract loaded bucket={} key={} candidates={}",
        // bucket,resolvedTextractKey,candidates.keySet());

        // Build schema from NlpOutput POJO
        Map<String, Object> schema = buildNlpSchemaFromPojo();
        log.info(
            "ainlp final json schema for llm = {}",
            om.writerWithDefaultPrettyPrinter().writeValueAsString(schema));

        // Get textract high fidelity fields
        Map<String, String> textractHigh = extractHighFidelityFromTextract(blocks);

        log.info(
            "ainlp textract high fidelity json data ={}",
            om.writerWithDefaultPrettyPrinter().writeValueAsString(textractHigh));

        // --- Option 1: Chunking (large docs) ---
        // List<String> chunks = chunkTextractBlocks(blocks, 18000);
        List<String> chunks = chunkTextractBlocksWithFullCoverage(blocks, 18000);

        String allChunks =
            IntStream.range(0, chunks.size())
                .mapToObj(i -> String.format("---- Chunk %d ----%n%s", i, chunks.get(i)))
                .collect(Collectors.joining("\n\n"));

        log.info("ainlp chunking count and data ={}\n{}", chunks.size(), allChunks);

        // --- Option 2: Process chunks ---
        List<String> partials = processChunksWithLLM(chunks, schema);

        String allPartials =
            IntStream.range(0, partials.size())
                .mapToObj(i -> String.format("---- Partial %d ----%n%s", i, partials.get(i)))
                .collect(Collectors.joining("\n\n"));

        log.info("ainlp processed partials count and data ={}\n{}", partials.size(), allPartials);

        // Consolidate into final JSON
        String modelJson = consolidateResults(partials, schema);

        Map<String, Object> llmOutput =
            om.readValue(modelJson, new TypeReference<Map<String, Object>>() {});

        log.info(
            "ainlp processed partial consolidated llmOutput={}",
            om.writerWithDefaultPrettyPrinter().writeValueAsString(llmOutput));

        Map<String, Object> finalJson = mergeTextractAndLLM(llmOutput, textractHigh);

        log.info(
            "ainlp.llmoutput and textract output merged final json={}",
            om.writerWithDefaultPrettyPrinter().writeValueAsString(finalJson));

        String finalJsonString = om.writeValueAsString(finalJson);

        // Deserialize into POJO
        NlpOutput out = om.readValue(finalJsonString, NlpOutput.class);

        // Store the actual final JSON snapshot in rawJson for trace/debug
        out.setRawJson(finalJsonString);

        // Map into business-friendly schema
        Map<String, Object> business = BusinessSchemaMapper.toBusinessSchema(out);
        String businessPretty = om.writerWithDefaultPrettyPrinter().writeValueAsString(business);

        log.info("business.json={}", businessPretty);

        // Save outputs
        if (outputKey != null && !outputKey.isBlank()) {
          String fullKey = resolveOpenAiOutputKey(outputKey);
          putS3Text(bucket, fullKey, modelJson, "application/json");
          log.info("ainlp.normalized.saved s3://{}/{}", bucket, fullKey);

          String businessKey = fullKey.replaceAll("\\.json$", "") + ".business.json";
          putS3Text(bucket, businessKey, businessPretty, "application/json");
          log.info("ainlp.business.saved s3://{}/{}", bucket, businessKey);
        }

        return business;
      }

    } catch (Exception e) {
      log.error(
          "ainlp.normalizeFromTextractS3 failed bucket={} key={} msg={}",
          bucket,
          textractKey,
          e.getMessage(),
          e);
      throw new RuntimeException("AI NLP Textract normalization failed", e);
    }
  }

  private Map<String, Object> normalizeFromVectorDb(
      String docId, String outputKey, Float minConfidence, String userTask) {

    log.info("input doc id for vector db query: {}", docId);
    docId = normalizeDocId(docId);
    log.info("normalized doc id for vector db query: {}", docId);

    float threshold = (minConfidence == null ? 70.0f : minConfidence);

    threshold = 70.0f;

    try {
      // 1. Schema for output
      Map<String, Object> schema = buildNlpSchemaFromPojo();
      log.info(
          "ainlp target schema = {}",
          om.writerWithDefaultPrettyPrinter().writeValueAsString(schema));

      // 2. High-fidelity fields (direct Textract signals)
      Map<String, String> textractHigh = loadHighFidelityFromDb(docId, threshold);
      log.info(
          "ainlp high-fidelity data from db = {}",
          om.writerWithDefaultPrettyPrinter().writeValueAsString(textractHigh));

      // 3. Retrieve candidate evidence via vector search
      List<String> retrievedSnippets = retrieveRelevantChunks(docId, userTask, 15);

      log.info("ainlp retrieved {} snippets for task={}", retrievedSnippets.size(), userTask);

      String evidence =
          IntStream.range(0, retrievedSnippets.size())
              .mapToObj(
                  i -> String.format("---- Evidence %d ----%n%s", i, retrievedSnippets.get(i)))
              .collect(Collectors.joining("\n\n"));

      // 4. Prompt LLM with schema + evidence
      String modelJson = callLLMWithEvidence(evidence, schema, userTask);

      Map<String, Object> llmOutput =
          om.readValue(modelJson, new TypeReference<Map<String, Object>>() {});
      log.info(
          "ainlp LLM structured output={}",
          om.writerWithDefaultPrettyPrinter().writeValueAsString(llmOutput));

      // 5. Merge Textract high-fidelity (anchors) with LLM (semantic fill)
      Map<String, Object> finalJson = mergeTextractAndLLM(llmOutput, textractHigh);

      String finalJsonString = om.writeValueAsString(finalJson);

      // Deserialize into POJO
      NlpOutput out = om.readValue(finalJsonString, NlpOutput.class);
      out.setRawJson(finalJsonString);

      // Business schema
      Map<String, Object> business = BusinessSchemaMapper.toBusinessSchema(out);
      String businessPretty = om.writerWithDefaultPrettyPrinter().writeValueAsString(business);

      log.info("ainlp business json using vector ={}", businessPretty);

      // Save outputs if required
      if (outputKey != null && !outputKey.isBlank()) {
        String fullKey = resolveOpenAiOutputKey(outputKey);
        putS3Text(bucket, fullKey, modelJson, "application/json");
        putS3Text(
            bucket,
            fullKey.replaceAll("\\.json$", "") + ".business.json",
            businessPretty,
            "application/json");
      }

      return business;

    } catch (Exception e) {
      log.error("ainlp.normalizeFromVectorDb failed docId={} msg={}", docId, e.getMessage(), e);
      throw new RuntimeException("AI NLP vector-based normalization failed", e);
    }
  }

  private List<String> retrieveRelevantChunks(String docId, String query, int limit) {
    float[] qVec = embeddingModel.embed(query); // embed user task or schema
    PGvector qVector = new PGvector(qVec);

    return jdbcTemplate.query(
        "SELECT text FROM textract_index "
            + "WHERE doc_id = ? "
            + "ORDER BY vector <-> ? "
            + "LIMIT ?",
        ps -> {
          ps.setString(1, docId);
          ps.setObject(2, qVector);
          ps.setInt(3, limit);
        },
        (rs, rowNum) -> rs.getString("text"));
  }

  private Map<String, String> loadHighFidelityFromDb(String docId, float threshold) {
    return jdbcTemplate.query(
        "SELECT text, type FROM textract_index "
            + "WHERE doc_id=? AND confidence >= ? AND type IN ('KEY_VALUE_SET','CELL','LINE')",
        ps -> {
          ps.setString(1, docId);
          ps.setFloat(2, threshold);
        },
        rs -> {
          Map<String, String> m = new HashMap<>();
          while (rs.next()) {
            String type = rs.getString("type");
            String txt = rs.getString("text");
            m.put(type + "_" + m.size(), txt);
          }
          return m;
        });
  }

  private String callLLMWithEvidence(String evidence, Map<String, Object> schema, String userTask)
      throws Exception {

    PromptConfig cfg = promptLoader.load(bucket, buildPromptKey());

    Map<String, Object> vars = new HashMap<>();
    if (cfg.getRules() != null) vars.putAll(cfg.getRules());
    vars.put("evidence", evidence);
    vars.put("task", userTask);

    Message systemMsg = new PromptTemplate(cfg.getSystemTemplate()).createMessage(vars);

    Message userMsg =
        new PromptTemplate(
                """
	                Convert the following OCR evidence into the target JSON format.

	                STRICT RULES:
	                - Your response MUST strictly follow the provided JSON schema (no extra fields).
	                - Use the retrieved evidence snippets as the authoritative source.
	                - Fill only fields that are explicitly supported by evidence.
	                - If a field is not found in the evidence, return null (or [] for arrays).
	                - Always return the full JSON object (all schema-required fields present).
	                - Do NOT hallucinate values.
	                - Task context: {task}

	                Retrieved Evidence:
	                {evidence}
	                """)
            .createMessage(vars);

    // Enforce schema restrictions
    enforceNoAdditionalProperties(schema);

    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model("gpt-4o-mini")
            .temperature(0.0)
            .responseFormat(
                ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(
                        ResponseFormat.JsonSchema.builder()
                            .name("NlpOutput")
                            .schema(schema)
                            .strict(true)
                            .build())
                    .build())
            .build();

    String json =
        chat.prompt().messages(List.of(systemMsg, userMsg)).options(options).call().content();

    log.debug("ainlp.llm.rawJson={}", truncate(json, 1400));
    return json;
  }

  private List<String> chunkTextractBlocksWithFullCoverage(
      List<Map<String, Object>> blocks, int maxChars) {
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    // Index TABLE -> list of CELLs
    Map<String, List<Map<String, Object>>> tableCells = new HashMap<>();

    for (Map<String, Object> b : blocks) {
      String type = Objects.toString(b.get("BlockType"), "");

      // Collect cells for later table grouping
      if ("CELL".equals(type) || "MERGED_CELL".equals(type)) {
        String parentId = Objects.toString(b.get("ParentId"), "");
        tableCells.computeIfAbsent(parentId, k -> new ArrayList<>()).add(b);
        continue;
      }

      StringBuilder sb = new StringBuilder("[").append(type).append("] ");

      // Common text (WORD, LINE, QUERY_RESULT, etc.)
      if (b.containsKey("Text")) {
        sb.append(b.get("Text")).append(" ");
      }

      // Selection elements (checkboxes, radio buttons)
      if ("SELECTION_ELEMENT".equals(type)) {
        sb.append("Selected: ").append(b.get("SelectionStatus")).append(" ");
      }

      // Table marker (we’ll expand cells below)
      if ("TABLE".equals(type)) {
        sb.append("(Table detected)");
        String tableId = Objects.toString(b.get("Id"), "");
        List<Map<String, Object>> cells = tableCells.getOrDefault(tableId, List.of());

        // Group by RowIndex
        Map<Integer, List<Map<String, Object>>> rows = new TreeMap<>();
        for (Map<String, Object> cell : cells) {
          Integer row = (Integer) cell.get("RowIndex");
          rows.computeIfAbsent(row, k -> new ArrayList<>()).add(cell);
        }

        sb.append("\n");
        for (var row : rows.entrySet()) {
          sb.append("  Row ").append(row.getKey()).append(": ");
          // sort by ColumnIndex
          row.getValue().sort(Comparator.comparingInt(c -> (Integer) c.get("ColumnIndex")));
          for (Map<String, Object> cell : row.getValue()) {
            String txt = Objects.toString(cell.get("Text"), "").trim();
            sb.append(" | ").append(txt.isEmpty() ? " " : txt);
          }
          sb.append(" |\n");
        }
      }

      // Page marker
      if ("PAGE".equals(type)) {
        sb.append("(Page break) ");
      }

      // Key-value sets (forms)
      if ("KEY_VALUE_SET".equals(type)) {
        sb.append("(Form field) ");
        if (b.containsKey("EntityTypes")) {
          sb.append("EntityTypes=").append(b.get("EntityTypes")).append(" ");
        }
      }

      // Queries
      if ("QUERY".equals(type)) {
        sb.append("Query: ").append(b.getOrDefault("QueryText", "")).append(" ");
      }

      if ("QUERY_RESULT".equals(type)) {
        sb.append("Answer: ").append(b.getOrDefault("Text", "")).append(" ");
      }

      // Layout features
      if (type.startsWith("LAYOUT_")) {
        sb.append("(Layout element) ");
      }

      // Final text for this block
      String text = sb.toString().trim();
      if (text.isBlank()) continue;

      // Chunking
      if (current.length() + text.length() > maxChars) {
        chunks.add(current.toString());
        current = new StringBuilder();
      }
      current.append(text).append("\n");
    }

    if (current.length() > 0) {
      chunks.add(current.toString());
    }

    return chunks;
  }

  // ============================================================
  // Chunking + Two-Pass Summarization
  // ============================================================

  private List<String> chunkTextractBlocks(List<Map<String, Object>> blocks, int maxChars) {
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (Map<String, Object> b : blocks) {
      String type = Objects.toString(b.get("BlockType"), "");
      StringBuilder sb = new StringBuilder("[").append(type).append("] ");

      if (b.containsKey("Text")) sb.append(b.get("Text")).append(" ");
      if ("SELECTION_ELEMENT".equals(type))
        sb.append("Selected: ").append(b.get("SelectionStatus")).append(" ");
      if ("CELL".equals(type)) {
        if (b.containsKey("RowIndex") || b.containsKey("ColumnIndex")) {
          sb.append("(Row=")
              .append(b.get("RowIndex"))
              .append(", Col=")
              .append(b.get("ColumnIndex"))
              .append(") ");
        }
      }

      String text = sb.toString().trim();
      if (text.isBlank()) continue;

      if (current.length() + text.length() > maxChars) {
        chunks.add(current.toString());
        current = new StringBuilder();
      }
      current.append(text).append("\n");
    }
    if (current.length() > 0) chunks.add(current.toString());
    return chunks;
  }

  private List<String> processChunksWithLLM(List<String> chunks, Map<String, Object> schema)
      throws Exception {
    List<String> partialResults = new ArrayList<>();
    for (String chunk : chunks) {
      String fragmentJson = callModelWithCandidates(chunk, "", schema);
      partialResults.add(fragmentJson);
    }
    return partialResults;
  }

  private String consolidateResults(List<String> partialResults, Map<String, Object> schema)
      throws Exception {
    String mergedInput = String.join("\n", partialResults);
    return callModelWithCandidates(
        "Merge the following partial JSON fragments into one final JSON according to the schema:\n"
            + mergedInput,
        "",
        schema);
  }

  // ============================================================
  // LLM call
  // ============================================================

  private String callModelWithCandidates(
      String rawText, String structuredJson, Map<String, Object> schema) throws Exception {

    PromptConfig cfg = promptLoader.load(bucket, buildPromptKey());

    Map<String, Object> vars = new HashMap<>();
    if (cfg.getRules() != null) vars.putAll(cfg.getRules());
    vars.put("rawText", rawText);
    vars.put("structuredJson", structuredJson);

    Message systemMsg = new PromptTemplate(cfg.getSystemTemplate()).createMessage(vars);

    Message userMsg =
        new PromptTemplate(
                """
                        Convert the following AWS Textract output into the target JSON format.

                        STRICT RULES:
                        - Your response MUST strictly follow the provided JSON schema (no extra fields).
                        - Use structuredJson as the primary source for labeled values (KEY_VALUE_SET, TABLE/CELL, QUERY_RESULT).
                        - Use rawText to fill gaps and confirm ambiguous fields.
                        - If a field is not found, return null (or [] for arrays).
                        - Always return the full JSON object (all schema-required fields present).
                        - Avoid hallucinating values that aren't supported by the inputs.

                        Raw text:
                        {rawText}

                        Structured JSON (array of blocks):
                        {structuredJson}
                        """)
            .createMessage(vars);

    enforceNoAdditionalProperties(schema);

    OpenAiChatOptions options =
        OpenAiChatOptions.builder()
            .model("gpt-4o-mini")
            .temperature(0.0)
            .responseFormat(
                ResponseFormat.builder()
                    .type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(
                        ResponseFormat.JsonSchema.builder()
                            .name("NlpOutput")
                            .schema(schema)
                            .strict(true)
                            .build())
                    .build())
            .build();

    String json =
        chat.prompt().messages(List.of(systemMsg, userMsg)).options(options).call().content();

    log.debug("ainlp.llm.rawJson={}", truncate(json, 1400));
    return json;
  }

  // ============================================================
  // Schema helpers
  // ============================================================

  private Map<String, Object> buildNlpSchemaFromPojo() {
    SchemaGeneratorConfigBuilder cfgBuilder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
    Module jacksonModule = new com.github.victools.jsonschema.module.jackson.JacksonModule();
    cfgBuilder.with(jacksonModule);

    SchemaGenerator generator = new SchemaGenerator(cfgBuilder.build());
    JsonNode schemaNode = generator.generateSchema(NlpOutput.class);

    Map<String, Object> schema =
        om.convertValue(schemaNode, new TypeReference<Map<String, Object>>() {});
    schema.remove("$schema");
    schema.remove("$id");

    // Explicitly enforce root contract
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    // Recursively enforce across schema tree
    enforceNoAdditionalProperties(schema);

    return schema;
  }

  @SuppressWarnings("unchecked")
  private void enforceNoAdditionalProperties(Map<String, Object> schema) {
    if (schema == null) return;

    // Objects: enforce contract
    if ("object".equals(schema.get("type"))) {
      schema.put("additionalProperties", false);
      if (schema.containsKey("properties")) {
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> propsMap) {
          // ✅ Add "required" = all keys in properties
          List<String> keys = propsMap.keySet().stream().map(Object::toString).toList();
          schema.put("required", keys);

          // Recurse into children
          for (Object v : propsMap.values()) {
            if (v instanceof Map<?, ?> child) {
              enforceNoAdditionalProperties((Map<String, Object>) child);
            }
          }
        }
      }
    }

    // Arrays: recurse into items
    if ("array".equals(schema.get("type"))) {
      Object items = schema.get("items");
      if (items instanceof Map<?, ?> child) {
        enforceNoAdditionalProperties((Map<String, Object>) child);
      }
    }

    // Composite schemas: recurse into each member
    for (String composite : List.of("anyOf", "oneOf", "allOf")) {
      Object val = schema.get(composite);
      if (val instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> child) {
            enforceNoAdditionalProperties((Map<String, Object>) child);
          }
        }
      }
    }

    // Definitions: recurse into all sub-schemas
    for (String defsKey : List.of("definitions", "$defs")) {
      Object defs = schema.get(defsKey);
      if (defs instanceof Map<?, ?> defsMap) {
        for (Object v : defsMap.values()) {
          if (v instanceof Map<?, ?> child) {
            enforceNoAdditionalProperties((Map<String, Object>) child);
          }
        }
      }
    }
  }

  // ============================================================
  // Textract + S3 utils
  // ============================================================

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getTextractBlocksFromS3(String bucket, String key)
      throws Exception {
    try {
      ResponseBytes<?> bytes =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
      String json = bytes.asString(StandardCharsets.UTF_8);
      try {
        Map<String, Object> maybeObj = om.readValue(json, new TypeReference<>() {});
        Object rawBlocks = maybeObj.get("Blocks");
        if (rawBlocks instanceof List<?> list) {
          return list.stream()
              .filter(Map.class::isInstance)
              .map(m -> (Map<String, Object>) m)
              .toList();
        }
        return List.of();
      } catch (Exception ignore) {
        return om.readValue(json, new TypeReference<>() {});
      }
    } catch (NoSuchKeyException e) {
      throw new RuntimeException("Textract JSON not found at s3://" + bucket + "/" + key, e);
    }
  }

  private void putS3Text(String bucket, String key, String content, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromString(content, StandardCharsets.UTF_8));
  }

  // ============================================================
  // Utility
  // ============================================================

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() > max ? s.substring(0, max) + "…(truncated)" : s;
  }

  private String ensureSlash(String prefix) {
    return (prefix.endsWith("/") ? prefix : prefix + "/");
  }

  private String resolveTextractKey(String key) {
    String decoded = URLDecoder.decode(Objects.toString(key, ""), StandardCharsets.UTF_8);
    String texPrefix = ensureSlash(Objects.toString(textractPrefix, ""));
    if (decoded.isBlank()) throw new IllegalArgumentException("textractKey must not be blank");
    if (decoded.startsWith(texPrefix)) return decoded;
    return texPrefix + stripLeadingSlash(decoded);
  }

  private String resolveOpenAiOutputKey(String key) {
    String decoded = URLDecoder.decode(Objects.toString(key, ""), StandardCharsets.UTF_8);
    String aiPrefix = ensureSlash(Objects.toString(openAiPrefix, ""));
    if (decoded.isBlank()) throw new IllegalArgumentException("outputKey must not be blank");
    if (decoded.startsWith(aiPrefix)) return decoded;
    return aiPrefix + stripLeadingSlash(decoded);
  }

  private static String stripLeadingSlash(String s) {
    if (s == null) return "";
    return s.startsWith("/") ? s.substring(1) : s;
  }

  public String buildPromptKey() {
    return ensureSlash(promptPrefix) + promptFile;
  }

  // ============================================================
  // Pre-parser (telemetry + minimal heuristics)
  // ============================================================

  private Map<String, Object> preParseFields(
      List<Map<String, Object>> blocks, float minConfidence) {

    // 1) Extract text (prefer LINEs; fallback WORDs)
    List<String> lines =
        blocks.stream()
            .filter(b -> "LINE".equalsIgnoreCase(Objects.toString(b.get("BlockType"), "")))
            .filter(b -> meetsConfidence(b, minConfidence))
            .map(b -> Objects.toString(b.get("Text"), ""))
            .filter(s -> !s.isBlank())
            .toList();

    List<String> texts;
    if (!lines.isEmpty()) {
      texts = lines;
    } else {
      texts =
          blocks.stream()
              .filter(b -> "WORD".equalsIgnoreCase(Objects.toString(b.get("BlockType"), "")))
              .filter(b -> meetsConfidence(b, minConfidence))
              .map(b -> Objects.toString(b.get("Text"), ""))
              .filter(s -> !s.isBlank())
              .toList();
    }

    String allText = String.join(" ", texts).replaceAll("\\s+", " ").trim();

    // Regex heuristics (VIN, year, zip, etc.)
    Pattern vinPattern = Pattern.compile("\\b([A-HJ-NPR-Z0-9]{11,17})\\b");
    Pattern yearPattern = Pattern.compile("\\b(19|20)\\d{2}\\b");
    Pattern zipPattern = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b");

    String vin = null;
    int vinConf = 1;
    Matcher vinM = vinPattern.matcher(allText);
    if (vinM.find()) {
      vin = vinM.group(1);
      vinConf = 5;
    }

    Integer year = null;
    int yearConf = 1;
    Matcher yearM = yearPattern.matcher(allText);
    if (yearM.find()) {
      year = Integer.parseInt(yearM.group());
      yearConf = 5;
    }

    String address = null;
    int addrConf = 1;
    Matcher zipM = zipPattern.matcher(allText);
    if (zipM.find()) {
      address = extractSurrounding(allText, zipM.start());
      addrConf = 3;
    }

    String owner = guessOwner(allText);
    String lien = guessLien(allText);

    // Build skeleton for telemetry / fallback
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "title_information",
        Map.of(
            "vehicle_id_number", field(vin, vinConf),
            "year", field(year, yearConf)));
    root.put(
        "owner_information",
        Map.of(
            "name", field(owner, owner != null ? 3 : 1),
            "address", field(address, addrConf)));
    root.put("lien_information", Map.of("first_lienholder", field(lien, lien != null ? 3 : 1)));
    root.put("assignment_of_vehicle", new ArrayList<>());
    root.put("officials", Map.of("secretary_of_transportation", field(null, 1)));
    root.put("raw_text", allText);

    return root;
  }

  private Map<String, Object> field(Object value, int confidence) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("value", value);
    f.put("confidence", confidence);
    f.put("source", "textract");
    return f;
  }

  private String extractSurrounding(String text, int index) {
    int start = Math.max(0, index - 40);
    int end = Math.min(text.length(), index + 20);
    return text.substring(start, end).trim();
  }

  private String guessOwner(String text) {
    Pattern p =
        Pattern.compile(
            "\\b(\\w+(?:\\s+\\w+){0,3})(INC|LLC|BANK|CORP|CORPORATION)\\b",
            Pattern.CASE_INSENSITIVE);
    Matcher m = p.matcher(text);
    return m.find() ? m.group() : null;
  }

  private String guessLien(String text) {
    Pattern p = Pattern.compile("\\bFINANCE|BANK|CREDIT|MORTGAGE\\b", Pattern.CASE_INSENSITIVE);
    Matcher m = p.matcher(text);
    return m.find() ? m.group() : null;
  }

  private static boolean meetsConfidence(Map<String, Object> block, float minConfidence) {
    Object conf = block.get("Confidence");
    if (conf instanceof Number) {
      return ((Number) conf).floatValue() >= minConfidence;
    }
    if (conf instanceof String s) {
      try {
        return Float.parseFloat(s) >= minConfidence;
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> extractHighFidelityFromTextract(List<Map<String, Object>> blocks) {
    Map<String, String> result = new HashMap<>();
    List<String> makes =
        List.of(
            "FORD",
            "TOYOTA",
            "DODGE",
            "HONDA",
            "CHEVROLET",
            "NISSAN",
            "BMW",
            "MERCEDES",
            "KIA",
            "HYUNDAI");

    List<String> vinCandidates = new ArrayList<>();
    List<Integer> odometerCandidates = new ArrayList<>();
    List<Integer> yearCandidates = new ArrayList<>();
    List<LocalDate> dateCandidates = new ArrayList<>();

    DateTimeFormatter[] dateFormats = {
      DateTimeFormatter.ofPattern("M/d/uu", Locale.US),
      DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
      DateTimeFormatter.ofPattern("MM-dd-uu", Locale.US),
      DateTimeFormatter.ofPattern("MM-dd-uuuu", Locale.US)
    };

    for (Map<String, Object> block : blocks) {
      String type = Objects.toString(block.get("BlockType"), "");
      if (!"LINE".equals(type) && !"WORD".equals(type)) continue;

      String raw = Objects.toString(block.get("Text"), "");
      String text = raw.toUpperCase().trim();

      // VIN (17 chars, no I/O/Q)
      if (text.matches("^[A-HJ-NPR-Z0-9]{17}$")) {
        vinCandidates.add(text);
      }

      // Year
      if (text.matches("19\\d{2}|20\\d{2}")) {
        yearCandidates.add(Integer.parseInt(text));
      }

      // Make
      for (String m : makes) {
        if (text.contains(m)) {
          result.put("make", m);
        }
      }

      // Odometer
      if (text.matches("\\d{1,3}(,\\d{3})*(\\s*(MI|MILES))?")) {
        String cleaned = text.replaceAll("[^0-9]", "");
        if (!cleaned.isEmpty()) {
          odometerCandidates.add(Integer.parseInt(cleaned));
        }
      }

      // Fuel type
      if (text.contains("DIESEL")) result.put("fuel_type", "DIESEL");
      else if (text.contains("GAS")) result.put("fuel_type", "GAS");
      else if (text.contains("FLEX")) result.put("fuel_type", "FLEX");
      else if (text.contains("ELECTRIC")) result.put("fuel_type", "ELECTRIC");

      // Dates
      if (text.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}")) {
        for (DateTimeFormatter fmt : dateFormats) {
          try {
            LocalDate d = LocalDate.parse(text, fmt);
            dateCandidates.add(d);
            break;
          } catch (DateTimeParseException ignore) {
          }
        }
      }

      // Address (expanded suffixes)
      if (text.matches(".*\\d+\\s+.*(RD|ROAD|DR|DRIVE|ST|STREET|AVE|AVENUE|BLVD|LANE|LN|CT).*")) {
        result.put("owner_address", raw);
      }

      // Lien info
      if (text.contains("LIEN")) {
        result.put("lien_info", raw);
      }

      // Title brand normalization
      if (text.contains("SALVAGE")) result.put("title_brand", "SALVAGE");
      else if (text.contains("REBUILT")) result.put("title_brand", "REBUILT");
      else if (text.contains("DUP")) result.put("title_brand", "DUPLICATE");
    }

    // Post-processing
    vinCandidates.stream().findFirst().ifPresent(v -> result.put("vehicle_id_number", v));
    yearCandidates.stream()
        .mapToInt(y -> y)
        .max()
        .ifPresent(y -> result.put("year", String.valueOf(y)));
    odometerCandidates.stream()
        .mapToInt(o -> o)
        .max()
        .ifPresent(o -> result.put("odometer_reading", String.valueOf(o)));
    dateCandidates.stream()
        .max(Comparator.naturalOrder())
        .ifPresent(d -> result.put("date", d.toString())); // ISO yyyy-MM-dd

    return result;
  }

  // Helper to parse dates in multiple formats
  private LocalDate parseDate(String text) {
    DateTimeFormatter[] fmts = {
      DateTimeFormatter.ofPattern("M/d/yy"),
      DateTimeFormatter.ofPattern("MM/dd/yyyy"),
      DateTimeFormatter.ofPattern("M-d-yy"),
      DateTimeFormatter.ofPattern("MM-dd-yyyy")
    };
    for (DateTimeFormatter f : fmts) {
      try {
        return LocalDate.parse(text, f);
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mergeTextractAndLLM(
      Map<String, Object> llmOutput, Map<String, String> textractFields) {

    Map<String, Object> titleInfo = (Map<String, Object>) llmOutput.get("title_information");
    if (titleInfo == null) return llmOutput;

    for (Map.Entry<String, String> e : textractFields.entrySet()) {
      String key = e.getKey();
      String value = e.getValue();

      if (titleInfo.containsKey(key)) {
        Map<String, Object> field = (Map<String, Object>) titleInfo.get(key);
        // overwrite only if Textract gives something useful
        if (value != null && !value.isBlank()) {
          field.put("value", value);
          field.put("confidence", 5); // trust Textract more
        }
      }
    }

    return llmOutput;
  }

  String normalizeDocId(String key) {
    // Strip textract/ prefix and .json suffix if present
    return key.replaceFirst("^textract/", "").replaceFirst("\\.json$", "");
  }
}
