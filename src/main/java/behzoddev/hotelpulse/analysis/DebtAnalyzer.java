package behzoddev.hotelpulse.analysis;

import behzoddev.hotelpulse.analysis.DebtAnalysis.Level;
import behzoddev.hotelpulse.analysis.DebtAnalysis.Point;
import behzoddev.hotelpulse.analysis.DebtAnalysis.Priority;
import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.kpi.DebtReport.Category;
import behzoddev.hotelpulse.kpi.DebtReport.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Qarzdorlik hisobotini qoidalar asosida tahlil qiladi: ulushlar, qarz yoshi, jamlanish, manbalar.
 * Hammasi serverning o'zida hisoblanadi — pullik xizmat yoki API kaliti kerak emas, ma'lumot tashqariga chiqmaydi.
 */
@Component
@RequiredArgsConstructor
public class DebtAnalyzer {

    /** Shu ulushdan oshsa — muammo "asosiy" deb hisoblanadi. */
    private static final double MAJOR_SHARE = 0.30;
    private static final int TOP_CONCENTRATION = 5;
    private static final int PRIORITY_COUNT = 5;
    private static final List<String> OTA = List.of("booking", "expedia", "ostrovok", "airbnb", "agoda",
            "trip.com", "hotels.com", "yandex", "101hotels", "bronevik", "otello", "tripadvisor");

    private final Formats fmt;
    private final Clock clock;

    public DebtAnalysis analyze(DebtReport report, String currency, boolean paymentsComplete) {
        String warning = paymentsComplete ? null
                : "Bu mehmonxona Exely Connect orqali ulangan — to'lov ma'lumoti to'liq emas, "
                + "shuning uchun qarz amaldagidan katta ko'rinishi mumkin. Aniq tahlil uchun Exely PMS kalitini ulang.";
        DebtReport.Summary s = report.summary();
        if (s.count() == 0 || s.total().signum() <= 0) {
            return new DebtAnalysis(warning, "Qarzdorlik yo'q — barcha yashashlar to'liq to'langan. Joriy tartibni saqlang.",
                    List.of(), List.of(), List.of());
        }
        Stats st = new Stats(report.rows(), s);
        // Kartalardagi bilan bir xil yaxlitlash — qismlar yig'indisi jamiga teng.
        st.catMoney = fmt.moneyShortParts(s.total(), List.of(s.inHouse(), s.checkedOut(), s.notCheckedOut()), currency);
        st.catPct = fmt.pctParts(List.of(st.inHouseShare, st.checkedOutShare, st.notCheckedOutShare));
        return new DebtAnalysis(warning, summary(st, currency), risks(st, currency), actions(st, currency),
                priorities(report.rows(), LocalDate.now(clock)));
    }

    // ---------------------------------------------------------------- Asosiy xulosa

