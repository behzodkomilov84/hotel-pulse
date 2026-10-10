package behzoddev.hotelpulse.report;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

/** USALI xarajatlari: oy bo'yicha saqlash va davr bo'yicha yig'ish. Ruxsatni chaqiruvchi (controller) tekshiradi. */
@Service
@RequiredArgsConstructor
public class UsaliExpenseService {

    public static final String MANUAL = "MANUAL";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Bir oy: modda → summa (kiritilmagan moddalar yo'q). */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> month(Long hotelId, YearMonth month) {
        return sum(hotelId, month, month);
    }

    /** [from, to] oylar yig'indisi. */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> sum(Long hotelId, YearMonth from, YearMonth to) {
        Map<UsaliLine, BigDecimal> result = new EnumMap<>(UsaliLine.class);
        jdbc.query("select line, sum(amount) from usali_expenses where hotel_id = ? and month >= ? and month <= ? group by line",
                rs -> {
                    try {
                        result.put(UsaliLine.valueOf(rs.getString(1)), rs.getBigDecimal(2));
                    } catch (IllegalArgumentException ignored) {
                        // eski / noma'lum modda
                    }
                }, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atDay(1)));
        return result;
    }

    /** Nechta oy uchun xarajat kiritilgan ([from, to] ichida). */
    @Transactional(readOnly = true)
    public int monthsWithData(Long hotelId, YearMonth from, YearMonth to) {
        Integer n = jdbc.queryForObject("select count(distinct month) from usali_expenses where hotel_id = ? and month >= ? and month <= ?",
                Integer.class, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atDay(1)));
        return n == null ? 0 : n;
    }

    /** Oy xarajatlarini saqlaydi: bo'sh/0 — o'chiriladi. */
    @Transactional
    public void save(Long hotelId, YearMonth month, Map<UsaliLine, BigDecimal> values, Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Date m = Date.valueOf(month.atDay(1));
        for (UsaliLine line : UsaliLine.values()) {
            BigDecimal v = values.get(line);
            if (v == null || v.signum() == 0) {
                jdbc.update("delete from usali_expenses where hotel_id = ? and month = ? and line = ?", hotelId, m, line.name());
            } else {
                if (v.signum() < 0) {
                    throw new IllegalArgumentException(line.getLabel() + ": summa manfiy bo'lmasin");
                }
                jdbc.update("""
                        insert into usali_expenses (hotel_id, month, line, amount, source, updated_by, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on duplicate key update amount = values(amount), source = values(source),
                                                updated_by = values(updated_by), updated_at = values(updated_at)
                        """, hotelId, m, line.name(), v, MANUAL, userId, now);
            }
        }
    }

    public static YearMonth monthOf(LocalDate d) {
        return YearMonth.from(d);
    }
}
