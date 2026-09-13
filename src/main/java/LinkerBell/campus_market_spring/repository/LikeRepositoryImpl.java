package LinkerBell.campus_market_spring.repository;

import LinkerBell.campus_market_spring.domain.QChatRoom;
import LinkerBell.campus_market_spring.domain.QItem;
import LinkerBell.campus_market_spring.domain.QLike;
import LinkerBell.campus_market_spring.domain.QUser;
import LinkerBell.campus_market_spring.dto.LikeSearchResponseDto;
import LinkerBell.campus_market_spring.dto.QItemSearchResponseDto;
import LinkerBell.campus_market_spring.dto.SliceResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

public class LikeRepositoryImpl implements LikeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public LikeRepositoryImpl(EntityManager em) {
        queryFactory = new JPAQueryFactory(em);
    }


    @Override
    public SliceResponse<LikeSearchResponseDto> findAllByUserId(Long userId, Pageable pageable) {
        QLike like = QLike.like;
        QItem item = QItem.item;
        QUser user = QUser.user;
        // Counted per item with correlated aggregates instead of joining both collections at once,
        // which would multiply chat room rows by like rows before grouping.
        QChatRoom chatRoomCount = new QChatRoom("chatRoomCount");
        QLike likeCount = new QLike("likeCount");

        JPAQuery<LikeSearchResponseDto> query = queryFactory
            .select(Projections.constructor(LikeSearchResponseDto.class,
                like.likeId,
                new QItemSearchResponseDto(
                    item.itemId,
                    user.userId,
                    user.nickname,
                    item.thumbnail,
                    item.title,
                    item.price,
                    JPAExpressions.select(chatRoomCount.count().intValue())
                        .from(chatRoomCount)
                        .where(chatRoomCount.item.eq(item)),
                    JPAExpressions.select(likeCount.count().intValue())
                        .from(likeCount)
                        .where(likeCount.item.eq(item)),
                    item.itemStatus,
                    Expressions.TRUE, item.createdDate, item.lastModifiedDate)
            ))
            .from(like)
            .leftJoin(like.item, item)
            .leftJoin(item.user, user)
            .where(
                like.user.userId.eq(userId),
                item.isDeleted.isFalse()
            )
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize() + 1)
            .orderBy(like.createdDate.desc());

        List<LikeSearchResponseDto> content = query.fetch();

        boolean hasNext = false;
        if (content.size() > pageable.getPageSize()) {
            content.remove(content.size() - 1);
            hasNext = true;
        }
        return new SliceResponse<LikeSearchResponseDto>(
            new SliceImpl<>(content, pageable, hasNext));
    }
}
