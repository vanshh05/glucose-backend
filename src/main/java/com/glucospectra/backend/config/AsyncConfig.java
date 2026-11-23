package com.glucospectra.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables @Async method execution across the application.
 *
 * A dedicated small pool for persistence work — we never want Supabase
 * latency to starve the request-handling threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "persistenceExecutor")
    public TaskExecutor persistenceExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("persist-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }
}
