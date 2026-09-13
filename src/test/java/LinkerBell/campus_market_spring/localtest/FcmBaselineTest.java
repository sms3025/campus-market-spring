package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.dto.FcmMessageDto;
import LinkerBell.campus_market_spring.repository.UserFcmTokenRepository;
import LinkerBell.campus_market_spring.service.FcmNotificationService;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.firebase.messaging.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FcmBaselineTest {
    private final UserFcmTokenRepository tokens=mock(UserFcmTokenRepository.class);
    private final FcmNotificationService service=new FcmNotificationService(tokens);
    private FcmMessageDto dto(){return FcmMessageDto.builder().targetToken("fixture-token").title("title").body("body").deeplinkUrl("http://localhost:8080/local-test/").build();}

    @Test void waitsForSdkFutureCompletion() throws Exception {
        var pending=SettableApiFuture.<String>create();
        var entered=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> result=worker.submit(()->{
                try(var statics=mockStatic(FirebaseMessaging.class)){
                    var messaging=mock(FirebaseMessaging.class);
                    statics.when(FirebaseMessaging::getInstance).thenReturn(messaging);
                    when(messaging.sendAsync(any(Message.class))).thenAnswer(inv->{entered.countDown();return pending;});
                    service.sendNotification(dto());
                }
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(result.isDone()).isFalse();
            pending.set("message-id");result.get(5,TimeUnit.SECONDS);
            verifyNoInteractions(tokens);
        }finally{pending.set("cleanup");worker.shutdownNow();}
    }

    @ParameterizedTest @EnumSource(value=MessagingErrorCode.class,names={"INVALID_ARGUMENT","UNREGISTERED"})
    void removesInvalidToken(MessagingErrorCode code){
        var error=mock(FirebaseMessagingException.class);when(error.getMessagingErrorCode()).thenReturn(code);
        withFailure(error);verify(tokens).deleteByFcmToken("fixture-token");
    }
    @Test void preservesTokenOnTransientError(){
        var error=mock(FirebaseMessagingException.class);when(error.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        withFailure(error);verifyNoInteractions(tokens);
    }
    @Test void handlesUnexpectedFailure(){withFailure(new IllegalStateException("test failure"));verifyNoInteractions(tokens);}
    private void withFailure(Throwable error){
        try(var statics=mockStatic(FirebaseMessaging.class)){
            var messaging=mock(FirebaseMessaging.class);statics.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFailedFuture(error));
            assertThatCode(()->service.sendNotification(dto())).doesNotThrowAnyException();
        }
    }
    @Test void restoresInterruptFlag(){
        try(var statics=mockStatic(FirebaseMessaging.class)){
            var messaging=mock(FirebaseMessaging.class);statics.when(FirebaseMessaging::getInstance).thenReturn(messaging);
            when(messaging.sendAsync(any(Message.class))).thenReturn(SettableApiFuture.create());
            Thread.currentThread().interrupt();service.sendNotification(dto());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
    }
}
