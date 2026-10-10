package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExelyBookingMapperTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static ExelyApi.Booking booking(String status, List<ExelyApi.RoomStay> stays,
                                            ExelyApi.Cancellation cancellation, BigDecimal prepaid) {
        return new ExelyApi.Booking("500360", "N-1", status, "2026-09-01T10:00:00Z", "2026-09-01T10:00:00Z",
                new ExelyApi.GuaranteeInfo(prepaid), "UZS", stays, null, cancellation,
                new ExelyApi.Source("Channel", "PA2"), new ExelyApi.Customer("Ali", "Valiyev"));
    }

    private static ExelyApi.RoomStay stay(String arrival, String departure, Integer adults, BigDecimal after, BigDecimal before) {
        return new ExelyApi.RoomStay(new ExelyApi.StayDates(arrival, departure),
                new ExelyApi.GuestCount(adults, List.of(7)), new ExelyApi.Total(before, after));
    }

    @Test
    void eachRoomStayBecomesSeparateRow() {
        ExelyApi.Booking src = booking("Active", List.of(
                stay("2026-10-01T14:00", "2026-10-04T12:00", 2, new BigDecimal("1650000"), new BigDecimal("1500000")),
                stay("2026-10-10T14:00", "2026-10-11T12:00", 1, null, new BigDecimal("400000"))), null, BigDecimal.ZERO);

        List<Booking> rows = ExelyBookingMapper.toBookings(src, 7L, TASHKENT, LocalDate.of(2026, 10, 4));

        assertEquals(2, rows.size());
        Booking a = rows.get(0);
        assertEquals("N-1#0", a.getExternalId());
        assertEquals(DataOrigin.EXELY, a.getOrigin());
        assertEquals(7L, a.getHotelId());
        assertEquals(LocalDate.of(2026, 10, 1), a.getArrivalDate());
        assertEquals(LocalDate.of(2026, 10, 4), a.getDepartureDate());
        assertEquals(0, new BigDecimal("1650000").compareTo(a.getTotalAmount()), "soliq bilan narx olinadi");
        assertEquals(3, a.getGuests(), "2 kattalar + 1 bola");
        assertEquals("Kanal PA2", a.getSource());
        assertEquals("Valiyev Ali", a.getGuestName());
        // UTC 10:00 → Toshkent 15:00
        assertEquals(LocalDateTime.of(2026, 9, 1, 15, 0), a.getBookedAt());
        assertEquals(BookingStatus.CHECKED_OUT, a.getStatus(), "bugun ketgan");

        Booking b = rows.get(1);
        assertEquals("N-1#1", b.getExternalId());
        assertEquals(0, new BigDecimal("400000").compareTo(b.getTotalAmount()), "priceAfterTax yo'q — priceBeforeTax");
        assertEquals(BookingStatus.CONFIRMED, b.getStatus());
    }

    @Test
    void cancelledBookingKeepsCancellationTime() {
        ExelyApi.Booking src = booking("Cancelled",
                List.of(stay("2026-10-20T14:00", "2026-10-22T12:00", 1, new BigDecimal("500000"), null)),
                new ExelyApi.Cancellation(BigDecimal.ZERO, "2026-09-05T06:30:00Z"), BigDecimal.ZERO);

        Booking row = ExelyBookingMapper.toBookings(src, 1L, TASHKENT, LocalDate.of(2026, 10, 4)).get(0);

        assertEquals(BookingStatus.CANCELLED, row.getStatus());
        assertEquals(LocalDateTime.of(2026, 9, 5, 11, 30), row.getCancelledAt());
    }

    @Test
    void dayUseStayCountsAsOneNightAndStatusFollowsDates() {
        ExelyApi.Booking src = booking("Active",
                List.of(stay("2026-10-04T10:00", "2026-10-04T18:00", 1, new BigDecimal("200000"), null)), null, null);

        Booking row = ExelyBookingMapper.toBookings(src, 1L, TASHKENT, LocalDate.of(2026, 10, 4)).get(0);

        assertEquals(LocalDate.of(2026, 10, 5), row.getDepartureDate());
        assertEquals(BookingStatus.CHECKED_IN, row.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(ExelyBookingMapper.prepaid(src)));
    }

    @Test
    void sourceNames() {
        assertEquals("Sayt", ExelyBookingMapper.sourceName(new ExelyApi.Source("BookingEngine", null)));
        assertEquals("Kanal", ExelyBookingMapper.sourceName(new ExelyApi.Source("Channel", "")));
        assertEquals("Номаълум", ExelyBookingMapper.sourceName(null));
        assertEquals("SomethingNew", ExelyBookingMapper.sourceName(new ExelyApi.Source("SomethingNew", "X")));
    }
}
