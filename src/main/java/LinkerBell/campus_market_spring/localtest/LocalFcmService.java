package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.dto.FcmMessageDto;
import LinkerBell.campus_market_spring.repository.UserFcmTokenRepository;
import LinkerBell.campus_market_spring.service.FcmNotificationService;
import com.google.firebase.messaging.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Mock mode keeps one delay per message, so a batch is never credited with a shorter wait than the per-message baseline. */
@Service @Profile("local-test")
public class LocalFcmService extends FcmNotificationService {
    private final UserFcmTokenRepository tokens;
    private final MeterRegistry meters;
    private final boolean real;
    private final long delayMs;
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();

    public LocalFcmService(UserFcmTokenRepository tokens, MeterRegistry meters,
        @Value("${local-test.fcm.real:false}") boolean real,
        @Value("${local-test.fcm.delay-ms:0}") long delayMs) {
        super(tokens);
        this.tokens = tokens; this.meters = meters; this.real = real;
        if (delayMs < 0 || delayMs > 5000) throw new IllegalArgumentException("FCM delay must be 0..5000ms");
        this.delayMs = delayMs;
    }

    @Override public void sendNotifications(List<FcmMessageDto> messages) {
        if (messages.isEmpty()) return;
        if (real) {
            List<FcmMessageDto> deliverable = messages.stream()
                .filter(dto -> !dto.getTargetToken().startsWith("mock-")).toList();
            messages.stream().filter(dto -> dto.getTargetToken().startsWith("mock-"))
                .forEach(dto -> record(dto, "real", "mock_token_skipped", 0));
            super.sendNotifications(deliverable);
            deliverable.forEach(dto -> record(dto, "real", "batch_submitted", 0));
            return;
        }
        for (FcmMessageDto dto : messages) {
            sendNotification(dto);
        }
    }

    @Override public void sendNotification(FcmMessageDto dto) {
        Timer.Sample timer = Timer.start(meters);
        String outcome = "success";
        try {
            if (real) {
                if (dto.getTargetToken().startsWith("mock-")) {
                    outcome = "mock_token_skipped";
                    return;
                }
                Message.Builder message = Message.builder().setToken(dto.getTargetToken())
                    .setNotification(Notification.builder().setTitle(dto.getTitle()).setBody(dto.getBody()).build());
                if (dto.getDeeplinkUrl() != null) message.putData("deeplink", dto.getDeeplinkUrl());
                FirebaseMessaging.getInstance().sendAsync(message.build()).get();
            } else {
                Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); outcome = "interrupted";
        } catch (ExecutionException e) {
            outcome = "failure";
            if (e.getCause() instanceof FirebaseMessagingException f) {
                if (f.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || f.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                    tokens.deleteByFcmToken(dto.getTargetToken());
                }
            }
        } catch (RuntimeException e) {
            outcome = "failure";
        } finally {
            String mode = real ? "real" : "mock";
            timer.stop(meters.timer("local.fcm.duration", "mode", mode, "outcome", outcome));
            record(dto, mode, outcome, 1);
        }
    }

    private void record(FcmMessageDto dto, String mode, String outcome, int counted) {
        if (counted > 0) meters.counter("local.fcm.completed", "mode", mode, "outcome", outcome).increment();
        synchronized (events) {
            if (events.size() == 100) events.removeFirst();
            events.addLast(Map.of("at", Instant.now().toString(), "mode", mode, "outcome", outcome,
                "title", Objects.toString(dto.getTitle(), ""), "body", Objects.toString(dto.getBody(), ""),
                "deeplink", Objects.toString(dto.getDeeplinkUrl(), "")));
        }
    }

    public List<Map<String, Object>> events() { synchronized (events) { return List.copyOf(events); } }
    public boolean isReal() { return real; }
}
