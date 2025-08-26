package com.cario.title.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

@SpringBootTest
@ActiveProfiles("local")
class AwsBeansLocalIntegrationTest {

  @Autowired private S3Client s3Client;

  @Autowired private DynamoDbClient dynamoDbClient;

  @Test
  void testS3Connection() {
    ListBucketsResponse buckets = s3Client.listBuckets();
    assertNotNull(buckets);
    System.out.println("Buckets found: " + buckets.buckets().size());
  }

  @Test
  void testDynamoDbConnection() {
    ListTablesResponse tables = dynamoDbClient.listTables();
    assertNotNull(tables);
    System.out.println("Tables found: " + tables.tableNames());
  }
}
