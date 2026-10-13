package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.exely.ExelyVerifyService.Check;
import behzoddev.hotelpulse.exely.ExelyVerifyService.Report;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static behzoddev.hotelpulse.agent.ShelterMapper.amount;
import static behzoddev.hotelpulse.agent.ShelterMapper.d;
import static behzoddev.hotelpulse.agent.ShelterMapper.l;

/**
 * "Shelter bilan solishtirish": agent yuborgan xom jadvallardan Shelter'ning o'z yig'indilarini
 * (FOLIO.SERVICEBALANCE / PAYMENTBALANCE / BALANCE, FOLIO_CONTENTS qatorlari) qaytadan hisoblab,
 * saytdagi bronlar, daromad, to'lovlar va qarzdorlik bilan solishtiradi. Har importdan keyin ishlaydi; faqat o'qiydi.
 * Natija Exely solishtirishi bilan bir xil ko'rinishda mehmonxona yozuviga saqlanadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShelterVerifyService {

    private static final int MAX_DETAILS = 30;
    /** Yaxlitlash farqi (so'm). */
    private static final BigDecimal TOLERANCE = BigDecimal.valueOf(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AgentService agents;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** FOLIO_CONTENTS'dan bir folio bo'yicha yig'indilar (turli usullarda — qaysi biri Shelter'ga mos kelishini ko'rish uchun). */
    private static final class Sums {
        BigDecimal services = BigDecimal.ZERO;            // COSTALL, bekor qilinganlar bilan
        BigDecimal servicesCost = BigDecimal.ZERO;        // COST (chegirmagacha)
        BigDecimal servicesNoCancel = BigDecimal.ZERO;    // COSTALL, CANCELED=1 siz
        BigDecimal payments = BigDecimal.ZERO;
        BigDecimal paymentsNoCancel = BigDecimal.ZERO;
    }

    public Report verify(long hotelId) {
        LocalDateTime now = LocalDateTime.now(clock).withSecond(0).withNano(0);
        Report report;
        try {
            Map<Long, Integer> categories = new HashMap<>();
            agents.forEachRow(hotelId, "OPERATIONS", line -> {
                JsonNode n = JSON.readTree(line);
                categories.put(l(n, "ID"), (int) l(n, "CATEGORY"));
            });

            // Xom FOLIO_CONTENTS: folio bo'yicha (ichki yaxlitlik) va oy bo'yicha (daromad, to'lovlar).
            Map<Long, Sums> byFolio = new HashMap<>();
            Map<YearMonth, BigDecimal> rawRevenue = new TreeMap<>();
            Map<YearMonth, BigDecimal> rawPayments = new TreeMap<>();
            agents.forEachRow(hotelId, "FOLIO_CONTENTS", line -> {
                JsonNode fc = JSON.readTree(line);
                if (l(fc, "ENABLED") != 1) {
                    return;
                }
                boolean service = l(fc, "ISSERVICE") == 1;
                boolean cancelled = l(fc, "CANCELED") == 1;
                BigDecimal v = amount(fc);
                Sums s = byFolio.computeIfAbsent(l(fc, "FOLIO"), k -> new Sums());
                if (service) {
                    s.services = s.services.add(v);
                    s.servicesCost = s.servicesCost.add(d(fc, "COST"));
                    if (!cancelled) {
                        s.servicesNoCancel = s.servicesNoCancel.add(v);
                    }
                } else {
                    s.payments = s.payments.add(v);
                    if (!cancelled) {
                        s.paymentsNoCancel = s.paymentsNoCancel.add(v);
                    }
                }
                if (ShelterMapper.TRANSFER_OPERATIONS.contains(l(fc, "OPERATION"))) {
                    return;
                }
                if (service) {
                    if (ShelterMapper.TAX_CATEGORIES.contains(categories.getOrDefault(l(fc, "OPERATION"), 0))) {
                        return;
                    }
                    LocalDate day = ShelterMapper.date(fc, "SHIFTDATE");
                    if (day != null) {
                        rawRevenue.merge(YearMonth.from(day), v, BigDecimal::add);
                    }
                } else {
                    LocalDateTime at = ShelterMapper.dateTime(fc, "CREATIONDATE");
                    if (at != null) {
                        rawPayments.merge(YearMonth.from(at), v, BigDecimal::add);
                    }
                }
            });

            List<JsonNode> folios = new ArrayList<>();
            agents.forEachRow(hotelId, "FOLIO", line -> {
                JsonNode f = JSON.readTree(line);
                folios.add(ShelterImportService.project(f, Set.of("ID", "KIND", "BALANCE", "SERVICEBALANCE", "PAYMENTBALANCE")));
            });

            List<Check> checks = new ArrayList<>();
            checks.add(integrity(folios, byFolio));
            checks.add(monthly("Даромад (ойлар бўйича)", rawRevenue, ours("""
                    select date_format(service_date, '%Y-%m'), sum(amount) from service_revenue
                    where hotel_id = ? and external_id like 'sh:%' group by 1""", hotelId)));
            checks.add(monthly("Тўловлар (ойлар бўйича)", rawPayments, ours("""
                    select date_format(paid_at, '%Y-%m'), sum(amount) from payments
                    where hotel_id = ? and origin = 'SHELTER' group by 1""", hotelId)));
            checks.add(debts(hotelId, folios));
            checks.add(stays(hotelId));
            report = new Report(now, checks.stream().allMatch(Check::ok), checks, null);
        } catch (RuntimeException e) {
            log.warn("Shelter solishtirish (mehmonxona {}) xatosi", hotelId, e);
            report = new Report(now, false, List.of(), "Солиштиришда хато: " + e.getClass().getSimpleName());
        }
        jdbc.update("update hotels set exely_verified_at = ?, exely_verify_ok = ?, exely_verify_report = ? where id = ?",
                Timestamp.valueOf(report.at()), report.ok(), JSON.writeValueAsString(report), hotelId);
        return report;
    }

    /** Shelter ichida: har folio'ning SERVICEBALANCE/PAYMENTBALANCE'i uning qatorlari yig'indisiga tengmi. */
    private Check integrity(List<JsonNode> folios, Map<Long, Sums> byFolio) {
        boolean hasBalances = folios.stream().anyMatch(f -> f.has("SERVICEBALANCE") && f.has("PAYMENTBALANCE"));
        if (!hasBalances) {
            return new Check("Shelter ички яхлитлиги", true, "FOLIO'да SERVICEBALANCE/PAYMENTBALANCE йўқ — текширилмади", List.of());
        }
        int badMain = 0, badCost = 0, badNoCancel = 0, badPay = 0, badPayNoCancel = 0, badBalance = 0;
        List<String> details = new ArrayList<>();
        BigDecimal shelterServices = BigDecimal.ZERO, rowServices = BigDecimal.ZERO;
        for (JsonNode f : folios) {
            Sums s = byFolio.getOrDefault(l(f, "ID"), new Sums());
            BigDecimal sb = d(f, "SERVICEBALANCE"), pb = d(f, "PAYMENTBALANCE"), bal = d(f, "BALANCE");
            shelterServices = shelterServices.add(sb);
            rowServices = rowServices.add(s.services);
            if (differs(sb, s.services)) {
                badMain++;
                if (details.size() < MAX_DETAILS) {
                    details.add("Фолио " + l(f, "ID") + ": хизматлар Shelter " + money(sb) + ", қаторлар " + money(s.services));
                }
            }
            if (differs(sb, s.servicesCost)) {
                badCost++;
            }
            if (differs(sb, s.servicesNoCancel)) {
                badNoCancel++;
            }
            if (differs(pb, s.payments)) {
                badPay++;
                if (details.size() < MAX_DETAILS) {
                    details.add("Фолио " + l(f, "ID") + ": тўловлар Shelter " + money(pb) + ", қаторлар " + money(s.payments));
                }
            }
            if (differs(pb, s.paymentsNoCancel)) {
                badPayNoCancel++;
            }
            if (differs(bal, pb.subtract(sb))) {
                badBalance++;
            }
        }
        boolean ok = badMain == 0 && badPay == 0;
        String summary = folios.size() + " та фолио; хизматлар Shelter " + money(shelterServices) + ", қаторлар " + money(rowServices)
                + (ok ? " — мос" : "; фарқ: хизмат " + badMain + ", тўлов " + badPay + " фолиода")
                + " (таққослаш: COST билан " + badCost + ", бекорларсиз " + badNoCancel + "/" + badPayNoCancel
                + ", қолдиқ≠тўлов−хизмат " + badBalance + ")";
        return new Check("Shelter ички яхлитлиги", ok, summary, details);
    }

    private Check monthly(String name, Map<YearMonth, BigDecimal> shelter, Map<YearMonth, BigDecimal> site) {
        TreeSet<YearMonth> months = new TreeSet<>(shelter.keySet());
        months.addAll(site.keySet());
        List<String> details = new ArrayList<>();
        BigDecimal a = BigDecimal.ZERO, b = BigDecimal.ZERO;
        int bad = 0;
        for (YearMonth m : months) {
            BigDecimal x = shelter.getOrDefault(m, BigDecimal.ZERO), y = site.getOrDefault(m, BigDecimal.ZERO);
            a = a.add(x);
            b = b.add(y);
            if (differs(x, y)) {
                bad++;
                if (details.size() < MAX_DETAILS) {
                    details.add(m + ": Shelter " + money(x) + ", сайтда " + money(y));
                }
            }
        }
        boolean ok = bad == 0;
        return new Check(name, ok, "Shelter " + money(a) + ", сайтда " + money(b) + (ok ? ", ҳар ой мос" : ", " + bad + " ойда фарқ"), details);
    }

    /** Qarz: Shelter'dagi manfiy qoldiqlar = saytdagi bron qarzlari + korxona hisoblari. */
    private Check debts(long hotelId, List<JsonNode> folios) {
        BigDecimal company = BigDecimal.ZERO, guest = BigDecimal.ZERO;
        int companyCount = 0, guestCount = 0;
        for (JsonNode f : folios) {
            BigDecimal bal = d(f, "BALANCE");
            if (bal.signum() >= 0) {
                continue;
            }
            if (l(f, "KIND") == 1) {
                company = company.add(bal.negate());
                companyCount++;
            } else {
                guest = guest.add(bal.negate());
                guestCount++;
            }
        }
        BigDecimal siteCompany = sum("select coalesce(sum(amount), 0) from company_debts where hotel_id = ?", hotelId);
        BigDecimal siteGuest = sum("""
                select coalesce(sum(balance_due), 0) from bookings
                where hotel_id = ? and origin = 'SHELTER' and balance_due > 0 and status <> 'CANCELLED'""", hotelId);
        List<String> details = new ArrayList<>();
        boolean ok = !differs(company, siteCompany) && !differs(guest, siteGuest);
        if (differs(company, siteCompany)) {
            details.add("Корхона ҳисоблари: Shelter " + money(company) + " (" + companyCount + " та), сайтда " + money(siteCompany));
        }
        if (differs(guest, siteGuest)) {
            details.add("Меҳмон ҳисоблари: Shelter " + money(guest) + " (" + guestCount + " та фолио), сайтда " + money(siteGuest)
                    + " — фарқ брон билан боғланмаган ёки бекор қилинган брондаги фолиоларда");
        }
        return new Check("Қарздорлик", ok, "Shelter " + money(company.add(guest)) + ", сайтда " + money(siteCompany.add(siteGuest))
                + (ok ? " — мос" : ""), details);
    }

    /**
     * Bronlar: xom ROOM_SCHEDULE'dagi yashash yozuvlari (xona almashtirish nusxalarisiz) holatlar bo'yicha = saytdagi bronlar.
     * Bir folio bir nechta bronga tegishli bo'lsa — qarz ikki marta sanalmasligi ham tekshiriladi.
     */
    private Check stays(long hotelId) {
        Map<String, Long> latest = new HashMap<>();
        Map<Long, int[]> stateById = new HashMap<>();   // ID → [STATE, bekor]
        Map<Long, Long> folioById = new HashMap<>();
        agents.forEachRow(hotelId, "ROOM_SCHEDULE", line -> {
            JsonNode r = JSON.readTree(line);
            int state = (int) l(r, "STATE");
            if (l(r, "ENABLED") != 1 || !(state == 50 || state == 52 || state == 300 || state == 305 || state == 400)
                    || ShelterMapper.date(r, "ARRIVAL") == null || ShelterMapper.date(r, "DEPARTURE") == null) {
                return;
            }
            long parent = l(r, "IDPAR") > 0 ? l(r, "IDPAR") : l(r, "ID");
            String key = parent + "|" + l(r, "FOLIO") + "|" + ShelterMapper.date(r, "ARRIVAL") + "|" + ShelterMapper.date(r, "DEPARTURE");
            latest.merge(key, l(r, "ID"), Math::max);
            stateById.put(l(r, "ID"), new int[]{state, l(r, "QUANTCANCEL") > 0 ? 1 : 0});
            folioById.put(l(r, "ID"), l(r, "FOLIO"));
        });
        Map<String, Long> shelter = new TreeMap<>();
        Map<Long, Integer> staysPerFolio = new HashMap<>();
        for (long id : latest.values()) {
            int[] s = stateById.get(id);
            String group = switch (s[0]) {
                case 300 -> "CHECKED_IN";
                case 400 -> "CHECKED_OUT";
                case 305 -> "CANCELLED";
                default -> s[1] == 1 ? "CANCELLED" : "RESERVED";
            };
            shelter.merge(group, 1L, Long::sum);
            long folio = folioById.get(id);
            if (folio > 0) {
                staysPerFolio.merge(folio, 1, Integer::sum);
            }
        }
        Map<String, Long> site = new TreeMap<>();
        jdbc.query("select status, count(*) from bookings where hotel_id = ? and origin = 'SHELTER' group by status",
                rs -> {
                    String st = rs.getString(1);
                    site.merge(st.equals("CONFIRMED") || st.equals("NO_SHOW") ? "RESERVED" : st, rs.getLong(2), Long::sum);
                }, hotelId);
        List<String> details = new ArrayList<>();
        TreeSet<String> groups = new TreeSet<>(shelter.keySet());
        groups.addAll(site.keySet());
        for (String g : groups) {
            long a = shelter.getOrDefault(g, 0L), b = site.getOrDefault(g, 0L);
            if (a != b) {
                details.add(g + ": Shelter " + a + ", сайтда " + b);
            }
        }
        long shared = staysPerFolio.values().stream().filter(c -> c > 1).count();
        if (shared > 0) {
            details.add("Маълумот: " + shared + " та фолио бир нечта яшашга тегишли (гуруҳ/қайта жойлашиш) — қарз бир марта санаб текширилади");
        }
        boolean ok = details.stream().noneMatch(x -> !x.startsWith("Маълумот"));
        long total = latest.size();
        return new Check("Бронлар", ok, "Shelter " + total + " та, сайтда " + site.values().stream().mapToLong(Long::longValue).sum()
                + " та" + (ok ? " — ҳолатлар бўйича мос" : ""), details);
    }

    private Map<YearMonth, BigDecimal> ours(String sql, long hotelId) {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        jdbc.query(sql, rs -> {
            m.put(YearMonth.parse(rs.getString(1)), rs.getBigDecimal(2));
        }, hotelId);
        return m;
    }

    private BigDecimal sum(String sql, long hotelId) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, hotelId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) > 0;
    }

    private static String money(BigDecimal v) {
        return String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue()).replace(',', ' ');
    }
}
