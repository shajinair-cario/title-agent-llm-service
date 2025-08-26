package com.cario.title.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import java.util.List;

public class VisionOcrService {

  private final ImageAnnotatorClient client;
  private final ObjectMapper om = new ObjectMapper();

  public VisionOcrService(ImageAnnotatorClient client) {
    this.client = client;
  }

  /** Calls GCV with TEXT_DETECTION or DOCUMENT_TEXT_DETECTION and returns raw JSON string. */
  public String ocrBytes(byte[] bytes, boolean documentMode) throws Exception {
    ByteString content = ByteString.copyFrom(bytes);
    Image img = Image.newBuilder().setContent(content).build();

    Feature.Type type =
        documentMode ? Feature.Type.DOCUMENT_TEXT_DETECTION : Feature.Type.TEXT_DETECTION;
    Feature feat = Feature.newBuilder().setType(type).build();

    AnnotateImageRequest req =
        AnnotateImageRequest.newBuilder().addFeatures(feat).setImage(img).build();

    BatchAnnotateImagesResponse batch = client.batchAnnotateImages(List.of(req));
    AnnotateImageResponse resp = batch.getResponses(0);

    if (resp.hasError()) {
      throw new RuntimeException("GCV error: " + resp.getError().getMessage());
    }
    // Return the full response as JSON (easy to archive & audit)
    return om.writeValueAsString(resp);
  }
}
