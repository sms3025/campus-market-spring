package LinkerBell.campus_market_spring.service;

import LinkerBell.campus_market_spring.global.config.AsyncConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs after the item registration transaction commits, so a rolled back registration never
 * produces a notification, and the caller does not wait for the send to finish.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KeywordNotificationListener {

    private final FcmService fcmService;
    private final MeterRegistry meterRegistry;

    @Async(AsyncConfig.FCM_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKeywordNotification(KeywordNotificationEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            fcmService.sendKeywordNotifications(event);
        } catch (RuntimeException e) {
            outcome = "failure";
            log.error("Keyword notification dispatch failed for item {}", event.itemId(), e);
        } finally {
            sample.stop(meterRegistry.timer("fcm.dispatch.duration", "outcome", outcome));
            meterRegistry.counter("fcm.dispatch", "outcome", outcome).increment();
        }
    }
}
