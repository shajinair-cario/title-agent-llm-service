package com.cario.title.app.service;

import com.cario.title.app.util.PerplexityResultUtils;
import com.cario.title.app.util.S3SignedUrlCleanupUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Log4j2
@Service
public class PerplexityExtractService {

  @Value("${aws.s3.bucket}")
  private String bucket;

  @Value("${perplexity.s3.bucket}")
  private String pBucket;

  @Value("${aws.s3.perplexity-ai-prefix}")
  private String outputPrefix;

  @Value("${perplexity.api.key}")
  private String apiKey;

  @Value("${aws.region:us-east-1}")
  private String awsRegion;

  @Value("${perplexity.link-mode:presigned}") // presigned | public
  private String linkMode;

  private final WebClient webClient;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final ObjectMapper mapper = new ObjectMapper();

  public PerplexityExtractService(
      WebClient.Builder builder, S3Client s3Client, S3Presigner s3Presigner) {
    this.webClient = builder.baseUrl("https://api.perplexity.ai").build();
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> extractFromS3Old(String key, List<String> fields) {
    // Build the document URL according to configured mode
    final boolean usePresigned = "presigned".equalsIgnoreCase(linkMode);
    final String docUrl =
        usePresigned
            ? presignGetUrl(pBucket, key, java.time.Duration.ofMinutes(30)) // Option 1
            : buildPublicS3Url(pBucket, key); // Option 2

    // List<String> dataUris = convertPdfFromS3ToDataUris(pBucket, key);
    List<String> dataUris = convertPdfFromS3ToDataUrisJpg(pBucket, key);
    log.info("Converted {} pages from {} to data URIs", dataUris.size(), key);

    // Prepare holders for merged result
    Map<String, Object> titleInfo = null;
    Map<String, Object> ownerInfo = null;
    Map<String, Object> lienInfo = null;
    Map<String, Object> officials = null;
    List<Object> assignments = new ArrayList<>();

    // Iterate all pages
    for (int i = 0; i < dataUris.size(); i++) {
      String pageUri = dataUris.get(i);
      Map<String, Object> pageResult = extractFromOnePage(pageUri);
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } // 1.5s delay to avoid hammering API

      if (pageResult == null) continue;

      if (titleInfo == null) {
        titleInfo = (Map<String, Object>) pageResult.get("title_information");
      }
      if (ownerInfo == null) {
        ownerInfo = (Map<String, Object>) pageResult.get("owner_information");
      }
      if (lienInfo == null) {
        lienInfo = (Map<String, Object>) pageResult.get("lien_information");
      }
      if (officials == null) {
        officials = (Map<String, Object>) pageResult.get("officials");
      }

      List<Object> pageAssignments = (List<Object>) pageResult.get("assignment_of_vehicle");
      if (pageAssignments != null && !pageAssignments.isEmpty()) {
        assignments.addAll(pageAssignments);
      }
    }

    // Build final merged result
    Map<String, Object> finalResult = new LinkedHashMap<>();
    finalResult.put("title_information", titleInfo != null ? titleInfo : Map.of());
    finalResult.put("owner_information", ownerInfo != null ? ownerInfo : Map.of());
    finalResult.put("lien_information", lienInfo != null ? lienInfo : Map.of());
    finalResult.put("assignment_of_vehicle", assignments);
    finalResult.put("officials", officials != null ? officials : Map.of());

    // Save JSON output back to S3
    try {
      String outputKey = outputPrefix + key + ".json";
      String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalResult);

      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(outputKey)
              .contentType("application/json")
              .build(),
          RequestBody.fromString(json, StandardCharsets.UTF_8));

