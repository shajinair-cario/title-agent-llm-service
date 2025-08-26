package com.cario.title.app.config;

import com.cario.title.app.service.VisionOcrService;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
@ConditionalOnProperty(
    prefix = "gcv",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class GoogleVisionConfig {

  @Bean(destroyMethod = "close")
  public ImageAnnotatorClient imageAnnotatorClient() throws Exception {
    // Load the JSON key file from classpath
    ClassPathResource resource = new ClassPathResource("gcp/sa-json");
    try (InputStream in = resource.getInputStream()) {
      GoogleCredentials credentials = GoogleCredentials.fromStream(in);
      ImageAnnotatorSettings settings =
          ImageAnnotatorSettings.newBuilder().setCredentialsProvider(() -> credentials).build();
      return ImageAnnotatorClient.create(settings);
    }
  }

  @Bean
  public VisionOcrService visionOcrService(ImageAnnotatorClient client) {
    return new VisionOcrService(client);
  }
}
