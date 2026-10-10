package behzoddev.hotelpulse.report;

import behzoddev.hotelpulse.faktura.FakturaService;
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
import java.util.Set;
import java.util.TreeSet;

/**
 * USALI xarajatlari: oy bo'yicha saqlash va davr bo'yicha yig'ish. Ruxsatni chaqiruvchi (controller) tekshiradi.
 * Davr yig'indisi = qo'lda kiritilgan + Faktura.uz'dagi imzolangan kiruvchi hisob-fakturalar (QQSsiz; faqat UZS
 * valyutasidagi mehmonxonalar uchun — Faktura.uz summalari so'mda).
 */
@Service
@RequiredArgsConstructor
public class UsaliExpenseService {

    public static final String MANUAL = "MANUAL";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final FakturaService faktura;

    /** Bir oy: qo'lda kiritilgan summalar, modda → summa (kiritilmagan moddalar yo'q). */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> month(Long hotelId, YearMonth month) {
        return manual(hotelId, month, month);
    }

    /** Bir oy: Faktura.uz'dan kelgan summalar (UZS bo'lmasa — bo'sh). */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> fakturaMonth(Long hotelId, YearMonth month) {
        return uzs(hotelId) ? faktura.expenses(hotelId, month, month) : new EnumMap<>(UsaliLine.class);
    }

    /** [from, to] oylar yig'indisi: qo'lda + Faktura.uz. */
    @Transactional(readOnly = true)
    public Map<UsaliLine, BigDecimal> sum(Long hotelId, YearMonth from, YearMonth to) {
        Map<UsaliLine, BigDecimal> result = manual(hotelId, from, to);
        if (uzs(hotelId)) {
            faktura.expenses(hotelId, from, to).forEach((l, v) -> result.merge(l, v, BigDecimal::add));
        }
        return result;
    }

    public boolean uzs(Long hotelId) {
        return "UZS".equals(jdbc.queryForList("select currency from hotels where id = ?", String.class, hotelId)
                .stream().findFirst().orElse(null));
    }

    private Map<UsaliLine, BigDecimal> manual(Long hotelId, YearMonth from, YearMonth to) {
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
        Set<YearMonth> months = new TreeSet<>();
        jdbc.query("select distinct month from usali_expenses where hotel_id = ? and month >= ? and month <= ?",
                rs -> {
                    months.add(YearMonth.from(rs.getDate(1).toLocalDate()));
                }, hotelId, Date.valueOf(from.atDay(1)), Date.valueOf(to.atDay(1)));
        if (uzs(hotelId)) {
            months.addAll(faktura.expenseMonths(hotelId, from, to));
        }
        return months.size();
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
