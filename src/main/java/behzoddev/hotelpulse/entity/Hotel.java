package behzoddev.hotelpulse.entity;

import behzoddev.hotelpulse.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "hotels")
@Getter
@Setter
@NoArgsConstructor
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String city;

    @Column(name = "rooms_count", nullable = false)
    private int roomsCount;

    @Column(nullable = false, length = 3)
    private String currency = "UZS";

    @Column(name = "exely_property_id", length = 64)
    private String exelyPropertyId;

    /** Exely Connect OAuth2 client_id (mehmonxona extranet'da yaratadi). */
    @Column(name = "exely_client_id", length = 128)
    private String exelyClientId;

    /** client_secret — bazada shifrlangan holda saqlanadi; sahifalarga hech qachon chiqarilmaydi. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "exely_client_secret", length = 1024)
    private String exelyClientSecret;

    /** Read Reservation API'ning keyingi so'rov tokeni (inkremental sinxronlash). */
    @Column(name = "exely_continue_token", length = 1024)
    private String exelyContinueToken;

    @Column(name = "exely_last_sync_at")
    private LocalDateTime exelyLastSyncAt;

    @Column(name = "exely_last_sync_ok")
    private Boolean exelyLastSyncOk;

    @Column(name = "exely_last_sync_message", length = 500)
    private String exelyLastSyncMessage;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Sinxronlash uchun uchala qiymat ham kerak. */
    public boolean isExelyConnected() {
        return notBlank(exelyPropertyId) && notBlank(exelyClientId) && notBlank(exelyClientSecret);
    }

    public boolean hasExelySecret() {
        return notBlank(exelyClientSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
