/**
 * AWS configuration package for the document extraction service.
 *
 * <p>Contains Spring configuration classes that provide AWS client beans for services like S3,
 * TEXTRACT, and DynamoDB.
 */
package com.cario.title.app.config;

import com.cario.title.app.repository.dynamodb.DocArtifactAppender;
import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.textract.TextractClient;

/**
 * AWS configuration for the production profile.
 *
 * <p>This class defines Spring beans for AWS service clients used in the document extraction
 * service, including:
 *
 * <ul>
 *   <li>{@link S3Client} - to interact with Amazon S3 for file storage.
 *   <li>{@link TextractClient} - to perform OCR and document analysis.
 *   <li>{@link DynamoDbClient} - to store and retrieve structured data.
 * </ul>
 *
 * <p>This configuration is active only when the {@code production} Spring profile is enabled.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-09
 */
@Configuration
@Profile("production")
@Import({ServiceConfig.class, ChatGptConfig.class, SchedulerConfig.class})
public class AwsProdConfig {

  /**
   * AWS region in which the clients will operate. Injected from the application configuration
   * property {@code aws.region}.
   */
  @Value("${aws.region}")
  private String region;

  @Value("${dynamodb.tableName}")
  private String tableName;

  /**
   * Creates an Amazon S3 client.
   *
   * @return a configured {@link S3Client} for the specified AWS region.
   */
  @Bean
  public S3Client s3Client() {
    return S3Client.builder().region(Region.of(region)).build();
  }

  @Bean
  S3Presigner s3Presigner(StaticCredentialsProvider creds) {
    return S3Presigner.builder().region(Region.of(region)).build();
  }

  /**
   * Creates an Amazon TEXTRACT client.
   *
   * @return a configured {@link TextractClient} for the specified AWS region.
   */
  @Bean
  public TextractClient textractClient() {
    return TextractClient.builder().region(Region.of(region)).build();
  }

  /**
   * Creates an Amazon DynamoDB client using the default credentials provider.
   *
   * @return a configured {@link DynamoDbClient} for the specified AWS region.
   */
  @Bean
  public DynamoDbClient dynamoDbClient() {
    return DynamoDbClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  @Bean
  public DocProcessStateRepository docProcessStateRepository(DynamoDbClient ddb) {
    return new DocProcessStateRepository(ddb, tableName);
  }

  @Bean
  public DocArtifactAppender docArtifactAppender(DocProcessStateRepository repository) {
    return new DocArtifactAppender(repository);
  }

  @Bean
  public Tesseract tesseract() {
    Tesseract t = new Tesseract();
    // Set data path if you bundle tessdata in resources and copy out on startup
    // t.setDatapath("/usr/share/tesseract-ocr/4.00/tessdata");
    t.setLanguage("eng");
    t.setVariable("user_defined_dpi", "300");

    return t;
  }
}
