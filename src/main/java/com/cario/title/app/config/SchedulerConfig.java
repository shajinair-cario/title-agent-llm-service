package com.cario.title.app.config;

import com.cario.title.app.repository.dynamodb.DocProcessStateRepository;
import com.cario.title.app.scheduler.DocProcessScheduler;
import com.cario.title.app.scheduler.GcvIngestScheduler;
import com.cario.title.app.service.AiPipelineService;
import com.cario.title.app.service.StatusService;
import com.cario.title.app.service.VisionOcrService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import software.amazon.awssdk.services.s3.S3Client;

@Log4j2
@Configuration
@EnableScheduling
public class SchedulerConfig {

  @Value("${scheduled.threadpool.size:4}")
  private int poolSize;

  @Value("${scheduled.threadpool.await-termination-seconds:30}")
  private int awaitTerminationSeconds;

  /** Dedicated scheduler pool for @Scheduled jobs with graceful shutdown and error logging. */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(poolSize);
    scheduler.setThreadNamePrefix("doc-process-scheduler-");

    // Log any uncaught exception thrown by @Scheduled methods
    scheduler.setErrorHandler(t -> log.error("Uncaught exception in scheduled task", t));

    // Be nice on shutdown
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(awaitTerminationSeconds);

    // Handle rejected tasks if pool is saturated during shutdown, etc.
    RejectedExecutionHandler reh = new ThreadPoolExecutor.CallerRunsPolicy();
    scheduler.setRejectedExecutionHandler(reh);

    scheduler.initialize();
    log.info(
        "ThreadPoolTaskScheduler initialized poolSize={} awaitTerminationSeconds={}",
        poolSize,
        awaitTerminationSeconds);
    return scheduler;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "scheduled.ingest",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = false)
  public DocProcessScheduler docProcessScheduler(
      S3Client s3,
      AiPipelineService pipeline,
      StatusService status,
      DocProcessStateRepository stateRepo) {
    return new DocProcessScheduler(s3, pipeline, status, stateRepo);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "gcv",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = false)
  public GcvIngestScheduler gcvIngestScheduler(S3Client s3, VisionOcrService vision) {
    return new GcvIngestScheduler(s3, vision);
  }
}
