package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Payment;
import behzoddev.hotelpulse.entity.ServiceRevenue;
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

    @Test
    void foreignCurrencyAmountsAreConvertedOnArrivalDate() {
        ExelyPmsApi.Booking usd = new ExelyPmsApi.Booking("b2", "N-2", "2026-09-30T08:00:00Z", "USD",
                new ExelyPmsApi.Customer("Smith", "John"), List.of(
                stay("rs1", "2026-10-01T14:00", "2026-10-02T12:00", "CheckedOut", "Confirmed",
                        new BigDecimal("86.19"), new BigDecimal("10"))), null, "Trip.com Group");
        List<String> calls = new java.util.ArrayList<>();
        MoneyConverter x10000 = (amount, currency, date) -> {
            calls.add(currency + "@" + date);
            return "USD".equals(currency) ? amount.multiply(BigDecimal.valueOf(10_000)) : amount;
        };

        Booking row = ExelyPmsMapper.toBookings(usd, 5L, TASHKENT, Map.of(), x10000).get(0);

        assertEquals(0, new BigDecimal("861900").compareTo(row.getTotalAmount()));
        assertEquals(0, new BigDecimal("100000").compareTo(row.getBalanceDue()));
        assertTrue(calls.stream().allMatch(c -> c.equals("USD@2026-10-01")), calls.toString());

        Payment p = ExelyPmsMapper.toPayment(new ExelyPmsApi.Payment(9L, "N-2", 0, new BigDecimal("20"),
                "202610011512", null, 1, null, "USD", null), 5L, x10000);
        assertEquals(0, new BigDecimal("200000").compareTo(p.getAmount()));
    }

    @Test
    void servicesUseExelyRateAndKeepDuplicateServiceIdsPerStay() {
        // Real ma'lumotdagi holat: bitta nonushta id'si ikki xil yashashda (bronda) takrorlanadi.
        ExelyPmsApi.ServicesData data = new ExelyPmsApi.ServicesData(List.of(
                new ExelyPmsApi.Service("acc1", 0, "Accommodation", new BigDecimal("45.61"), "20261001", 11L, false),
                new ExelyPmsApi.Service("BRK", 1, "Buffet breakfast", new BigDecimal("24"), "20261001", 11L, true),
                new ExelyPmsApi.Service("acc2", 0, "Accommodation", new BigDecimal("45.61"), "20261001", 12L, false),
                new ExelyPmsApi.Service("BRK", 1, "Buffet breakfast", new BigDecimal("24"), "20261001", 12L, true),
                new ExelyPmsApi.Service("uz", 0, "Проживание", new BigDecimal("500000"), "20261002", 13L, false),
                // kurs berilmagan USD — Markaziy bank kursi (money) bilan
                new ExelyPmsApi.Service("norate", 0, "Accommodation", new BigDecimal("10"), "20261002", 14L, false),
                new ExelyPmsApi.Service("bad", 0, "x", new BigDecimal("1"), null, 13L, false)),
                List.of(new ExelyPmsApi.ServiceReservation(11L, "US-1", new BigDecimal("69.61"), "USD", new BigDecimal("11808.76")),
                        new ExelyPmsApi.ServiceReservation(12L, "US-1", new BigDecimal("69.61"), "USD", new BigDecimal("11808.76")),
                        new ExelyPmsApi.ServiceReservation(13L, "UZ-1", new BigDecimal("500000"), "UZS", BigDecimal.ONE),
                        new ExelyPmsApi.ServiceReservation(14L, "US-2", new BigDecimal("10"), "USD", null)));
        MoneyConverter cbu = (amount, currency, date) ->
                "USD".equals(currency) ? amount.multiply(new BigDecimal("12000")) : amount;

        var rows = ExelyPmsMapper.toServices(data, 5L, "UZS", cbu);

        assertEquals(6, rows.size(), "sanasiz qator tashlanadi");
        assertEquals(6, rows.stream().map(ServiceRevenue::getExternalId).distinct().count(),
                "takroriy nonushta id'si turli yashashlarda — alohida qatorlar");
        assertEquals(0, new BigDecimal("538597.54").compareTo(rows.get(0).getAmount()), "45.61 × 11808.76 (Exely kursi)");
        assertEquals(0, new BigDecimal("283410.24").compareTo(rows.get(1).getAmount()), "24 × 11808.76");
        assertEquals(1, rows.get(1).getKind());
        assertEquals("USD", rows.get(1).getCurrency());
        assertEquals(11L, rows.get(1).getReservationId());
        assertEquals("US-1", rows.get(1).getBookingNumber());
        assertEquals(0, new BigDecimal("500000").compareTo(rows.get(4).getAmount()), "so'm — o'girilmaydi");
        assertEquals(LocalDate.of(2026, 10, 2), rows.get(4).getServiceDate());
        assertEquals(0, new BigDecimal("120000").compareTo(rows.get(5).getAmount()), "kurs yo'q — CBU");
        assertEquals("N-7", ExelyPmsMapper.bookingNumber("pms:N-7#9007199"));
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
