package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Payment;
import behzoddev.hotelpulse.entity.ServiceRevenue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exely PMS bronini va to'lovini bizning modelga o'giradi.
 *
 * Har bir roomStay — alohida Booking qatori: externalId = "{raqam}#{roomStayId}".
 * Holat PMS'ning o'zidan (CheckedIn/CheckedOut/Cancelled/New), qarz — toPayAmount.
 * PMS bron yaratilgan vaqtni bermaydi — bookedAt birinchi ko'rilganda lastModified'dan
 * olinadi va keyingi yangilanishlarda saqlab qolinadi (ExelyBookingWriter).
 */
public final class ExelyPmsMapper {

    private ExelyPmsMapper() {
    }

    public static String externalPrefix(String number) {
        return "pms:" + number + "#";
    }

    /** "pms:{raqam}#{roomStayId}" → raqam. */
    public static String bookingNumber(String externalId) {
        String id = externalId.startsWith("pms:") ? externalId.substring(4) : externalId;
        int hash = id.lastIndexOf('#');
        return hash > 0 ? id.substring(0, hash) : id;
    }

    public static List<Booking> toBookings(ExelyPmsApi.Booking src, Long hotelId, ZoneId zone,
                                           Map<String, LocalDateTime> knownBookedAt) {
        return toBookings(src, hotelId, zone, knownBookedAt, MoneyConverter.NONE);
    }

    /**
     * @param money bron valyutasi (currencyId, masalan OTA bronlarida USD) mehmonxonanikidan farq qilsa —
     *              summalar kelish sanasidagi kurs bo'yicha o'giriladi
     */
    public static List<Booking> toBookings(ExelyPmsApi.Booking src, Long hotelId, ZoneId zone,
                                           Map<String, LocalDateTime> knownBookedAt, MoneyConverter money) {
        List<Booking> result = new ArrayList<>();
        if (src.roomStays() == null) {
            return result;
        }
        LocalDateTime modified = toLocal(src.lastModified(), zone);
        for (ExelyPmsApi.RoomStay rs : src.roomStays()) {
            LocalDate arrival = datePart(rs.checkInDateTime());
            LocalDate departure = datePart(rs.checkOutDateTime());
            if (arrival == null || departure == null) {
                continue;
            }
            if (!departure.isAfter(arrival)) {
                departure = arrival.plusDays(1);   // kunduzgi yashash — bitta kecha
            }
            String externalId = externalPrefix(src.number()) + (rs.id() == null ? result.size() : rs.id());

            Booking b = new Booking();
            b.setHotelId(hotelId);
            b.setOrigin(DataOrigin.EXELY_PMS);
            b.setExternalId(externalId);
            b.setSource(sourceName(src));
            b.setGuestName(guestName(src.customer()));
            b.setArrivalDate(arrival);
            b.setDepartureDate(departure);
            b.setRooms(1);
            b.setGuests(guests(rs.guestCountInfo()));
            ExelyPmsApi.TotalPrice price = rs.totalPrice();
            BigDecimal total = price != null && price.amount() != null ? price.amount() : BigDecimal.ZERO;
            BigDecimal due = price != null && price.toPayAmount() != null ? price.toPayAmount().max(BigDecimal.ZERO) : BigDecimal.ZERO;
            b.setCurrency(currencyCode(src.currencyId()));
            b.setTotalAmount(money.convert(total, src.currencyId(), arrival));
            b.setBalanceDue(money.convert(due, src.currencyId(), arrival));

            LocalDateTime firstSeen = knownBookedAt.get(externalId);
            b.setBookedAt(firstSeen != null ? firstSeen : (modified != null ? modified : arrival.atStartOfDay()));
            BookingStatus status = status(rs);
            b.setStatus(status);
            if (status == BookingStatus.CANCELLED) {
                b.setCancelledAt(modified != null ? modified : b.getBookedAt());
                // PMS bekor qilingan yashashda ham toPayAmount beradi (real ma'lumotda kuzatildi) —
                // bu qarz emas.
                b.setBalanceDue(BigDecimal.ZERO);
            }
            result.add(b);
        }
        return result;
    }