      log.info("Saved Perplexity extraction result to s3://{}/{}", bucket, outputKey);
    } catch (Exception e) {
      log.error("Failed to save Perplexity output to S3", e);
      throw new RuntimeException("S3 save failed", e);
    }

    return finalResult;
  }

  public Map<String, Object> extractFromS3Single(String key, List<String> fields) {
    // Build the document URL according to configured mode
    final boolean usePresigned = "presigned".equalsIgnoreCase(linkMode);
    final String docUrl =
        usePresigned
            ? presignGetUrl(pBucket, key, java.time.Duration.ofMinutes(30)) // Option 1
            : buildPublicS3Url(pBucket, key); // Option 2

    // String fileReference = uploadFileToPerplexity(pBucket, key);
    // log.info("Uploaded file {} to Perplexity, got file_id={}", key, fileReference);

    // log.info("Extracting fields {} from Perplexity for doc {} (mode={})", fields, docUrl,
    // linkMode);

    List<String> dataUris = convertPdfFromS3ToDataUris(pBucket, key);
    log.info("Converted {} pages from {} to data URIs", dataUris.size(), key);

    // Call the model

    Map<String, Object> result = extractFromOnePage(dataUris.get(0));

    // Save JSON output back to your main bucket
    try {
      String outputKey = outputPrefix + key + ".json";
      String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(outputKey)
              .contentType("application/json")
              .build(),
          RequestBody.fromString(json, StandardCharsets.UTF_8));

      log.info("Saved Perplexity extraction result to s3://{}/{}", bucket, outputKey);

    } catch (Exception e) {
      log.error("Failed to save Perplexity output to S3", e);
      throw new RuntimeException("S3 save failed", e);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> extractFromS3(String key, List<String> fields) {
    // (Optional) keep these if you log/trace doc origin
    final boolean usePresigned = "presigned".equalsIgnoreCase(linkMode);
    final String docUrl =
        usePresigned
            ? presignGetUrl(pBucket, key, java.time.Duration.ofMinutes(30))
            : buildPublicS3Url(pBucket, key);

    // 1) Render pages -> upload JPEGs -> get presigned URLs
    List<String> pageUrls = convertPdfFromS3ToSignedUrls(pBucket, key);
    if (pageUrls == null || pageUrls.isEmpty()) {
      throw new IllegalStateException("No pages produced for " + key);
    }
    log.info("Rendered {} page image(s) for {} -> using first page URL", pageUrls.size(), key);

    Map<String, Object> finalResult = new HashMap<>();

    for (int i = 0; i < pageUrls.size(); i++) {
      // 2) Call the model with the first page URL (lightweight vs data URI)
      Map<String, Object> curResult = extractFromOnePage(pageUrls.get(i));

      // 3) Save output JSON back to your main bucket
      try {
        String outputKey =
            (outputPrefix + "/" + key.replace('.', '-') + "/page-" + i + "-result" + ".json")
                .replace("input", "output")
                .replace("/output/", "");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(curResult);

        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(outputKey)
                .contentType("application/json")
                .build(),
            RequestBody.fromString(json, StandardCharsets.UTF_8));

        log.info("Saved Perplexity extraction result to s3://{}/{}", bucket, outputKey);

        finalResult.put("page-" + i + "-result", curResult);

      } catch (Exception e) {
        log.error("Failed to save Perplexity output to S3", e);
        throw new RuntimeException("S3 save failed", e);
      }
    }

    try {
      String finalResultKey = buildFinalResultKey(outputPrefix, key);

      Map<String, Object> businessResult =
          PerplexityResultUtils.collapsePerplexityResultsToBusinessJson(finalResult, mapper);

      String finalResultJson =
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(businessResult);

      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(finalResultKey)
              .contentType("application/json")
              .build(),
          RequestBody.fromString(finalResultJson, StandardCharsets.UTF_8));

      log.info("Saved Perplexity extraction final result to s3://{}/{}", bucket, finalResultKey);

      S3SignedUrlCleanupUtils.deleteObjectsFromSignedUrls(s3Client, pageUrls);
    } catch (JsonProcessingException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    return finalResult;
  }

  private Map<String, Object> extractFields(List<String> dataUris) {
    // 1. Common reusable schemas
    Map<String, Object> fieldSchema =
        Map.of(
            "type", "object",
            "properties",
                Map.ofEntries(
                    Map.entry("value", Map.of("type", List.of("string", "null"))),
                    Map.entry("confidence", Map.of("type", "integer", "minimum", 1, "maximum", 5))),
            "required", List.of("value", "confidence"));

    Map<String, Object> arrayFieldSchema =
        Map.of(
            "type", "object",
            "properties",
                Map.ofEntries(
                    Map.entry("value", Map.of("type", "array", "items", Map.of("type", "string"))),
                    Map.entry("confidence", Map.of("type", "integer", "minimum", 1, "maximum", 5))),
            "required", List.of("value", "confidence"));

    Map<String, Object> priceFieldSchema =
        Map.of(
            "type", "object",
            "properties",
                Map.ofEntries(
                    Map.entry("value", Map.of("type", List.of("string", "number", "null"))),
                    Map.entry("confidence", Map.of("type", "integer", "minimum", 1, "maximum", 5))),
            "required", List.of("value", "confidence"));

    // 2. Section schemas
    Map<String, Object> titleInfoSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.ofEntries(
                Map.entry("state", fieldSchema),
                Map.entry("certificate_type", fieldSchema),
                Map.entry("title_number", fieldSchema),
                Map.entry("duplicate_indicator", fieldSchema),
                Map.entry("vehicle_id_number", fieldSchema),
                Map.entry("year", fieldSchema),
                Map.entry("make", fieldSchema),
                Map.entry("model", fieldSchema),
                Map.entry("body_type", fieldSchema),
                Map.entry("fuel_type", fieldSchema),
                Map.entry("prior_title_state", fieldSchema),
                Map.entry("date_pa_titled", fieldSchema),
                Map.entry("date_of_issue", fieldSchema),
                Map.entry("odometer_reading", fieldSchema),
                Map.entry("odometer_status", fieldSchema),
                Map.entry("odometer_recorded_date", fieldSchema),
                Map.entry("gvwr", fieldSchema),
                Map.entry("gcwr", fieldSchema),
                Map.entry("unladen_weight", fieldSchema),
                Map.entry("title_brands", arrayFieldSchema)));

    Map<String, Object> ownerInfoSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.ofEntries(Map.entry("name", fieldSchema), Map.entry("address", fieldSchema)));

    Map<String, Object> lienInfoSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.ofEntries(
                Map.entry("first_lienholder", fieldSchema),
                Map.entry(
                    "first_lien_released",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.ofEntries(
                            Map.entry("status", fieldSchema),
                            Map.entry("date", fieldSchema),
                            Map.entry("authorized_by", fieldSchema)))),
                Map.entry("second_lienholder", fieldSchema),
                Map.entry("second_lien_released", fieldSchema)));

    Map<String, Object> assignmentSchema =
        Map.of(
            "type",
            "array",
            "items",
            Map.of(
                "type",
                "object",
                "properties",
                Map.ofEntries(
                    Map.entry("assignment_type", fieldSchema),
                    Map.entry("seller", fieldSchema),
                    Map.entry("buyer_name", fieldSchema),
                    Map.entry("buyer_address", fieldSchema),
                    Map.entry("odometer_at_sale", fieldSchema),
                    Map.entry("mileage_certification", fieldSchema),
                    Map.entry("sale_date", fieldSchema),
                    Map.entry("price", priceFieldSchema),
                    Map.entry("witness", fieldSchema))));

    Map<String, Object> officialsSchema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.ofEntries(Map.entry("secretary_of_transportation", fieldSchema)));

    // 3. Root schema
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "properties",
            Map.ofEntries(
                Map.entry("title_information", titleInfoSchema),
                Map.entry("owner_information", ownerInfoSchema),
                Map.entry("lien_information", lienInfoSchema),
                Map.entry("assignment_of_vehicle", assignmentSchema),
                Map.entry("officials", officialsSchema)));

    // Build image content array (Perplexity expects `image_url`)
    List<Map<String, Object>> imageContents =
        dataUris.stream()
            .map(uri -> Map.of("type", "image_url", "image_url", Map.of("url", uri)))
            .toList();

    // Build messages
    Map<String, Object> body =
        Map.of(
            "model", "sonar-reasoning-pro",
            "messages",
                List.of(
                    Map.of(
                        "role",
                        "system",
                        "content",
                        "You are a professional title examiner. "
                            + "Your task is to carefully read the provided PDF document "
                            + "and extract structured title information. "
                            + "Organize results into nested JSON sections: "
                            + "title_information, owner_information, lien_information, "
                            + "assignment_of_vehicle (array), and officials. "
                            + "Each field must include both value and confidence (1–5). "
                            + "If a value is missing, return null with confidence=1. "
                            + "For title_brands, always return an array of strings. "
                            + "For assignment_of_vehicle.price, allow string, number, or null. "
                            + "Only use the PDF input, do not rely on external web data."),
                    Map.of(
                        "role",
                        "user",
                        "content",
                        new java.util.ArrayList<>() {
                          {
                            add(
                                Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Extract all fields from the provided vehicle title."));
                            addAll(imageContents);
                          }
                        })),
            "response_format",
                Map.of("type", "json_schema", "json_schema", Map.of("schema", schema)));

    log.debug("Sending Perplexity request: {}", body);

    return this.webClient
        .post()
        .uri("/chat/completions")
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
        .doOnError(e -> log.error("Perplexity API call failed", e))
        .block();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> extractFromOnePage(String imageUrlOrDataUri) {
    // Build a minimal OpenAI-style message with one image and a short instruction.
    Map<String, Object> body =
        Map.of(
            "model",
            "sonar-reasoning-pro",
            "messages",
            List.of(
                Map.of(
                    "role",
                    "system",
                    "content",
                    "You are a professional vehicle title examiner. "
                        + "Extract ALL available fields you can read from the image of the title. "
                        + "Return ONLY JSON. Use this structure:\n"
                        + "{\n"
                        + "  \"title_information\": { /* keys/values you find */ },\n"
                        + "  \"owner_information\": {},\n"
                        + "  \"lien_information\": {},\n"
                        + "  \"assignment_of_vehicle\": [ /* each assignment as an object */ ],\n"
                        + "  \"officials\": {}\n"
                        + "}\n"
                        + "For each leaf field, return an object: {\"value\": <string|null|number>, \"confidence\": <1-5>}.\n"
                        + "If a field is missing/illegible, set value=null and confidence=1.\n"
                        + "Do not invent information not visible in the image."),
                Map.of(
                    "role",
                    "user",
                    "content",
                    List.of(
                        Map.of(
                            "type", "text",
                            "text",
                                "Extract all fields from this vehicle title image and return only the JSON."),
                        Map.of(
                            "type", "image_url", "image_url", Map.of("url", imageUrlOrDataUri))))),
            // keep it small and predictable
            // "response_format",
            // Map.of(
            //    "type", "json_object"), // this is the important part to avoid JSON processing
            // error
            "temperature",
            0.1,
            "max_tokens",
            4000);

    log.debug("Sending Perplexity request (single image): {}", body);

    return this.webClient
        .post()
        .uri("/chat/completions")
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
        .doOnError(e -> log.error("Perplexity API call failed", e))
        .block();
  }

  private String buildS3Url(String bucket, String key) {
    try {
      String encodedKey =
          java.net.URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
      return String.format("https://%s.s3.amazonaws.com/%s", bucket, encodedKey);
    } catch (Exception e) {
      throw new RuntimeException("Failed to encode S3 key", e);
    }
  }

  private String presignGetUrl(String bucket, String key, java.time.Duration ttl) {

    try {
      var get =
          software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .build();

      var presign =
          software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.builder()
              .signatureDuration(ttl)
              .getObjectRequest(get)
              .build();

      return s3Presigner.presignGetObject(presign).url().toString();
    } finally {
      // if (s3Presigner != null) s3Presigner.close();
    }
  }

  /** Encode each segment; do NOT encode '/' */
  private String buildPublicS3Url(String bucket, String key) {
    String encoded =
        java.util.Arrays.stream(key.split("/"))
            .map(
                seg ->
                    java.net.URLEncoder.encode(seg, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"))
            .collect(java.util.stream.Collectors.joining("/"));
    return "https://" + bucket + ".s3.amazonaws.com/" + encoded;
  }

  /** Upload file from S3 to Perplexity's /v1/files endpoint and return file_id */
  private String uploadFileToPerplexity(String bucket, String key) {
    // Download S3 object into memory
    byte[] bytes = s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();

    // Wrap as ByteArrayResource (safe for multi-read)
    ByteArrayResource resource =
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return key; // ensures multipart has a filename
          }
        };

    return this.webClient
        .post()
        .uri("/v1/files")
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData("file", resource))
        .retrieve()
        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
        .map(resp -> (String) resp.get("id"))
        .block();
  }

  /** Convert the first page of a PDF in S3 to a PNG base64 Data URI */
  private List<String> convertPdfFromS3ToDataUris(String bucket, String key) {
    byte[] pdfBytes = s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();

    List<String> dataUris = new ArrayList<>();

    try (InputStream in = new ByteArrayInputStream(pdfBytes);
        PDDocument document = PDDocument.load(in)) {

      PDFRenderer renderer = new PDFRenderer(document);
      int pageCount = document.getNumberOfPages();

      for (int i = 0; i < pageCount; i++) {
        BufferedImage image = renderer.renderImageWithDPI(i, 200);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);

        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        dataUris.add("data:image/png;base64," + base64);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert PDF to image Data URIs", e);
    }

    return dataUris;
  }

  private List<String> convertPdfFromS3ToDataUrisJpg(String bucket, String key) {
    byte[] pdfBytes = s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();

    List<String> dataUris = new ArrayList<>();

    try (InputStream in = new ByteArrayInputStream(pdfBytes);
        PDDocument document = PDDocument.load(in)) {

      PDFRenderer renderer = new PDFRenderer(document);
      int pageCount = document.getNumberOfPages();

      for (int i = 0; i < pageCount; i++) {
        // Render at lower DPI (e.g. 120 instead of 200/300)
        BufferedImage image = renderer.renderImageWithDPI(i, 120);

        // Write to JPEG with compression
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = jpgWriter.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
          param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
          param.setCompressionQuality(0.6f); // 60% quality (tune between 0.5–0.8)
        }

        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(baos)) {
          jpgWriter.setOutput(output);
          jpgWriter.write(null, new IIOImage(image, null, null), param);
        } finally {
          jpgWriter.dispose();
        }

        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        dataUris.add("data:image/jpeg;base64," + base64);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to convert PDF to image Data URIs", e);
    }

    return dataUris;
  }

  /**
   * Render each page of a PDF in S3 to a compressed JPEG, upload to S3, and return short‑lived
   * presigned GET URLs for those page images.
   */
  private List<String> convertPdfFromS3ToSignedUrls(String bucket, String key) {
    // Tunables — adjust if needed
    final int renderDpi = 150; // 120–150 is plenty for LLMs/OCR
    final float jpegQuality = 0.60f; // 0.50–0.80; lower = smaller
    final Duration urlTtl = Duration.ofMinutes(30);
    final String rendersPrefix = "renders/"; // where page images will be stored

    // Fetch source PDF
    byte[] pdfBytes = s3Client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();

    List<String> urls = new ArrayList<>();

    try (InputStream in = new ByteArrayInputStream(pdfBytes);
        PDDocument document = PDDocument.load(in)) {

      PDFRenderer renderer = new PDFRenderer(document);
      int pageCount = document.getNumberOfPages();

      // Create a stable base path for outputs (avoid characters that confuse S3 keys)
      String safeBase = key.replaceAll("[^a-zA-Z0-9._/\\-]", "_");

      for (int i = 0; i < pageCount; i++) {
        // 1) Render page
        BufferedImage rendered = renderer.renderImageWithDPI(i, renderDpi);

        // 2) Ensure RGB (PDFBox may produce images with alpha; JPEG doesn't support alpha)
        BufferedImage rgb =
            new BufferedImage(
                rendered.getWidth(), rendered.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
          g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          g.drawImage(rendered, 0, 0, null);
        } finally {
          g.dispose();
        }

        // 3) Compress to JPEG bytes
        byte[] jpgBytes = toJpegBytes(rgb, jpegQuality);

        // 4) Upload to S3 under renders/…/<original>-page-XX.jpg
        String pageObjectKey = String.format("%s%s-page-%02d.jpg", rendersPrefix, safeBase, i + 1);

        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(pageObjectKey)
                .contentType("image/jpeg")
                .contentLength((long) jpgBytes.length)
                .build(),
            RequestBody.fromBytes(jpgBytes));

        // 5) Presign and collect URL
        String signedUrl = presignGetUrl(bucket, pageObjectKey, urlTtl);
        urls.add(signedUrl);
      }

    } catch (Exception e) {
      throw new RuntimeException("Failed to convert PDF to S3 JPEGs with signed URLs", e);
    }

    return urls;
  }

  /** Compress a BufferedImage to JPEG bytes with the given quality. */
  private static byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    if (param.canWriteCompressed()) {
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(quality);
    }
    try (MemoryCacheImageOutputStream mc = new MemoryCacheImageOutputStream(baos)) {
      writer.setOutput(mc);
      writer.write(null, new IIOImage(img, null, null), param);
    } finally {
      writer.dispose();
    }
    return baos.toByteArray();
  }

  private String buildFinalResultKey(String outputPrefix, String key) {
    // ensure prefix ends with one slash
    String prefix = outputPrefix.endsWith("/") ? outputPrefix : outputPrefix + "/";

    // drop a leading "input/" or "output/" from the key
    String base =
        key.replaceFirst("^(?:input|output)/", "") // regex: remove leading folder if present
                .replace('.', '-') // replace dots for extension
            + "/final-result.json";

    return prefix + base;
  }
}
