package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.TelegramLinkToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface TelegramLinkTokenRepository extends JpaRepository<TelegramLinkToken, String> {

    @Modifying
    @Query("delete from TelegramLinkToken t where t.userId = :userId or t.expiresAt < :now")
    void deleteForUserOrExpired(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
