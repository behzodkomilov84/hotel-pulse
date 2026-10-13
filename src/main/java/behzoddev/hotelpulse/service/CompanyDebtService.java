package behzoddev.hotelpulse.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Korxona va guruh hisoblari qarzi (UCS Shelter: to'lovchi korxona folio'lari) — mehmon (yashash) qarzidan alohida.
 * Ma'lumot Shelter importi bilan har safar to'liq yangilanadi.
 */
@Service
@RequiredArgsConstructor
public class CompanyDebtService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    /**
     * @param openedOn hisob ochilgan sana
     * @param services hisobga yozilgan xizmatlar, paid — to'lovlar (services − paid = amount)
     * @param from     yashashlar davri (birinchi kelish), to — oxirgi ketish
     * @param stays    hisobga yozilgan yashashlar (xona, mehmon, sanalar, summa)
     */
    public record CompanyDebt(long id, String name, BigDecimal amount, LocalDate openedOn, BigDecimal services,
                              BigDecimal paid, LocalDate from, LocalDate to, int rooms, int guests, List<Stay> stays) {
    }

    /** room null — yashashga bog'lanmagan xizmatlar. */
    public record Stay(String room, String guest, LocalDate arrival, LocalDate departure, int guests, BigDecimal amount) {
    }

    public record Total(BigDecimal amount, long count) {
    }

    @Transactional(readOnly = true)
    public List<CompanyDebt> list(long hotelId) {
        return jdbc.query("""
                        select id, name, amount, opened_on, services, paid, period_from, period_to, rooms, guests, stays_json
                        from company_debts where hotel_id = ? order by amount desc""",
                (rs, i) -> new CompanyDebt(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), date(rs.getDate(4)),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), date(rs.getDate(7)), date(rs.getDate(8)),
                        rs.getInt(9), rs.getInt(10), stays(rs.getString(11))), hotelId);
    }

    @Transactional(readOnly = true)
    public Total total(long hotelId) {
        return jdbc.query("select coalesce(sum(amount), 0), count(*) from company_debts where hotel_id = ?",
                rs -> {
                    rs.next();
                    return new Total(rs.getBigDecimal(1), rs.getLong(2));
                }, hotelId);
    }

    private static List<Stay> stays(String json) {
        List<Stay> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        for (JsonNode n : JSON.readTree(json)) {
            result.add(new Stay(text(n, "room"), text(n, "guest"), day(n, "arrival"), day(n, "departure"),
                    n.path("guests").asInt(), n.path("amount").decimalValue()));
        }
        return result;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static LocalDate day(JsonNode n, String f) {
        String v = text(n, f);
        return v == null ? null : LocalDate.parse(v);
    }

    private static LocalDate date(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
