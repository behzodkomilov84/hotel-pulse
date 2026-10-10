package behzoddev.hotelpulse.faktura;

import behzoddev.hotelpulse.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Mehmonxonaning ЭСФ operatori (Didox yoki Faktura.uz) ulanishi. Parol, client secret va token bazada shifrlangan, sahifalarga chiqmaydi. */
@Entity
@Table(name = "faktura_accounts")
@Getter
@Setter
@NoArgsConstructor
public class FakturaAccount {

    @Id
    @Column(name = "hotel_id")
    private Long hotelId;

    /** ЭСФ operatori: {@link #DIDOX} yoki {@link #FAKTURA}. */
    @Column(nullable = false, length = 16)
    private String provider = FAKTURA;

    @Column(name = "company_inn", nullable = false, length = 9)
    private String companyInn;

    @Column(length = 128)
    private String username;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 512)
    private String password;

    @Column(name = "client_id", length = 128)
    private String clientId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "client_secret", length = 512)
    private String clientSecret;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "token_expires")
    private LocalDateTime tokenExpires;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "sync_ok")
    private Boolean syncOk;

    @Column(name = "sync_message", length = 500)
    private String syncMessage;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static final String DIDOX = "DIDOX";
    public static final String FAKTURA = "FAKTURA";

    public boolean isDidox() {
        return DIDOX.equals(provider);
    }

    /** Operator nomi (sahifalar uchun). */
    public String getProviderName() {
        return isDidox() ? "Didox" : "Faktura.uz";
    }

    public boolean isComplete() {
        if (isDidox()) {
            return companyInn != null && password != null;
        }
        return companyInn != null && username != null && password != null && clientId != null && clientSecret != null;
    }
}
