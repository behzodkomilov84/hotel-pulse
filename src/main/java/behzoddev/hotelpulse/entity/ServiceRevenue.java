package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Exely PMS xizmatlar hisobotining bir qatori: bitta kun, bitta xizmat (yashash, nonushta va h.k.). */
@Entity
@Table(name = "service_revenue")
@Getter
@Setter
@NoArgsConstructor
public class ServiceRevenue {

    /** kind: 0 — yashash; qolganlari (1 — qo'shimcha xizmat, 2 — transfer, 3/4 — erta/kech) — xizmatlar. */
    public static final int ACCOMMODATION = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hotel_id", nullable = false)
    private Long hotelId;

    /** Xizmat id + ":" + yashash id (xizmat id bronlar orasida takrorlanishi mumkin). */
    @Column(name = "external_id", nullable = false, length = 160)
    private String externalId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.TINYINT)
    private int kind;

    @Column(length = 128)
    private String name;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "booking_number", length = 64)
    private String bookingNumber;

    /** Exely yashash (roomStay) identifikatori. */
    @Column(name = "reservation_id")
    private Long reservationId;

    /** Asl valyuta (amount baribir mehmonxona valyutasida saqlanadi). */
    @Column(length = 3)
    private String currency;
}
