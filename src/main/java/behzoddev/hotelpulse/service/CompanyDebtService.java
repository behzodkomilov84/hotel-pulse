package behzoddev.hotelpulse.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Korxona va guruh hisoblari qarzi (UCS Shelter: to'lovchi korxona folio'lari) — mehmon (yashash) qarzidan alohida.
 * Ma'lumot Shelter importi bilan har safar to'liq yangilanadi.
 */
@Service
@RequiredArgsConstructor
public class CompanyDebtService {

    private final JdbcTemplate jdbc;

    /** @param openedOn hisob ochilgan sana (guruh kelishi atrofida) */
    public record CompanyDebt(String name, BigDecimal amount, LocalDate openedOn) {
    }

    public record Total(BigDecimal amount, long count) {
    }

    @Transactional(readOnly = true)
    public List<CompanyDebt> list(long hotelId) {
        return jdbc.query("select name, amount, opened_on from company_debts where hotel_id = ? order by amount desc",
                (rs, i) -> new CompanyDebt(rs.getString(1), rs.getBigDecimal(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate()), hotelId);
    }

    @Transactional(readOnly = true)
    public Total total(long hotelId) {
        return jdbc.query("select coalesce(sum(amount), 0), count(*) from company_debts where hotel_id = ?",
                rs -> {
                    rs.next();
                    return new Total(rs.getBigDecimal(1), rs.getLong(2));
                }, hotelId);
    }
}