    static BookingStatus status(ExelyPmsApi.RoomStay rs) {
        if ("Cancelled".equalsIgnoreCase(rs.status()) || "Cancelled".equalsIgnoreCase(rs.bookingStatus())) {
            return BookingStatus.CANCELLED;
        }
        if ("CheckedOut".equalsIgnoreCase(rs.status())) {
            return BookingStatus.CHECKED_OUT;
        }
        if ("CheckedIn".equalsIgnoreCase(rs.status())) {
            return BookingStatus.CHECKED_IN;
        }
        return BookingStatus.CONFIRMED;   // New (Confirmed yoki Pending)
    }

    /**
     * null — to'lov hisobga olinmaydi (bekor qilingan yoki summasi yo'q).
     * Bronga bog'lanmaydi (booking_id = null): bron qayta yozilganda (o'chirib-qo'shish)
     * FK cascade to'lovni ham o'chirib yuborardi; qarz baribir PMS'ning toPayAmount'idan olinadi.
     */
    public static Payment toPayment(ExelyPmsApi.Payment p, Long hotelId) {
        return toPayment(p, hotelId, MoneyConverter.NONE);
    }

    /** @param money to'lov valyutasi mehmonxonanikidan farq qilsa — to'lov kunidagi kurs bo'yicha o'giriladi */
    public static Payment toPayment(ExelyPmsApi.Payment p, Long hotelId, MoneyConverter money) {
        if (p.id() == null || p.amount() == null || notBlank(p.cancellationDateTime())) {
            return null;
        }
        // Belgi actionKind bo'yicha (summa doim musbat keladi): 0/4 — tushum, 1 — qaytarish (ayiriladi).
        // 2/3 — bekor qilish yozuvlari: asl yozuv cancellationDateTime bilan belgilanib, yuqorida
        // allaqachon chiqarib tashlanadi — bularni ham hisoblasak, ikki marta ayirilgan bo'lardi.
        int action = p.actionKind() == null ? 0 : p.actionKind();
        if (action == 2 || action == 3) {
            return null;
        }
        BigDecimal amount = action == 1 ? p.amount().abs().negate() : p.amount().abs();
        LocalDateTime paidAt = analyticsTime(notBlank(p.paymentDateTime()) ? p.paymentDateTime() : p.dateTime());
        if (paidAt == null) {
            return null;
        }
        Payment pay = new Payment();
        pay.setHotelId(hotelId);
        pay.setOrigin(DataOrigin.EXELY_PMS);
        pay.setExternalId("pms-pay:" + p.id());
        pay.setAmount(money.convert(amount, p.currency(), paidAt.toLocalDate()));
        pay.setMethod(method(p));
        pay.setPaidAt(paidAt);
        return pay;
    }

