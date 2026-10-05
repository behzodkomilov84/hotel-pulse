package behzoddev.hotelpulse.kpi;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KpiCalculatorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);

    private static Booking booking(LocalDate arrival, int nights, int rooms, long total,
                                   BookingStatus status, String source) {
        Booking b = new Booking();
        b.setArrivalDate(arrival);
        b.setDepartureDate(arrival.plusDays(nights));
        b.setRooms(rooms);
        b.setTotalAmount(BigDecimal.valueOf(total));
        b.setStatus(status);
        b.setSource(source);
        return b;
    }

    @Test
    void basicOccupancyAdrRevpar() {
        // 10 xona, 2 kun = 20 mavjud xona-kecha.
        Period p = new Period("custom", D1, D1.plusDays(1));
        List<Booking> bookings = List.of(
                booking(D1, 2, 1, 1_000_000, BookingStatus.CHECKED_OUT, "Booking.com"),   // 2 xona-kecha
                booking(D1, 1, 2, 800_000, BookingStatus.CHECKED_OUT, "Sayt"));           // 2 xona-kecha

        StayMetrics m = KpiCalculator.calculate(bookings, 10, p);

        assertEquals(20, m.availableRoomNights());
        assertEquals(4, m.soldRoomNights());
        assertEquals(0.2, m.occupancy(), 1e-9);
        assertEquals(0, new BigDecimal("1800000").compareTo(m.roomRevenue()));
        assertEquals(0, BigDecimal.valueOf(450_000).compareTo(m.adr()));     // 1.8M / 4
        assertEquals(0, BigDecimal.valueOf(90_000).compareTo(m.revpar()));   // 1.8M / 20
    }

    @Test
    void exelyServicesReplaceBookingRevenueInsideCoverage() {
        // 3 kun; xizmatlar hisoboti faqat 1- va 2-kunni qamraydi (3-kun — bronlardan).
        Period p = new Period("custom", D1, D1.plusDays(2));
        List<Booking> bookings = List.of(
                booking(D1, 3, 1, 900_000, BookingStatus.CHECKED_OUT, "Booking.com"),  // 300 000 / kecha
                booking(D1, 1, 1, 500_000, BookingStatus.CHECKED_OUT, "Sayt"));
        KpiCalculator.ServiceDays services = new KpiCalculator.ServiceDays(D1, D1.plusDays(1), java.util.Map.of(
                D1, new BigDecimal[]{new BigDecimal("700000"), new BigDecimal("100000")}
                // D1+1 — qamrovda, lekin xizmat yo'q → 0
        ));

        StayMetrics m = KpiCalculator.calculate(bookings, 10, p, services);

        assertEquals(4, m.soldRoomNights(), "bandlik baribir bronlardan");
        // 700 000 (1-kun, yashash) + 0 (2-kun) + 300 000 (3-kun, bron) = 1 000 000
        assertEquals(0, new BigDecimal("1000000").compareTo(m.roomRevenue()));
        assertEquals(0, new BigDecimal("100000").compareTo(m.extrasRevenue()));
        assertEquals(0, new BigDecimal("1100000").compareTo(m.totalRevenue()));
        assertEquals(0, BigDecimal.valueOf(250_000).compareTo(m.adr()), "ADR — faqat yashashdan: 1M / 4");
        assertEquals(0, new BigDecimal("800000").compareTo(m.daily().get(0).revenue()), "grafik — jami");
        // Manbalar ulushi bronlardan, summasi yashash daromadiga moslangan.
        BigDecimal sourcesSum = m.sources().stream().map(StayMetrics.SourceShare::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1000000").compareTo(sourcesSum));
    }

    @Test
    void bookingSpanningPeriodBoundaryIsProrated() {
        // 4 kechalik bron, davrga faqat oxirgi 2 kechasi tushadi.
        Period p = new Period("custom", D1, D1.plusDays(4));
        Booking b = booking(D1.minusDays(2), 4, 1, 400_000, BookingStatus.CHECKED_OUT, "Sayt");

        StayMetrics m = KpiCalculator.calculate(List.of(b), 5, p);

        assertEquals(2, m.soldRoomNights());
        assertEquals(0, BigDecimal.valueOf(200_000).compareTo(m.roomRevenue()));
        assertEquals(1, m.daily().get(0).roomsSold());
        assertEquals(1, m.daily().get(1).roomsSold());
        assertEquals(0, m.daily().get(2).roomsSold());
        // Davrdan oldin kelgan — o'rtacha yashash muddatiga kirmaydi.
        assertEquals(0, m.avgLengthOfStay(), 1e-9);
    }

    @Test
    void cancelledAndNoShowDoNotOccupyRooms() {
        Period p = new Period("custom", D1, D1);
        List<Booking> bookings = List.of(
                booking(D1, 1, 1, 500_000, BookingStatus.CANCELLED, "Sayt"),
                booking(D1, 1, 1, 500_000, BookingStatus.NO_SHOW, "Sayt"),
                booking(D1, 3, 1, 900_000, BookingStatus.CHECKED_IN, "Expedia"));

        StayMetrics m = KpiCalculator.calculate(bookings, 4, p);

        assertEquals(1, m.soldRoomNights());
        assertEquals(1, m.noShows());
        assertEquals(0, BigDecimal.valueOf(300_000).compareTo(m.roomRevenue()));
        assertEquals(3, m.avgLengthOfStay(), 1e-9);
        assertEquals(1, m.sources().size());
        assertEquals("Expedia", m.sources().get(0).source());
        assertEquals(1.0, m.sources().get(0).share(), 1e-9);
    }

    @Test
    void zeroRoomsDoesNotDivideByZero() {
        Period p = new Period("custom", D1, D1);
        StayMetrics m = KpiCalculator.calculate(List.of(), 0, p);
        assertEquals(0, m.occupancy());
        assertEquals(0, BigDecimal.ZERO.compareTo(m.adr()));
        assertEquals(0, BigDecimal.ZERO.compareTo(m.revpar()));
    }
}
