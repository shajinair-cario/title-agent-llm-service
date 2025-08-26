/**
 * AWS configuration package for the document extraction service.
 *
 * <p>Contains Spring configuration classes for AWS client beans.
 */
package com.cario.title.app.config;

import com.cario.title.app.repository.dynamodb.DocArtifactAppender;
import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.textract.TextractClient;

/**
 * AWS configuration for the local environment.
 *
 * <p>This configuration uses static credentials provided in the application properties for local
 * development and testing purposes. It defines Spring beans for AWS service clients:
 *
 * <ul>
 *   <li>{@link S3Client} - For interacting with Amazon S3 buckets.
 *   <li>{@link TextractClient} - For running Amazon Textract document analysis.
 *   <li>{@link DynamoDbClient} - For interacting with Amazon DynamoDB tables.
 * </ul>
 *
 * <p>Active only when the {@code local} Spring profile is enabled.
 *
 * @author Shaji Nair
 * @version 1.0
 * @since 2025-08-09
 */
@Configuration
@Profile("local")
@Import({ServiceConfig.class, ChatGptConfig.class, GoogleVisionConfig.class, SchedulerConfig.class})
public class AwsLocalConfig {

  /** AWS region in which the clients will operate. */
  @Value("${aws.region}")
  private String region;

  /** AWS access key ID for local development. */
  @Value("${aws.accessKeyId}")
  private String accessKeyId;

  /** AWS secret access key for local development. */
  @Value("${aws.secretAccessKey}")
  private String secretAccessKey;

  @Value("${dynamodb.tableName}")
  private String tableName;

  @Bean
  StaticCredentialsProvider awsCreds() {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey));
  }

  /**
   * Creates an Amazon S3 client using static credentials.
   *
   * @return a configured {@link S3Client} for the specified AWS region.
   */
  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .region(Region.of(region))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .build();
  }

  @Bean
  S3Presigner s3Presigner(StaticCredentialsProvider creds) {
    return S3Presigner.builder().region(Region.of(region)).credentialsProvider(creds).build();
  }

  /**
   * Creates an Amazon Textract client using static credentials.
   *
   * @return a configured {@link TextractClient} for the specified AWS region.
   */
  @Bean
  public TextractClient textractClient() {
    return TextractClient.builder()
        .region(Region.of(region))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
        .build();
  }

  /**
   * Creates an Amazon DynamoDB client using static credentials.
   *
   * @return a configured {@link DynamoDbClient} for the specified AWS region.
   */
  @Bean
  public DynamoDbClient dynamoDbClient() {
    return DynamoDbClient.builder()
        .region(Region.of(region))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
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
}