    private String summary(Stats st, String cur) {
        DebtReport.Summary s = st.s;
        StringBuilder sb = new StringBuilder();
        sb.append("Jami qarz ").append(money(s.total(), cur)).append(" — ").append(s.count())
                .append(" ta yashash bo'yicha, o'rtacha ").append(money(st.average, cur)).append(". ");

        List<String> parts = new ArrayList<>();
        if (s.inHouseCount() > 0) parts.add(st.catPct.get(0) + " hozir yashayotganlarda");
        if (s.checkedOutCount() > 0) parts.add(st.catPct.get(1) + " ketgan mehmonlarda");
        if (s.notCheckedOutCount() > 0) parts.add(st.catPct.get(2) + " vyselenie qilinmagan yashashlarda");
        sb.append("Qarzning ").append(String.join(", ", parts)).append(". ");

        if (st.notCheckedOutShare >= MAJOR_SHARE) {
            sb.append("Asosiy muammo — ma'lumot tozaligi: ketish sanasi o'tgan, lekin PMS'da hamon \"yashayapti\" turgan "
                    + "yashashlar qarzni sun'iy oshiryapti. Avval ularni tartibga keltirish kerak.");
        } else if (st.checkedOutShare >= MAJOR_SHARE) {
            sb.append("Asosiy muammo — ketib qolgan mehmonlardan undirilmagan qarz: bu haqiqiy yo'qotish xavfi.");
        } else if (st.oldShare >= MAJOR_SHARE) {
            sb.append("Asosiy muammo — eski qarzlar: qarzning katta qismi 60 kundan oshgan.");
        } else {
            sb.append("Holat asosan me'yorida: qarzning katta qismi hali yashayotgan mehmonlarda, "
                    + "odatda ular ketishda to'lanadi.");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Xavfli nuqtalar

    private List<Point> risks(Stats st, String cur) {
        DebtReport.Summary s = st.s;
        List<Point> risks = new ArrayList<>();
        if (s.notCheckedOutCount() > 0) {
            risks.add(new Point(level(st.notCheckedOutShare), "Vyselenie qilinmagan: " + st.catMoney.get(2)
                    + " (" + s.notCheckedOutCount() + " ta)",
                    "Ketish sanasi o'tgan, lekin PMS'da hamon \"yashayapti\". Ko'pincha bu haqiqiy qarz emas — "
                            + "resepshn vyselenie qilishni unutgan; lekin to'lanmay ketgan mehmonlar ham bo'lishi mumkin."));
        }
        if (s.checkedOutCount() > 0) {
            risks.add(new Point(level(st.checkedOutShare), "Ketgan mehmonlar qarzi: " + st.catMoney.get(1)
                    + " (" + s.checkedOutCount() + " ta)",
                    "Mehmon ketib bo'lgan — vaqt o'tgan sari undirish qiyinlashadi."));
        }
        if (st.old60.signum() > 0) {
            String text = "Ketish sanasidan 60 kundan ko'p o'tgan qarzlar — jamining " + pct(st.oldShare) + ".";
            if (st.old90.signum() > 0) {
                text += " Shundan " + money(st.old90, cur) + " — 90 kundan eski: undirish ehtimoli past yoki o'sha "
                        + "davrdagi to'lovlar PMS'da qayd etilmagan — Exely'dagi \"Финансовый учет\" bilan solishtiring.";
            }
            risks.add(new Point(st.oldShare >= MAJOR_SHARE ? Level.HIGH : Level.MEDIUM,
                    "Eski qarz: " + money(st.old60, cur), text));
        }
        if (st.rows.size() >= TOP_CONCENTRATION * 2 && st.topShare >= 0.5) {
            risks.add(new Point(Level.MEDIUM, "Qarz jamlangan",
                    "Eng katta " + TOP_CONCENTRATION + " ta bron — jami qarzning " + pct(st.topShare)
                            + ". Bir nechta mehmon bilan gaplashish katta natija beradi."));
        }
        if (st.unpaidCount > 0) {
            risks.add(new Point(st.unpaidCount * 4 >= st.rows.size() ? Level.MEDIUM : Level.LOW,
                    "Umuman to'lanmagan: " + st.unpaidCount + " ta yashash (" + money(st.unpaid, cur) + ")",
                    "Bu yashashlar bo'yicha birorta ham to'lov yo'q."));
        }
        if (st.topSource != null && st.bySource.size() > 1 && st.topSourceShare >= 0.4) {
            risks.add(new Point(Level.MEDIUM, "Manba: " + st.topSource + " — " + pct(st.topSourceShare),
                    st.topSourceIsOta
                            ? "Qarzning katta qismi shu OTA orqali kelgan bronlarda — OTA to'lovi (virtual karta, "
                            + "o'tkazma) hali tushmagan yoki PMS'da belgilanmagan bo'lishi mumkin."
                            : "Qarzning katta qismi shu manbadan kelgan bronlarda."));
        }
        risks.sort(Comparator.comparing(Point::level));
        return risks;
    }

    // ---------------------------------------------------------------- Tavsiyalar

    private List<Point> actions(Stats st, String cur) {
        DebtReport.Summary s = st.s;
        List<Point> actions = new ArrayList<>();
        if (s.notCheckedOutCount() > 0) {
            actions.add(withList(level(st.notCheckedOutShare), "Resepshn",
                    "PMS'da " + s.notCheckedOutCount() + " ta yashashni tekshirib, vyselenie qiling — qarz ko'rsatkichi "
                            + st.catMoney.get(2) + " gacha aniqlashadi. Haqiqatan to'lamay ketganlarni alohida belgilang.",
                    LIST_NOT_CHECKED_OUT, st.rows));
        }
        if (s.checkedOutCount() > 0) {
            actions.add(withList(level(st.checkedOutShare), "Buxgalteriya",
                    "Ketgan " + s.checkedOutCount() + " ta mehmon bilan bog'lanib, qarzni undiring — eng kattalaridan boshlang "
                            + "(ro'yxat qarz bo'yicha saralangan).", LIST_CHECKED_OUT, st.rows));
        }
        if (st.topSourceIsOta && st.topSourceShare >= 0.4) {
            actions.add(withList(Level.MEDIUM, "Buxgalteriya",
                    st.topSource + " bilan hisob-kitobni solishtiring: tushgan to'lovlar PMS'da belgilanganmi.",
                    LIST_SOURCE + st.topSource, st.rows));
        }
        if (st.old60.signum() > 0) {
            actions.add(withList(Level.MEDIUM, "Rahbariyat",
                    "60 kundan eski qarzlarni alohida ko'rib chiqing: undirib bo'lmaydiganlarini hisobdan chiqaring, "
                            + "kompaniya/agentlarga rasmiy talabnoma yuboring.", LIST_OLD60, st.rows));
        }
        if (s.inHouseCount() > 0) {
            actions.add(withList(Level.LOW, "Resepshn",
                    "Hozir yashayotgan " + s.inHouseCount() + " ta mehmondan (" + st.catMoney.get(0)
                            + ") check-out paytida to'liq to'lovni nazorat qiling; uzoq yashaydiganlardan oraliq to'lov oling.",
                    LIST_IN_HOUSE, st.rows));
        }
        if (st.unpaidCount * 4 >= st.rows.size() && st.unpaidCount > 0) {
            actions.add(withList(Level.LOW, "Rahbariyat",
                    "Oldindan to'lov (prepayment) talabini kuchaytiring — " + st.unpaidCount + " ta yashash umuman to'lovsiz.",
                    LIST_UNPAID, st.rows));
        }
        actions.sort(Comparator.comparing(Point::level));
        return actions;
    }

    // ---------------------------------------------------------------- Ilova ro'yxatlari

    /** Tavsiyaga ilova qilinadigan ro'yxat turlari (topshiriqqa bronlar ro'yxati bo'lib qo'shiladi). */
    public static final String LIST_CHECKED_OUT = "CHECKED_OUT", LIST_NOT_CHECKED_OUT = "NOT_CHECKED_OUT",
            LIST_IN_HOUSE = "IN_HOUSE", LIST_OLD60 = "OLD60", LIST_UNPAID = "UNPAID",
            LIST_SOURCE = "SOURCE:", LIST_BOOKING = "BOOKING:";

    private static Point withList(Level level, String title, String text, String listKey, List<Row> rows) {
        return new Point(level, title, text, listKey, listRows(rows, listKey).size());
    }

    /** Ro'yxat qatorlari (qarz bo'yicha kamayish tartibida). Noma'lum kalit — bo'sh ro'yxat. */
    public static List<Row> listRows(List<Row> rows, String listKey) {
        if (listKey == null || listKey.isBlank()) {
            return List.of();
        }
        java.util.function.Predicate<Row> p;
        if (listKey.startsWith(LIST_SOURCE)) {
            String src = listKey.substring(LIST_SOURCE.length());
            p = r -> src.equals(r.source() == null || r.source().isBlank() ? "Noma'lum" : r.source());
        } else if (listKey.startsWith(LIST_BOOKING)) {
            String number = listKey.substring(LIST_BOOKING.length());
            p = r -> number.equals(r.bookingNumber());
        } else {
            p = switch (listKey) {
                case LIST_CHECKED_OUT -> r -> r.category() == Category.CHECKED_OUT;
                case LIST_NOT_CHECKED_OUT -> r -> r.category() == Category.NOT_CHECKED_OUT;
                case LIST_IN_HOUSE -> r -> r.category() == Category.IN_HOUSE;
                case LIST_OLD60 -> r -> r.category() != Category.IN_HOUSE && r.ageDays() > 60;
                case LIST_UNPAID -> r -> r.paid().signum() == 0;
                default -> r -> false;
            };
        }
        return rows.stream().filter(p).sorted(Comparator.comparing(Row::debt).reversed()).toList();
    }

    // ---------------------------------------------------------------- Birinchi navbat

    /**
     * Ball: qarz × (1 + yosh/30) × toifa vazni. Ketganlar — eng yuqori, uzoq yashaydiganlar — past.
     * Ko'p xonali bron (bir nechta yashash) — bitta qator bo'lib, qarzlari qo'shiladi.
     */
    private List<Priority> priorities(List<Row> rows, LocalDate today) {
        Map<String, List<Row>> byBooking = new LinkedHashMap<>();
        for (Row r : rows) {
            byBooking.computeIfAbsent(r.bookingNumber(), k -> new ArrayList<>()).add(r);
        }
        record Group(Row main, List<Row> rows, BigDecimal debt, BigDecimal paid, BigDecimal total, double score) {
        }
        return byBooking.values().stream()
                .map(g -> {
                    Row main = g.stream().max(Comparator.comparingDouble(r -> score(r, today))).orElseThrow();
                    return new Group(main, g,
                            g.stream().map(Row::debt).reduce(BigDecimal.ZERO, BigDecimal::add),
                            g.stream().map(Row::paid).reduce(BigDecimal.ZERO, BigDecimal::add),
                            g.stream().map(Row::total).reduce(BigDecimal.ZERO, BigDecimal::add),
                            g.stream().mapToDouble(r -> score(r, today)).sum());
                })
                .sorted(Comparator.comparingDouble((Group g) -> -g.score()))
                .limit(PRIORITY_COUNT)
                .map(g -> {
                    String reason = reason(g.main(), g.paid(), today);
                    if (g.rows().size() > 1) {
                        reason = g.rows().size() + " ta xona · " + reason;
                    }
                    return new Priority(g.main().bookingNumber(), guestName(g.rows()), g.main().source(),
                            g.debt(), g.paid(), g.total(), reason);
                })
                .toList();
    }

    /** Juda eski qarz ballni cheksiz oshirmasin — 90 kundan keyin yosh ta'siri o'smaydi. */
    static double score(Row r, LocalDate today) {
        double weight = switch (r.category()) {
            case CHECKED_OUT -> 1.5;
            case NOT_CHECKED_OUT -> 1.2;
            case IN_HOUSE -> r.departure().isAfter(today.plusDays(1)) ? 0.3 : 1.0;
        };
        return r.debt().doubleValue() * (1 + Math.min(r.ageDays(), 90) / 30.0) * weight;
    }

    /** PMS'dagi "--- ---" kabi to'ldiruvchi ismlar — ism yo'q deb hisoblanadi. */
    private static String guestName(List<Row> rows) {
        return rows.stream().map(Row::guestName)
                .filter(n -> n != null && n.chars().anyMatch(Character::isLetter))
                .findFirst().orElse(null);
    }

    private static String reason(Row r, BigDecimal paidTotal, LocalDate today) {
        String paid = paidTotal.signum() == 0 ? "umuman to'lanmagan" : "qisman to'langan";
        return switch (r.category()) {
            case CHECKED_OUT -> (r.ageDays() == 0 ? "Bugun ketgan, " : "Ketgan, " + r.ageDays() + " kun o'tdi, ") + paid + " — undirish kerak";
            case NOT_CHECKED_OUT -> "Ketish sanasidan " + r.ageDays() + " kun o'tgan, PMS'da hamon yashayapti — tekshiring";
            case IN_HOUSE -> (r.departure().isAfter(today.plusDays(1))
                    ? "Yashayapti, " + r.departure().format(DAY) + " da ketadi"
                    : (r.departure().isAfter(today) ? "Ertaga ketadi" : "Bugun ketadi")) + ", " + paid;
        };
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    // ---------------------------------------------------------------- yordamchilar

    private static Level level(double share) {
        return share >= MAJOR_SHARE ? Level.HIGH : share >= 0.10 ? Level.MEDIUM : Level.LOW;
    }

    private String money(BigDecimal v, String cur) {
        return fmt.moneyShort(v, cur);
    }

    private String pct(double share) {
        return fmt.pct(share);
    }

    private static boolean isOta(String source) {
        String s = source.toLowerCase(Locale.ROOT);
        return OTA.stream().anyMatch(s::contains);
    }

    /** Bir marta hisoblanadigan ko'rsatkichlar. */
    private static final class Stats {
        final List<Row> rows;
        final DebtReport.Summary s;
        final BigDecimal average;
        final double inHouseShare, checkedOutShare, notCheckedOutShare;
        BigDecimal old60 = BigDecimal.ZERO, old90 = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
        final double oldShare, topShare;
        int unpaidCount;
        final Map<String, BigDecimal> bySource = new LinkedHashMap<>();
        String topSource;
        double topSourceShare;
        boolean topSourceIsOta;
        List<String> catMoney, catPct;

        Stats(List<Row> rows, DebtReport.Summary s) {
            this.rows = rows;
            this.s = s;
            double total = s.total().doubleValue();
            average = s.total().divide(BigDecimal.valueOf(s.count()), 0, java.math.RoundingMode.HALF_UP);
            inHouseShare = s.inHouse().doubleValue() / total;
            checkedOutShare = s.checkedOut().doubleValue() / total;
            notCheckedOutShare = s.notCheckedOut().doubleValue() / total;
            for (Row r : rows) {
                if (r.category() != Category.IN_HOUSE && r.ageDays() > 60) old60 = old60.add(r.debt());
                if (r.category() != Category.IN_HOUSE && r.ageDays() > 90) old90 = old90.add(r.debt());
                if (r.paid().signum() == 0) {
                    unpaidCount++;
                    unpaid = unpaid.add(r.debt());
                }
                String src = r.source() == null || r.source().isBlank() ? "Noma'lum" : r.source();
                bySource.merge(src, r.debt(), BigDecimal::add);
            }
            oldShare = old60.doubleValue() / total;
            topShare = rows.stream().map(Row::debt).sorted(Comparator.reverseOrder()).limit(TOP_CONCENTRATION)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue() / total;
            bySource.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> {
                topSource = e.getKey();
                topSourceShare = e.getValue().doubleValue() / total;
                topSourceIsOta = isOta(e.getKey());
            });
        }
    }
}
