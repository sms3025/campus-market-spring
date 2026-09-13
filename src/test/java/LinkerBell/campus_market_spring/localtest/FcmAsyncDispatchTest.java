package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.dto.FcmMessageDto;
import LinkerBell.campus_market_spring.dto.UserFcmTokenDto;
import LinkerBell.campus_market_spring.repository.UserFcmTokenRepository;
import LinkerBell.campus_market_spring.service.FcmNotificationService;
import LinkerBell.campus_market_spring.service.FcmService;
import LinkerBell.campus_market_spring.service.KeywordNotificationEvent;
import LinkerBell.campus_market_spring.service.KeywordNotificationListener;
import com.google.firebase.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Covers what replaced the per-recipient blocking send: one token query, one batch call, after commit. */
class FcmAsyncDispatchTest {

    private final UserFcmTokenRepository tokens = mock(UserFcmTokenRepository.class);

    @Test void looksUpEveryRecipientTokenInOneQuery() {
        FcmNotificationService sender = mock(FcmNotificationService.class);
        FcmService service = new FcmService(tokens, sender);
        ReflectionTestUtils.setField(service, "deeplinkKeywordUrl", "http://localhost:8080/local-test/?itemId=");
        when(tokens.findFcmTokensByUserIds(List.of(1L, 2L))).thenReturn(List.of(
            new UserFcmTokenDto(1L, "token-a"), new UserFcmTokenDto(2L, "token-b"),
            new UserFcmTokenDto(2L, "token-c")));

        service.sendKeywordNotifications(new KeywordNotificationEvent(7L, "맥북 에어 팝니다",
            List.of(new KeywordNotificationEvent.Target(1L, "맥북"),
                new KeywordNotificationEvent.Target(2L, "에어"))));

        verify(tokens, times(1)).findFcmTokensByUserIds(List.of(1L, 2L));
        verify(tokens, never()).findFcmTokenByUser_UserId(anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FcmMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(sender, times(1)).sendNotifications(captor.capture());
        assertThat(captor.getValue()).extracting(FcmMessageDto::getTargetToken)
            .containsExactly("token-a", "token-b", "token-c");
        assertThat(captor.getValue()).extracting(FcmMessageDto::getTitle)
            .containsExactly("맥북 키워드 알림", "에어 키워드 알림", "에어 키워드 알림");
        assertThat(captor.getValue().get(0).getDeeplinkUrl()).endsWith("itemId=7");
    }

    @Test void sendsOneBatchAndRemovesOnlyTheRejectedToken() throws Exception {
        FcmNotificationService service = new FcmNotificationService(tokens);
        FirebaseMessagingException unregistered = mock(FirebaseMessagingException.class);
        when(unregistered.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        SendResponse accepted = mock(SendResponse.class);
        when(accepted.isSuccessful()).thenReturn(true);
        SendResponse rejected = mock(SendResponse.class);
        when(rejected.isSuccessful()).thenReturn(false);
        when(rejected.getException()).thenReturn(unregistered);
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getResponses()).thenReturn(List.of(accepted, rejected));

        try (var statics = mockStatic(FirebaseMessaging.class)) {
            FirebaseMessaging messaging = mock(FirebaseMessaging.class);
            statics.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.sendEach(anyList())).thenReturn(batchResponse);

            service.sendNotifications(List.of(message("token-a"), message("token-b")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
            verify(messaging, times(1)).sendEach(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            verify(messaging, never()).sendAsync(any(Message.class));
            verify(tokens).deleteByFcmToken("token-b");
            verify(tokens, never()).deleteByFcmToken("token-a");
        }
    }

    @Test void batchFailureIsLoggedWithoutBreakingTheCaller() throws Exception {
        FcmNotificationService service = new FcmNotificationService(tokens);
        try (var statics = mockStatic(FirebaseMessaging.class)) {
            FirebaseMessaging messaging = mock(FirebaseMessaging.class);
            statics.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.sendEach(anyList())).thenThrow(mock(FirebaseMessagingException.class));

            assertThatCode(() -> service.sendNotifications(List.of(message("token-a"))))
                .doesNotThrowAnyException();
            verifyNoInteractions(tokens);
        }
    }

    @Test void emptyTargetListSkipsTheLookupEntirely() {
        FcmNotificationService sender = mock(FcmNotificationService.class);
        FcmService service = new FcmService(tokens, sender);

        service.sendKeywordNotifications(new KeywordNotificationEvent(7L, "제목", List.of()));

        verifyNoInteractions(tokens, sender);
    }

    /** The dispatch contract itself: after the registration commits, and never on the request thread. */
    @Test void dispatchRunsAfterCommitOnTheNotificationExecutor() throws Exception {
        Method handler = KeywordNotificationListener.class
            .getMethod("onKeywordNotification", KeywordNotificationEvent.class);

        assertThat(handler.getAnnotation(TransactionalEventListener.class).phase())
            .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(handler.getAnnotation(Async.class).value()).isEqualTo("fcmExecutor");
    }

    @Test void dispatchFailureIsCountedAndNotPropagated() {
        FcmService fcmService = mock(FcmService.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KeywordNotificationListener listener = new KeywordNotificationListener(fcmService, meters);
        KeywordNotificationEvent event = new KeywordNotificationEvent(7L, "제목",
            List.of(new KeywordNotificationEvent.Target(1L, "키워드")));
        doThrow(new IllegalStateException("sender down")).when(fcmService).sendKeywordNotifications(event);

        // The caller already returned, so a failure here must only be recorded.
        assertThatCode(() -> listener.onKeywordNotification(event)).doesNotThrowAnyException();

        assertThat(meters.counter("fcm.dispatch", "outcome", "failure").count()).isEqualTo(1);
        assertThat(meters.timer("fcm.dispatch.duration", "outcome", "failure").count()).isEqualTo(1);
    }

    @Test void successfulDispatchIsCounted() {
        FcmService fcmService = mock(FcmService.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KeywordNotificationListener listener = new KeywordNotificationListener(fcmService, meters);
        KeywordNotificationEvent event = new KeywordNotificationEvent(7L, "제목", List.of());

        listener.onKeywordNotification(event);

        verify(fcmService).sendKeywordNotifications(event);
        assertThat(meters.counter("fcm.dispatch", "outcome", "success").count()).isEqualTo(1);
    }

    private FcmMessageDto message(String token) {
        return FcmMessageDto.builder().targetToken(token).title("제목").body("본문")
            .deeplinkUrl("http://localhost:8080/local-test/?itemId=1").build();
    }
}
