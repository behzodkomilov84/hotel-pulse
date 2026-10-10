package behzoddev.hotelpulse.report;

import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.exely.ExelyPmsMapper;
import behzoddev.hotelpulse.kpi.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hisobotlar uchun ma'lumotni to'g'ridan-to'g'ri SQL bilan yuklaydi (tez, faqat kerakli ustunlar). */
@Component
@RequiredArgsConstructor
public class ReportData {

    private final JdbcTemplate jdbc;

    /** Bitta yashash (roomStay). */
    public record Stay(String externalId, String source, BookingStatus status, String guest, LocalDate arrival,
                       LocalDate departure, int guests, BigDecimal total, BigDecimal balance, LocalDateTime bookedAt,
                       LocalDateTime cancelledAt, String roomTypeId, String roomId, BigDecimal commission) {

        public String number() {
            return ExelyPmsMapper.bookingNumber(externalId);
        }

        public long nights() {
            return Math.max(1, ChronoUnit.DAYS.between(arrival, departure));
        }

        public BigDecimal nightly() {
            return total.divide(BigDecimal.valueOf(nights()), 2, RoundingMode.HALF_UP);
        }

        /** Davrga tushadigan kechalar soni. */
        public long nightsIn(Period p) {
            LocalDate start = arrival.isBefore(p.from()) ? p.from() : arrival;
            LocalDate lastNight = departure.minusDays(1);
            LocalDate end = lastNight.isAfter(p.to()) ? p.to() : lastNight;
            return end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
        }

        /** Davrga tushadigan daromad (bron narxi kechalarga teng bo'lingan). */
        public BigDecimal revenueIn(Period p) {
            return nightly().multiply(BigDecimal.valueOf(nightsIn(p)));
        }

        public boolean active() {
            return status.isActive();
        }

        public boolean inHouseOn(LocalDate d) {
            return active() && !arrival.isAfter(d) && departure.isAfter(d);
        }
    }

    private static final String STAY_COLUMNS = """
            select external_id, source, status, guest_name, arrival_date, departure_date, guests, total_amount,
                   balance_due, booked_at, cancelled_at, room_type_id, room_id, agent_commission
            from bookings where hotel_id = ?
            """;

    private List<Stay> stays(String where, Object... args) {
        return jdbc.query(STAY_COLUMNS + " and " + where, (rs, i) -> {
            Timestamp booked = rs.getTimestamp(10);
            Timestamp cancelled = rs.getTimestamp(11);
            BigDecimal balance = rs.getBigDecimal(9);
            return new Stay(rs.getString(1), rs.getString(2), BookingStatus.valueOf(rs.getString(3)), rs.getString(4),
                    rs.getDate(5).toLocalDate(), rs.getDate(6).toLocalDate(), rs.getInt(7), rs.getBigDecimal(8),
                    balance == null ? BigDecimal.ZERO : balance, booked == null ? null : booked.toLocalDateTime(),
                    cancelled == null ? null : cancelled.toLocalDateTime(), rs.getString(12), rs.getString(13),
                    rs.getBigDecimal(14));
        }, args);
    }

    /** Davrga tegadigan yashashlar (har qanday holat). */
    public List<Stay> overlapping(Long hotelId, Period p) {
        return stays("arrival_date <= ? and departure_date > ?", hotelId, Date.valueOf(p.to()), Date.valueOf(p.from()));
    }

    /** Kelish sanasi davrda bo'lgan yashashlar (har qanday holat). */
    public List<Stay> arrivingIn(Long hotelId, Period p) {
        return stays("arrival_date >= ? and arrival_date <= ?", hotelId, Date.valueOf(p.from()), Date.valueOf(p.to()));
    }

    /** Ketish sanasi davrda bo'lgan yashashlar. */
    public List<Stay> departingIn(Long hotelId, Period p) {
        return stays("departure_date >= ? and departure_date <= ?", hotelId, Date.valueOf(p.from()), Date.valueOf(p.to()));
    }

    /** Bron qilingan vaqti davrda. */
    public List<Stay> bookedIn(Long hotelId, Period p) {
        return stays("booked_at >= ? and booked_at < ?", hotelId,
                Timestamp.valueOf(p.from().atStartOfDay()), Timestamp.valueOf(p.toExclusive().atStartOfDay()));
    }

    // ---------------------------------------------------------------- Xonalar

    /** Exely xona turlari: id → nomi. */
    public Map<String, String> roomTypes(Long hotelId) {
        return names(hotelId, "room_type");
    }

    /** Exely xonalari: id → raqami/nomi. */
    public Map<String, String> rooms(Long hotelId) {
        return names(hotelId, "room");
    }

