package behzoddev.hotelpulse.exely;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exely PMS'dan kelgan ma'lumotlarning xom (o'zgartirilmagan JSON) arxivi — exely_raw jadvali.
 * KPI'lar o'z jadvallaridan (bookings, payments, service_revenue) hisoblanadi; bu yerda esa keyingi
 * har qanday tahlil uchun Exely bergan hamma narsa saqlanadi.
 */
@Component
@RequiredArgsConstructor
public class ExelyRawStore {

    public static final String BOOKING = "booking";
    public static final String INVOICES = "invoices";
    public static final String GUEST = "guest";
    public static final String PAYMENT = "payment";
    public static final String SERVICE = "service";
    public static final String SERVICE_CANCELLED = "service_cancelled";
    public static final String RESERVATION = "reservation";
    public static final String CUSTOMER = "customer";
    public static final String AGENT = "agent";
    public static final String ROOM_TYPE = "room_type";
    public static final String ROOM = "room";
    public static final String COMPANY = "company";

    private static final String UPSERT = """
            insert into exely_raw (hotel_id, kind, external_id, booking_number, ref_date, payload, fetched_at)
            values (?, ?, ?, ?, ?, ?, ?)
            on duplicate key update booking_number = values(booking_number), ref_date = values(ref_date),
                                    payload = values(payload), fetched_at = values(fetched_at)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** @param bookingNumber, refDate — ixtiyoriy (null bo'lishi mumkin) */
    public record Row(String externalId, String bookingNumber, LocalDate refDate, String json) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(Long hotelId, String kind, Row row) {
        upsertAll(hotelId, kind, List.of(row));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertAll(Long hotelId, String kind, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        jdbc.batchUpdate(UPSERT, rows, 200, (ps, r) -> {
            ps.setLong(1, hotelId);
            ps.setString(2, kind);
            ps.setString(3, trim(r.externalId(), 160));
            ps.setString(4, r.bookingNumber() == null ? null : trim(r.bookingNumber(), 64));
            ps.setDate(5, r.refDate() == null ? null : Date.valueOf(r.refDate()));
            ps.setString(6, r.json());
            ps.setTimestamp(7, now);
        });
    }

    /** [from, to] kunlaridagi shu turdagi yozuvlarni to'liq almashtiradi (Exely'da o'chirilganlari ham ketadi). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceWindow(Long hotelId, String kind, LocalDate from, LocalDate to, List<Row> rows) {
        jdbc.update("delete from exely_raw where hotel_id = ? and kind = ? and ref_date >= ? and ref_date <= ?",
                hotelId, kind, Date.valueOf(from), Date.valueOf(to));
        upsertAll(hotelId, kind, rows);
    }

    /** Shu turdagi barcha yozuvlarni almashtiradi (masalan, xonalar ro'yxati). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceAll(Long hotelId, String kind, List<Row> rows) {
        jdbc.update("delete from exely_raw where hotel_id = ? and kind = ?", hotelId, kind);
        upsertAll(hotelId, kind, rows);
    }

    /** Tur bo'yicha yozuvlar soni (admin sahifasi uchun). */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(Long hotelId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("select kind, count(*) from exely_raw where hotel_id = ? group by kind order by kind",
                rs -> {
                    result.put(rs.getString(1), rs.getLong(2));
                }, hotelId);
        return result;
    }

    /** Shu turdagi yozuvlarning kalitlari (masalan, saqlangan barcha bron raqamlari). */
    @Transactional(readOnly = true)
    public java.util.Set<String> externalIds(Long hotelId, String kind) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "select external_id from exely_raw where hotel_id = ? and kind = ?", String.class, hotelId, kind));
    }

    /** Saqlangan bronlardagi yashashlar (roomStays) soni — bookings jadvali bilan solishtirish uchun. */
    @Transactional(readOnly = true)
    public long bookingRoomStays(Long hotelId) {
        Long n = jdbc.queryForObject("""
                select coalesce(sum(json_length(payload, '$.roomStays')), 0) from exely_raw
                where hotel_id = ? and kind = 'booking'
                """, Long.class, hotelId);
        return n == null ? 0 : n;
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
