package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Saytda yaratilgan bir martalik Telegram ulash tokeni. */
@Entity
@Table(name = "telegram_link_tokens")
@Getter
@Setter
@NoArgsConstructor
public class TelegramLinkToken {

    @Id
    @Column(length = 64)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
