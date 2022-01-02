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
    public ThreadPoolTaskExecutor harvestingTaskExecutor() {
        int threads = (System.getenv().get("max_concurrent_harvests") != null) ? Integer.parseInt(System.getenv().get("max_concurrent_harvests")) : EEASettings.MAX_CONCURRENT_HARVESTS;
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(threads);
        threadPoolTaskExecutor.setMaxPoolSize(threads);
        return threadPoolTaskExecutor;
    }


    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("dashboardsInfo");
    }
}
