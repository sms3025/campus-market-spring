package LinkerBell.campus_market_spring.service;

import LinkerBell.campus_market_spring.dto.FcmMessageDto;
import LinkerBell.campus_market_spring.repository.UserFcmTokenRepository;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.List;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Profile("!local-test")
@Slf4j
@RequiredArgsConstructor
@Transactional
public class FcmNotificationService {

    private final UserFcmTokenRepository userFcmTokenRepository;

    /**
     * Firebase accepts up to 500 messages per batch call.
     */
    private static final int BATCH_LIMIT = 500;

    /**
     * Sends every message for one item registration. A batch call replaces one blocking round trip
     * per recipient, and each message keeps its own failure handling through the batch response.
     */
    public void sendNotifications(List<FcmMessageDto> fcmMessageDtos) {
        for (int start = 0; start < fcmMessageDtos.size(); start += BATCH_LIMIT) {
            List<FcmMessageDto> chunk = fcmMessageDtos.subList(start,
                Math.min(start + BATCH_LIMIT, fcmMessageDtos.size()));
            try {
                BatchResponse batchResponse = FirebaseMessaging.getInstance()
                    .sendEach(chunk.stream().map(this::toMessage).toList());
                for (int index = 0; index < batchResponse.getResponses().size(); index++) {
                    SendResponse sendResponse = batchResponse.getResponses().get(index);
                    if (!sendResponse.isSuccessful() && sendResponse.getException() != null) {
                        handleFirebaseMessagingException(sendResponse.getException(),
                            chunk.get(index).getTargetToken());
                    }
                }
            } catch (FirebaseMessagingException e) {
                log.error("Failed to send notification batch of {} messages", chunk.size(), e);
            } catch (Throwable e) {
                log.error("invalid error={}", e.getMessage());
            }
        }
    }

    private Message toMessage(FcmMessageDto fcmMessageDto) {
        Message.Builder messageBuilder = Message.builder()
            .setToken(fcmMessageDto.getTargetToken())
            .setNotification(Notification.builder()
                .setTitle(fcmMessageDto.getTitle())
                .setBody(fcmMessageDto.getBody())
                .build());

        if (fcmMessageDto.getDeeplinkUrl() != null) {
            messageBuilder.putData("deeplink", fcmMessageDto.getDeeplinkUrl());
        }
        return messageBuilder.build();
    }

    public void sendNotification(FcmMessageDto fcmMessageDto) {
        Message message = toMessage(fcmMessageDto);
        try {
            String response = FirebaseMessaging.getInstance().sendAsync(message).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseMessagingException messagingException) {
                handleFirebaseMessagingException(messagingException,
                    fcmMessageDto.getTargetToken());
            } else {
                log.error("Unexpected error occurred while sending notification", e);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Notification sending was interrupted", e);
        } catch (Throwable e) {
            log.error("invalid error={}", e.getMessage());
        }
    }

    private void handleFirebaseMessagingException(FirebaseMessagingException ex,
        String targetToken) {
        switch (ex.getMessagingErrorCode()) {
            case INVALID_ARGUMENT -> {
                log.error("Invalid FCM token, removing token: {}", targetToken);
                userFcmTokenRepository.deleteByFcmToken(targetToken);
            }
            case UNREGISTERED -> {
                log.error("Unregistered FCM token, removing token: {}", targetToken);
                userFcmTokenRepository.deleteByFcmToken(targetToken);
            }
            default -> log.error("Unexpected FirebaseMessagingException occurred", ex);
        }
    }
}
