package LinkerBell.campus_market_spring.global.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Notification sending runs here instead of on the request thread. The queue is bounded and
 * rejections are counted, so an overloaded sender is visible rather than silently unbounded.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    public static final String FCM_EXECUTOR = "fcmExecutor";

    @Bean(name = FCM_EXECUTOR)
    public ThreadPoolTaskExecutor fcmExecutor(MeterRegistry meterRegistry) {
        Counter rejected = Counter.builder("fcm.executor.rejected")
            .description("Notification dispatches refused because the queue was full")
            .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("fcm-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            rejected.increment();
            log.warn("FCM dispatch rejected. queue={}, active={}", pool.getQueue().size(),
                pool.getActiveCount());
        });
        // Pending notifications are given a chance to finish when the application shuts down.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // Not initialized here on purpose: Spring initializes the bean, and binding metrics to a
        // manually created pool would leave the gauges pointing at a discarded executor.
        // Queue, active threads and pool size are bound by TaskExecutorMetricsAutoConfiguration
        // under the tag name="fcmExecutor".
        return executor;
    }
}
