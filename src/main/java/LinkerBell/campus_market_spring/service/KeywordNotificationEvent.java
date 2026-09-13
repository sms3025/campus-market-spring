package LinkerBell.campus_market_spring.service;

import LinkerBell.campus_market_spring.domain.Item;
import LinkerBell.campus_market_spring.domain.Keyword;
import java.util.List;

/**
 * Carries only the values the notification needs. Entities would be detached by the time the
 * listener runs after commit, so the ids and names are copied while the session is still open.
 */
public record KeywordNotificationEvent(Long itemId, String itemTitle, List<Target> targets) {

    public record Target(Long userId, String keywordName) {

    }

    public static KeywordNotificationEvent of(Item item, List<Keyword> keywords) {
        List<Target> targets = keywords.stream()
            .map(keyword -> new Target(keyword.getUser().getUserId(), keyword.getKeywordName()))
            .toList();
        return new KeywordNotificationEvent(item.getItemId(), item.getTitle(), targets);
    }
}
