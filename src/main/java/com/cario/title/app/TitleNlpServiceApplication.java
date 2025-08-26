package com.cario.title.app;

import com.cario.title.app.config.ServiceConfig.PerplexityServiceProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Title NLP Service Spring Boot application.
 *
 * <p>This service performs OCR and natural language processing on vehicle title documents. It
 * bootstraps the Spring application context and starts any configured web endpoints.
 *
 * <p>Logging is provided via Lombok's {@code @Log4j2} annotation, which injects a {@code log} field
 * for Log4j2-based logging. Usage:
 *
 * <pre>
 *   mvn spring-boot:run
 * </pre>
 *
 * @author Shaji Nair
 */
@Log4j2
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PerplexityServiceProperties.class)
public class TitleNlpServiceApplication {

  /**
   * Main entry point for the Spring Boot application.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    log.info("Starting Title NLP Service application...");
    SpringApplication.run(TitleNlpServiceApplication.class, args);
    log.info("Title NLP Service application started successfully.");
  }
}
