package org.elasticsearch.app.api.server.configs;

import org.elasticsearch.app.EEASettings;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableCaching
public class ServerConfiguration {

    @Bean
    public ThreadPoolTaskScheduler threadPoolTaskScheduler() {
        ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setPoolSize(1);
        threadPoolTaskScheduler.setRemoveOnCancelPolicy(true);
        threadPoolTaskScheduler.setThreadNamePrefix("TaskScheduler:");
        return threadPoolTaskScheduler;
    }

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("dashboardsInfo");
    }

    @Bean
    public ThreadPoolTaskExecutor harvestingTaskExecutor() {
        int threads = (System.getenv().get("threads") != null) ? Integer.parseInt(System.getenv().get("threads")) : EEASettings.THREADS;
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(threads);
        threadPoolTaskExecutor.setMaxPoolSize(threads);
        return threadPoolTaskExecutor;
    }
}
