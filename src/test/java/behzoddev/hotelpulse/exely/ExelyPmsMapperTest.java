package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExelyPmsMapperTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static ExelyPmsApi.RoomStay stay(String id, String in, String out, String status, String bookingStatus,
                                             BigDecimal amount, BigDecimal toPay) {
        return new ExelyPmsApi.RoomStay(id, in, out, status, bookingStatus,
                new ExelyPmsApi.GuestCount(2, 1), new ExelyPmsApi.TotalPrice(amount, toPay, BigDecimal.ZERO));
    }

    private static ExelyPmsApi.Booking booking(List<ExelyPmsApi.RoomStay> stays, String channel) {
        return new ExelyPmsApi.Booking("b1", "N-1", "2026-09-30T08:00:00Z", "UZS",
                new ExelyPmsApi.Customer("Karimov", "Aziz"), stays,
                new ExelyPmsApi.KeyValue("frontdesk", "Стойка"), channel);
    }

    @Test
    void mapsStatusesBalanceAndSource() {
        ExelyPmsApi.Booking src = booking(List.of(
                stay("rs1", "2026-10-01T14:00", "2026-10-03T12:00", "CheckedOut", "Confirmed",
                        new BigDecimal("1200000"), new BigDecimal("300000")),
                stay("rs2", "2026-10-04T14:00", "2026-10-06T12:00", "CheckedIn", "Confirmed",
                        new BigDecimal("900000"), BigDecimal.ZERO),
                stay("rs3", "2026-10-10T14:00", "2026-10-11T12:00", "New", "Pending",
                        new BigDecimal("400000"), new BigDecimal("400000")),
                stay("rs4", "2026-10-10T14:00", "2026-10-11T12:00", "Cancelled", "Cancelled",
                        new BigDecimal("400000"), BigDecimal.ZERO)), "Booking.com");

        List<Booking> rows = ExelyPmsMapper.toBookings(src, 5L, TASHKENT, Map.of());

        assertEquals(4, rows.size());
        Booking a = rows.get(0);
        assertEquals(DataOrigin.EXELY_PMS, a.getOrigin());
        assertEquals("pms:N-1#rs1", a.getExternalId());
        assertEquals(BookingStatus.CHECKED_OUT, a.getStatus());
        assertEquals(0, new BigDecimal("300000").compareTo(a.getBalanceDue()));
        assertEquals("Booking.com", a.getSource());
        assertEquals("Karimov Aziz", a.getGuestName());
        assertEquals(3, a.getGuests());
        assertEquals(LocalDate.of(2026, 10, 1), a.getArrivalDate());
        // UTC 08:00 → Toshkent 13:00 (PMS yaratilgan vaqtni bermaydi — lastModified)
        assertEquals(LocalDateTime.of(2026, 9, 30, 13, 0), a.getBookedAt());

        assertEquals(BookingStatus.CHECKED_IN, rows.get(1).getStatus());
        assertEquals(BookingStatus.CONFIRMED, rows.get(2).getStatus());
        assertEquals(BookingStatus.CANCELLED, rows.get(3).getStatus());
        assertNotNull(rows.get(3).getCancelledAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(rows.get(3).getBalanceDue()), "bekor qilinganda qarz yo'q");
    }

    @Test
    void keepsFirstSeenBookedAtAndFallsBackToSourceValue() {
        LocalDateTime firstSeen = LocalDateTime.of(2026, 8, 1, 9, 0);
        ExelyPmsApi.Booking src = booking(List.of(
                stay("rs1", "2026-10-01T14:00", "2026-10-01T18:00", "New", "Confirmed",
                        new BigDecimal("200000"), new BigDecimal("-5"))), null);

        Booking row = ExelyPmsMapper.toBookings(src, 5L, TASHKENT, Map.of("pms:N-1#rs1", firstSeen)).get(0);

        assertEquals(firstSeen, row.getBookedAt(), "birinchi ko'rilgan vaqt saqlanadi");
        assertEquals("Стойка", row.getSource());
        assertEquals(LocalDate.of(2026, 10, 2), row.getDepartureDate(), "kunduzgi yashash — 1 kecha");
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getBalanceDue()), "manfiy qoldiq 0 ga keltiriladi");
    }

    private static ExelyPmsApi.Payment pay(long id, int actionKind, String amount, String cancelledAt) {
        return new ExelyPmsApi.Payment(id, "N-1", actionKind, new BigDecimal(amount),
                "202610011512", "202610011510", 1, "Uzcard", "UZS", cancelledAt);
    }

    @Test
    void paymentSignFollowsActionKind() {
        Payment p = ExelyPmsMapper.toPayment(pay(1, 0, "500000", null), 5L);
        assertNotNull(p);
        assertEquals("pms-pay:1", p.getExternalId());
        assertEquals(0, new BigDecimal("500000").compareTo(p.getAmount()));
        assertEquals(LocalDateTime.of(2026, 10, 1, 15, 12), p.getPaidAt());
        assertEquals("Uzcard", p.getMethod());
        assertNull(p.getBookingId());

        assertEquals(0, new BigDecimal("300000").compareTo(ExelyPmsMapper.toPayment(pay(2, 4, "300000", null), 5L).getAmount()),
                "oldindan to'lov — qo'shiladi");
        assertEquals(0, new BigDecimal("-150000").compareTo(ExelyPmsMapper.toPayment(pay(3, 1, "150000", null), 5L).getAmount()),
                "qaytarish — ayiriladi");
    }

    @Test
    void cancelledPaymentsAndCancellationRecordsAreSkipped() {
        assertNull(ExelyPmsMapper.toPayment(pay(1, 0, "100000", "202610011600"), 5L), "bekor qilingan asl yozuv");
        assertNull(ExelyPmsMapper.toPayment(pay(2, 2, "100000", null), 5L), "to'lovni bekor qilish yozuvi");
        assertNull(ExelyPmsMapper.toPayment(pay(3, 3, "100000", null), 5L), "qaytarishni bekor qilish yozuvi");
    }
}
