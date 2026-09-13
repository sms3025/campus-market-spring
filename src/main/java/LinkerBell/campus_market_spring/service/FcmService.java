package LinkerBell.campus_market_spring.service;

import LinkerBell.campus_market_spring.domain.Item;
import LinkerBell.campus_market_spring.domain.Keyword;
import LinkerBell.campus_market_spring.domain.User;
import LinkerBell.campus_market_spring.domain.UserFcmToken;
import LinkerBell.campus_market_spring.dto.FcmMessageDto;
import LinkerBell.campus_market_spring.dto.UserFcmTokenDto;
import LinkerBell.campus_market_spring.repository.UserFcmTokenRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class FcmService {

    private final UserFcmTokenRepository userFcmTokenRepository;
    private final FcmNotificationService fcmNotificationService;

    @Value("${deeplink.keyword_url}")
    private String deeplinkKeywordUrl;
    @Value("${deeplink.chat_url}")
    private String deeplinkChatUrl;

    public void sendFcmMessageWithKeywords(List<Keyword> sendingKeywords, Item savedItem) {
        sendKeywordNotifications(KeywordNotificationEvent.of(savedItem, sendingKeywords));
    }

    /**
     * Looks up every recipient token in one query and hands the whole set to the sender, so the
     * cost no longer grows with one query and one round trip per recipient.
     */
    public void sendKeywordNotifications(KeywordNotificationEvent event) {
        if (event.targets().isEmpty()) {
            return;
        }
        List<Long> userIds = event.targets().stream()
            .map(KeywordNotificationEvent.Target::userId)
            .distinct()
            .toList();

        Map<Long, List<String>> tokensByUser = userFcmTokenRepository
            .findFcmTokensByUserIds(userIds).stream()
            .collect(Collectors.groupingBy(UserFcmTokenDto::userId,
                Collectors.mapping(UserFcmTokenDto::fcmToken, Collectors.toList())));

        List<FcmMessageDto> messages = new ArrayList<>();
        for (KeywordNotificationEvent.Target target : event.targets()) {
            for (String fcmToken : tokensByUser.getOrDefault(target.userId(), List.of())) {
                messages.add(createKeywordFcmMessage(target.keywordName(), fcmToken, event));
            }
        }
        fcmNotificationService.sendNotifications(messages);
    }

    private FcmMessageDto createKeywordFcmMessage(String keywordName, String fcmToken,
        KeywordNotificationEvent event) {
        return FcmMessageDto.builder()
            .targetToken(fcmToken)
            .title(keywordName + " 키워드 알림")
            .body(event.itemTitle())
            .deeplinkUrl(deeplinkKeywordUrl + event.itemId())
            .build();
    }

    public void saveUserFcmToken(String firebaseToken, User user) {
        userFcmTokenRepository.findByFcmToken(firebaseToken).ifPresentOrElse(userFcmToken -> {
                userFcmToken.setLastModifiedDate(LocalDateTime.now());
            },
            () -> {
                UserFcmToken userFcmToken = UserFcmToken.builder().fcmToken(firebaseToken)
                    .user(user).build();
                userFcmTokenRepository.save(userFcmToken);
            });
    }

    public void sendFcmMessageWithChat(Long userId, Long chatRoomId, String title, String content) {
        List<String> fcmTokens = userFcmTokenRepository.findFcmTokenByUser_UserId(userId);

        for (String fcmToken : fcmTokens) {
            FcmMessageDto fcmMessageDto = FcmMessageDto.builder()
                .targetToken(fcmToken)
                .title(title)
                .body(content)
                .deeplinkUrl(deeplinkChatUrl + chatRoomId)
                .build();

            fcmNotificationService.sendNotification(fcmMessageDto);
        }
    }

    public void deleteFcmTokenAllByUserId(Long userId) {
        userFcmTokenRepository.deleteByUser_UserId(userId);
    }

}