    private Map<String, String> names(Long hotelId, String kind) {
        Map<String, String> map = new HashMap<>();
        jdbc.query("select external_id, json_unquote(json_extract(payload, '$.name')) from exely_raw where hotel_id = ? and kind = ?",
                rs -> {
                    map.put(rs.getString(1), rs.getString(2));
                }, hotelId, kind);
        return map;
    }

    /** Xona turi bo'yicha xonalar soni (sotuvga mavjud). */
    public Map<String, Integer> roomsPerType(Long hotelId) {
        Map<String, Integer> map = new HashMap<>();
        jdbc.query("""
                select json_unquote(json_extract(payload, '$.roomTypeId')), count(*)
                from exely_raw where hotel_id = ? and kind = 'room' group by 1
                """, rs -> {
            map.put(rs.getString(1), rs.getInt(2));
        }, hotelId);
        return map;
    }

    // ---------------------------------------------------------------- To'lovlar

    /**
     * @param bookingNumber PMS to'lovi bo'lsa — bron raqami (xom Exely ma'lumotidan)
     * @param username      to'lovni qabul qilgan Exely foydalanuvchisi
     * @param refund        qaytarish (Exely actionKind = 1)
     */
    public record PaymentRow(LocalDateTime paidAt, BigDecimal amount, String method, String bookingNumber,
                             String username, boolean refund) {
    }

    public List<PaymentRow> payments(Long hotelId, Period p) {
        return jdbc.query("""
                select p.paid_at, p.amount, p.method,
                       json_unquote(json_extract(r.payload, '$.bookingNumber')),
                       json_unquote(json_extract(r.payload, '$.username')),
                       json_extract(r.payload, '$.actionKind')
                from payments p
                left join exely_raw r on r.hotel_id = p.hotel_id and r.kind = 'payment'
                                     and concat('pms-pay:', r.external_id) = p.external_id
                where p.hotel_id = ? and p.paid_at >= ? and p.paid_at < ?
                order by p.paid_at
                """, (rs, i) -> new PaymentRow(rs.getTimestamp(1).toLocalDateTime(), rs.getBigDecimal(2), rs.getString(3),
                rs.getString(4), rs.getString(5), "1".equals(rs.getString(6))),
                hotelId, Timestamp.valueOf(p.from().atStartOfDay()), Timestamp.valueOf(p.toExclusive().atStartOfDay()));
    }

    // ---------------------------------------------------------------- Xizmatlar

    /** Qo'shimcha xizmat (yashashdan tashqari) qatori: sana, nomi, toifasi, summa. */
    public record ServiceRow(LocalDate date, String name, String category, BigDecimal amount) {
        public boolean meals() {
            return "Meals".equals(category) || "Food service".equals(category);
        }
    }

    public List<ServiceRow> extraServices(Long hotelId, Period p) {
        return jdbc.query("""
                select service_date, name, category, amount from service_revenue
                where hotel_id = ? and service_date >= ? and service_date <= ? and kind not in (0, 3, 4)
                order by service_date, name
                """, (rs, i) -> new ServiceRow(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)),
                hotelId, Date.valueOf(p.from()), Date.valueOf(p.to()));
    }

    // ---------------------------------------------------------------- Talab suratlari

    /** stay_date → sotilgan xonalar, berilgan suratdagi holat. */
    public Map<LocalDate, Integer> snapshot(Long hotelId, LocalDate snapshotDate) {
        Map<LocalDate, Integer> map = new HashMap<>();
        jdbc.query("select stay_date, rooms_sold from otb_snapshots where hotel_id = ? and snapshot_date = ?",
                rs -> {
                    map.put(rs.getDate(1).toLocalDate(), rs.getInt(2));
                }, hotelId, Date.valueOf(snapshotDate));
        return map;
    }

    public boolean hasSnapshot(Long hotelId, LocalDate snapshotDate) {
        Integer n = jdbc.queryForObject("select count(*) from otb_snapshots where hotel_id = ? and snapshot_date = ?",
                Integer.class, hotelId, Date.valueOf(snapshotDate));
        return n != null && n > 0;
    }

    public void saveSnapshot(Long hotelId, LocalDate snapshotDate, Map<LocalDate, Integer> sold) {
        List<Object[]> args = new ArrayList<>();
        sold.forEach((d, n) -> args.add(new Object[]{hotelId, Date.valueOf(snapshotDate), Date.valueOf(d), n}));
        jdbc.batchUpdate("insert ignore into otb_snapshots (hotel_id, snapshot_date, stay_date, rooms_sold) values (?, ?, ?, ?)", args);
    }
}
