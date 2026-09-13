package LinkerBell.campus_market_spring.repository;

import LinkerBell.campus_market_spring.domain.Keyword;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KeywordRepository extends JpaRepository<Keyword, Long> {

    /**
     * Narrows notification targets in the database. The title match is case insensitive under the
     * default collation, so the caller still applies the exact check on this candidate set.
     */
    @Query("select k from Keyword k "
        + "join fetch k.user u "
        + "join fetch u.campus c "
        + "where c.campusId = :campusId "
        + "and u.userId <> :writerId "
        + "and locate(k.keywordName, :title) > 0")
    List<Keyword> findMatchingKeywords(@Param("campusId") Long campusId,
        @Param("writerId") Long writerId, @Param("title") String title);

    List<Keyword> findKeywordByUser_UserId(Long userId);

}