    /**
     * Xizmatlar hisobotini qatorlarga o'giradi. Summalar bron valyutasida keladi; Exely har bir yashash
     * uchun o'z kursini beradi (reservation.currency, currencyRate — DRR ham shu bilan hisoblanadi).
     * Kurs bo'lmasa — money (Markaziy bank kursi) bilan o'giriladi.
     */
    public static List<ServiceRevenue> toServices(ExelyPmsApi.ServicesData data, Long hotelId, String hotelCurrency,
                                                  MoneyConverter money) {
        Map<Long, ExelyPmsApi.ServiceReservation> byId = new HashMap<>();
        for (ExelyPmsApi.ServiceReservation r : data.reservations()) {
            if (r.id() != null) {
                byId.put(r.id(), r);
            }
        }
        String base = currencyCode(hotelCurrency == null ? "UZS" : hotelCurrency);
        List<ServiceRevenue> result = new ArrayList<>();
        for (ExelyPmsApi.Service s : data.services()) {
            LocalDate date = serviceDate(s.date());
            if (s.id() == null || s.amount() == null || date == null) {
                continue;
            }
            ExelyPmsApi.ServiceReservation r = s.reservationId() == null ? null : byId.get(s.reservationId());
            String currency = r == null ? null : currencyCode(r.currency());
            BigDecimal amount = s.amount();
            if (currency != null && !currency.equals(base)) {
                amount = r.currencyRate() != null && r.currencyRate().signum() > 0
                        ? amount.multiply(r.currencyRate()).setScale(2, java.math.RoundingMode.HALF_UP)
                        : money.convert(amount, currency, date);
            }
            ServiceRevenue row = new ServiceRevenue();
            row.setHotelId(hotelId);
            row.setExternalId(serviceKey(s));
            row.setServiceDate(date);
            row.setKind(s.kind() == null ? ServiceRevenue.ACCOMMODATION : s.kind());
            row.setName(s.name() == null ? null : trim(s.name(), 128));
            row.setCategory(s.optionCategory() == null || s.optionCategory().isBlank() ? null : trim(s.optionCategory(), 64));
            row.setAmount(amount);
            row.setCurrency(currency);
            row.setReservationId(s.reservationId());
            row.setBookingNumber(r == null || r.bookingNumber() == null ? null : trim(r.bookingNumber(), 64));
            result.add(row);
        }
        return result;
    }

    /**
     * Xizmat id bronlar orasida takrorlanadi (masalan, nonushta) — kalit: id + yashash + kun. Bir kunda bitta
     * yashashga bir xil id'li xizmat bir necha marta kelishi ham mumkin — ular ham alohida qator (jadvalda yagonalik talab qilinmaydi).
     */
    public static String serviceKey(ExelyPmsApi.Service s) {
        return trim(s.id() + ":" + s.reservationId() + ":" + s.date(), 160);
    }

    private static LocalDate serviceDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(yyyyMMdd.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String currencyCode(String currencyId) {
        if (currencyId == null || currencyId.isBlank()) {
            return null;
        }
        String c = currencyId.trim().toUpperCase(java.util.Locale.ROOT);
        return c.length() > 3 ? c.substring(0, 3) : c;
    }

    static String sourceName(ExelyPmsApi.Booking src) {
        if (notBlank(src.sourceChannelName())) {
            return trim(src.sourceChannelName(), 64);
        }
        if (src.source() != null && notBlank(src.source().value())) {
            return trim(src.source().value(), 64);
        }
        return "Noma'lum";
    }

    private static String method(ExelyPmsApi.Payment p) {
        if (notBlank(p.paymentSystem())) {
            return trim(p.paymentSystem(), 32);
        }
        if (p.paymentMethod() == null) {
            return null;
        }
        return p.paymentMethod() == 0 ? "Naqd" : "Elektron";
    }

    private static String guestName(ExelyPmsApi.Customer c) {
        if (c == null) {
            return null;
        }
        String n = ((c.lastName() == null ? "" : c.lastName()) + " " + (c.firstName() == null ? "" : c.firstName())).trim();
        return n.isEmpty() ? null : trim(n, 150);
    }

    private static int guests(ExelyPmsApi.GuestCount g) {
        if (g == null) {
            return 1;
        }
        int n = (g.adults() == null ? 0 : g.adults()) + (g.children() == null ? 0 : g.children());
        return Math.max(1, n);
    }

    private static LocalDate datePart(String v) {
        if (v == null || v.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "yyyyMMddHHmm" (mehmonxona vaqti). */
    static LocalDateTime analyticsTime(String v) {
        if (v == null || v.length() < 12) {
            return null;
        }
        try {
            return LocalDateTime.parse(v.substring(0, 12), ExelyPmsClient.ANALYTICS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** "2026-10-01T10:15:00Z" → mehmonxona zonasidagi vaqt. */
    static LocalDateTime toLocal(String v, ZoneId zone) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(v), zone);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(v);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
