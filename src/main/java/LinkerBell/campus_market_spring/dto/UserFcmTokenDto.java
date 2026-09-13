package LinkerBell.campus_market_spring.dto;

/**
 * One row of the batch token lookup, so sending to many users needs a single query.
 */
public record UserFcmTokenDto(Long userId, String fcmToken) {

}
