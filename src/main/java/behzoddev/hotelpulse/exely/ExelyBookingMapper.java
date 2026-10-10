package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exely bronini bizning modelga o'giradi.
 *
 * Bitta Exely broni bir nechta xona-yashashdan (roomStay) iborat bo'lishi
 * mumkin — har biri alohida Booking qatori bo'ladi:
 * externalId = "{raqam}#{tartib}" (masalan "20210325-500360-6987835#0").
 * Narx — roomStay.total.priceAfterTax (qo'shimcha xizmatlarsiz — faqat xona daromadi).
 *
 * Read Reservation API faqat "Active"/"Cancelled" holatini beradi; faol bron
 * uchun yashash holati sanalardan aniqlanadi.
 */
public final class ExelyBookingMapper {

    /** Ma'lum kanal kodlari → o'qiladigan nom. Noma'lumlari kod bilan ko'rsatiladi. */
    private static final Map<String, String> SOURCE_TYPES = Map.of(
            "BookingEngine", "Sayt",
            "Website", "Sayt",
            "FrontDesk", "To'g'ridan-to'g'ri",
            "Pms", "To'g'ridan-to'g'ri",
            "PMS", "To'g'ridan-to'g'ri",
            "Phone", "Telefon",
            "Agent", "Agent");

    private ExelyBookingMapper() {
    }

    public static String externalPrefix(String number) {
        return number + "#";
    }

    public static List<Booking> toBookings(ExelyApi.Booking src, Long hotelId, ZoneId zone, LocalDate today) {
        List<Booking> result = new ArrayList<>();
        List<ExelyApi.RoomStay> stays = src.roomStays() == null ? List.of() : src.roomStays();
        boolean cancelled = "Cancelled".equalsIgnoreCase(src.status());
        LocalDateTime bookedAt = toLocal(src.createdDateTime(), zone);
        LocalDateTime cancelledAt = cancelled && src.cancellation() != null
                ? toLocal(src.cancellation().cancelledDateTime(), zone) : null;

        for (int i = 0; i < stays.size(); i++) {
            ExelyApi.RoomStay rs = stays.get(i);
            if (rs.stayDates() == null) {
                continue;
            }
            LocalDate arrival = datePart(rs.stayDates().arrivalDateTime());
            LocalDate departure = datePart(rs.stayDates().departureDateTime());
            if (arrival == null || departure == null) {
                continue;
            }
            if (!departure.isAfter(arrival)) {
                // Kunduzgi (bir kunlik) yashash — bitta kecha deb hisoblanadi.
                departure = arrival.plusDays(1);
            }

            Booking b = new Booking();
            b.setHotelId(hotelId);
            b.setOrigin(DataOrigin.EXELY);
            b.setExternalId(externalPrefix(src.number()) + i);
            b.setSource(sourceName(src.source()));
            b.setGuestName(guestName(src.customer()));
            b.setArrivalDate(arrival);
            b.setDepartureDate(departure);
            b.setRooms(1);
            b.setGuests(guests(rs.guestCount()));
            b.setTotalAmount(price(rs.total()));
            b.setBookedAt(bookedAt != null ? bookedAt : arrival.atStartOfDay());
            if (cancelled) {
                b.setStatus(BookingStatus.CANCELLED);
                b.setCancelledAt(cancelledAt != null ? cancelledAt : b.getBookedAt());
            } else {
                b.setStatus(statusByDates(arrival, departure, today));
            }
            result.add(b);
        }
        return result;
    }

    /** Oldindan to'langan summa (Read Reservation API faqat shuni beradi). */
    public static BigDecimal prepaid(ExelyApi.Booking src) {
        if (src.guaranteeInfo() == null || src.guaranteeInfo().totalPrepaid() == null) {
            return BigDecimal.ZERO;
        }
        return src.guaranteeInfo().totalPrepaid();
    }

    static BookingStatus statusByDates(LocalDate arrival, LocalDate departure, LocalDate today) {
        if (!departure.isAfter(today)) {
            return BookingStatus.CHECKED_OUT;
        }
        if (!arrival.isAfter(today)) {
            return BookingStatus.CHECKED_IN;
        }
        return BookingStatus.CONFIRMED;
    }

    static String sourceName(ExelyApi.Source source) {
        if (source == null || source.type() == null) {
            return "Номаълум";
        }
        if ("Channel".equalsIgnoreCase(source.type())) {
            return source.code() == null || source.code().isBlank() ? "Kanal" : "Kanal " + source.code();
        }
        return SOURCE_TYPES.getOrDefault(source.type(), source.type());
    }

    private static String guestName(ExelyApi.Customer c) {
        if (c == null) {
            return null;
        }
        String name = ((c.lastName() == null ? "" : c.lastName()) + " " + (c.firstName() == null ? "" : c.firstName())).trim();
        if (name.isEmpty()) {
            return null;
        }
        return name.length() > 150 ? name.substring(0, 150) : name;
    }

    private static int guests(ExelyApi.GuestCount g) {
        if (g == null) {
            return 1;
        }
        int adults = g.adultCount() == null ? 0 : g.adultCount();
        int children = g.childAges() == null ? 0 : g.childAges().size();
        return Math.max(1, adults + children);
    }

    private static BigDecimal price(ExelyApi.Total t) {
        if (t == null) {
            return BigDecimal.ZERO;
        }
        if (t.priceAfterTax() != null) {
            return t.priceAfterTax();
        }
        return t.priceBeforeTax() != null ? t.priceBeforeTax() : BigDecimal.ZERO;
    }

    /** "2021-03-25T14:00" → 2021-03-25 */
    private static LocalDate datePart(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** UTC vaqt ("2021-03-19T15:18:23Z") → mehmonxona zonasidagi mahalliy vaqt. */
    static LocalDateTime toLocal(String utc, ZoneId zone) {
        if (utc == null || utc.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(utc), zone);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(utc);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
