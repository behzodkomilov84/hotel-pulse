package behzoddev.hotelpulse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hotel_id", nullable = false)
    private Long hotelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DataOrigin origin;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    /** Savdo kanali: Booking.com, Sayt, To'g'ridan-to'g'ri va h.k. */
    @Column(nullable = false, length = 64)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "guest_name", length = 150)
    private String guestName;

    @Column(name = "arrival_date", nullable = false)
    private LocalDate arrivalDate;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(nullable = false)
    private int rooms = 1;

    @Column(nullable = false)
    private int guests = 1;

    /** Butun yashash davri uchun xona narxi. */
    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /** PMS bergan to'lanmagan qoldiq; null — manba bermaydi (qarz to'lovlardan hisoblanadi). */
    @Column(name = "balance_due", precision = 15, scale = 2)
    private BigDecimal balanceDue;

    /** Manbadagi asl valyuta (masalan, OTA bronlarida USD); summalar baribir mehmonxona valyutasida saqlanadi. */
    @Column(length = 3)
    private String currency;

    @Column(name = "booked_at", nullable = false)
    private LocalDateTime bookedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public long getNights() {
        return Math.max(1, ChronoUnit.DAYS.between(arrivalDate, departureDate));
    }
}
