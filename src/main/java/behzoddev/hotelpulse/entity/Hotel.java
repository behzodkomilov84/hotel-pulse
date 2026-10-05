package behzoddev.hotelpulse.entity;

import behzoddev.hotelpulse.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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

    /** true — xonalar soni qo'lda kiritilgan, Exely sinxronlashi uni o'zgartirmaydi. */
    @Column(name = "rooms_count_manual", nullable = false)
    private boolean roomsCountManual;

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

    /** Exely PMS Universal API kaliti ("Ключ интеграции") — shifrlangan, sahifalarga chiqarilmaydi. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "exely_pms_key", length = 1024)
    private String exelyPmsKey;

    /** PMS bronlari shu paytgacha (mehmonxona vaqti) o'zgarganlari olingan. */
    @Column(name = "pms_bookings_synced_until")
    private LocalDateTime pmsBookingsSyncedUntil;

    /** PMS to'lovlari shu paytgacha olingan. */
    @Column(name = "pms_payments_synced_until")
    private LocalDateTime pmsPaymentsSyncedUntil;

    /** PMS xizmatlar hisoboti (kunlik daromad) qamrab olgan sanalar; null — hali olinmagan. */
    @Column(name = "pms_services_from")
    private LocalDate pmsServicesFrom;

    @Column(name = "pms_services_until")
    private LocalDate pmsServicesUntil;

    /** Read Reservation API'ning keyingi so'rov tokeni (inkremental sinxronlash). */
    @Column(name = "exely_continue_token", length = 1024)
    private String exelyContinueToken;

    @Column(name = "exely_last_sync_at")
    private LocalDateTime exelyLastSyncAt;

    @Column(name = "exely_last_sync_ok")
    private Boolean exelyLastSyncOk;

    @Column(name = "exely_last_sync_message", length = 500)
    private String exelyLastSyncMessage;

    /** "Exely bilan solishtirish" — oxirgi tekshiruv vaqti, natijasi va hisoboti (JSON). */
    @Column(name = "exely_verified_at")
    private LocalDateTime exelyVerifiedAt;

    @Column(name = "exely_verify_ok")
    private Boolean exelyVerifyOk;

    @Column(name = "exely_verify_report", columnDefinition = "MEDIUMTEXT")
    private String exelyVerifyReport;

    @Column(nullable = false)
    private boolean active = true;

    /** Kunlik Telegram hisobot vaqti (mehmonxona vaqti). */
    @Column(name = "daily_report_time", nullable = false)
    private LocalTime dailyReportTime = DEFAULT_DAILY_REPORT_TIME;

    /** Kunlik hisobot shu kun uchun yuborilgan (kuniga bir marta). */
    @Column(name = "daily_report_sent_on")
    private LocalDate dailyReportSentOn;

    public static final LocalTime DEFAULT_DAILY_REPORT_TIME = LocalTime.of(5, 0);

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Exely bilan qaysidir usulda ulanganmi (PMS kaliti yoki Connect). */
    public boolean isExelyConnected() {
        return hasPmsKey() || usesConnectApi();
    }

    /** Exely PMS Universal API (tavsiya etiladi — to'lovlar va qarzdorlik ham keladi). */
    public boolean hasPmsKey() {
        return notBlank(exelyPmsKey);
    }

    /** Exely Connect (Read Reservation API) — uchala qiymat ham kerak. */
    public boolean usesConnectApi() {
        return notBlank(exelyPropertyId) && notBlank(exelyClientId) && notBlank(exelyClientSecret);
    }

    public boolean hasExelySecret() {
        return notBlank(exelyClientSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
