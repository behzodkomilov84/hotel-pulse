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
                : "Бу меҳмонхона Exely Connect орқали уланган — тўлов маълумоти тўлиқ эмас, "
                + "шунинг учун қарз амалдагидан катта кўриниши мумкин. Аниқ таҳлил учун Exely PMS калитини уланг.";
        DebtReport.Summary s = report.summary();
        if (s.count() == 0 || s.total().signum() <= 0) {
            return new DebtAnalysis(warning, "Қарздорлик йўқ — барча яшашлар тўлиқ тўланган. Жорий тартибни сақланг.",
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
        sb.append("Жами қарз ").append(money(s.total(), cur)).append(" — ").append(s.count())
                .append(" та яшаш бўйича, ўртача ").append(money(st.average, cur)).append(". ");

        List<String> parts = new ArrayList<>();
        if (s.inHouseCount() > 0) parts.add(st.catPct.get(0) + " ҳозир яшаётганларда");
        if (s.checkedOutCount() > 0) parts.add(st.catPct.get(1) + " кетган меҳмонларда");
        if (s.notCheckedOutCount() > 0) parts.add(st.catPct.get(2) + " выселение қилинмаган яшашларда");
        sb.append("Қарзнинг ").append(String.join(", ", parts)).append(". ");

        if (st.notCheckedOutShare >= MAJOR_SHARE) {
            sb.append("Асосий муаммо — маълумот тозалиги: кетиш санаси ўтган, лекин PMS'да ҳамон \"яшаяпти\" турган "
                    + "яшашлар қарзни сунъий оширяпти. Аввал уларни тартибга келтириш керак.");
        } else if (st.checkedOutShare >= MAJOR_SHARE) {
            sb.append("Асосий муаммо — кетиб қолган меҳмонлардан ундирилмаган қарз: бу ҳақиқий йўқотиш хавфи.");
        } else if (st.oldShare >= MAJOR_SHARE) {
            sb.append("Асосий муаммо — эски қарзлар: қарзнинг катта қисми 60 кундан ошган.");
        } else {
            sb.append("Ҳолат асосан меъёрида: қарзнинг катта қисми ҳали яшаётган меҳмонларда, "
                    + "одатда улар кетишда тўланади.");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Xavfli nuqtalar

    private List<Point> risks(Stats st, String cur) {
        DebtReport.Summary s = st.s;
        List<Point> risks = new ArrayList<>();
        if (s.notCheckedOutCount() > 0) {
            risks.add(new Point(level(st.notCheckedOutShare), "Выселение қилинмаган: " + st.catMoney.get(2)
                    + " (" + s.notCheckedOutCount() + " та)",
                    "Кетиш санаси ўтган, лекин PMS'да ҳамон \"яшаяпти\". Кўпинча бу ҳақиқий қарз эмас — "
                            + "ресепшн выселение қилишни унутган; лекин тўланмай кетган меҳмонлар ҳам бўлиши мумкин."));
        }
        if (s.checkedOutCount() > 0) {
            risks.add(new Point(level(st.checkedOutShare), "Кетган меҳмонлар қарзи: " + st.catMoney.get(1)
                    + " (" + s.checkedOutCount() + " та)",
                    "Меҳмон кетиб бўлган — вақт ўтган сари ундириш қийинлашади."));
        }
        if (st.old60.signum() > 0) {
            String text = "Кетиш санасидан 60 кундан кўп ўтган қарзлар — жамининг " + pct(st.oldShare) + ".";
            if (st.old90.signum() > 0) {
                text += " Шундан " + money(st.old90, cur) + " — 90 кундан эски: ундириш эҳтимоли паст ёки ўша "
                        + "даврдаги тўловлар PMS'да қайд этилмаган — Exely'даги \"Финансовый учет\" билан солиштиринг.";
            }
            risks.add(new Point(st.oldShare >= MAJOR_SHARE ? Level.HIGH : Level.MEDIUM,
                    "Эски қарз: " + money(st.old60, cur), text));
        }
        if (st.rows.size() >= TOP_CONCENTRATION * 2 && st.topShare >= 0.5) {
            risks.add(new Point(Level.MEDIUM, "Қарз жамланган",
                    "Энг катта " + TOP_CONCENTRATION + " та брон — жами қарзнинг " + pct(st.topShare)
                            + ". Bir nechta mehmon bilan gaplashish katta natija beradi."));
        }
        if (st.unpaidCount > 0) {
            risks.add(new Point(st.unpaidCount * 4 >= st.rows.size() ? Level.MEDIUM : Level.LOW,
                    "Умуман тўланмаган: " + st.unpaidCount + " та яшаш (" + money(st.unpaid, cur) + ")",
                    "Бу яшашлар бўйича бирорта ҳам тўлов йўқ."));
        }
        if (st.topSource != null && st.bySource.size() > 1 && st.topSourceShare >= 0.4) {
            risks.add(new Point(Level.MEDIUM, "Манба: " + st.topSource + " — " + pct(st.topSourceShare),
                    st.topSourceIsOta
                            ? "Қарзнинг катта қисми шу OTA орқали келган бронларда — OTA тўлови (виртуал карта, "
                            + "ўтказма) ҳали тушмаган ёки PMS'да белгиланмаган бўлиши мумкин."
                            : "Қарзнинг катта қисми шу манбадан келган бронларда."));
        }
        risks.sort(Comparator.comparing(Point::level));
        return risks;
    }

    // ---------------------------------------------------------------- Tavsiyalar

    private List<Point> actions(Stats st, String cur) {
        DebtReport.Summary s = st.s;
        List<Point> actions = new ArrayList<>();
        if (s.notCheckedOutCount() > 0) {
            actions.add(withList(level(st.notCheckedOutShare), "Ресепшн",
                    "PMS'да " + s.notCheckedOutCount() + " та яшашни текшириб, выселение қилинг — қарз кўрсаткичи "
                            + st.catMoney.get(2) + " гача аниқлашади. Ҳақиқатан тўламай кетганларни алоҳида белгиланг.",
                    LIST_NOT_CHECKED_OUT, st.rows));
        }
        if (s.checkedOutCount() > 0) {
            actions.add(withList(level(st.checkedOutShare), "Бухгалтерия",
                    "Кетган " + s.checkedOutCount() + " та меҳмон билан боғланиб, қарзни ундиринг — энг катталаридан бошланг "
                            + "(рўйхат қарз бўйича сараланган).", LIST_CHECKED_OUT, st.rows));
        }
        if (st.topSourceIsOta && st.topSourceShare >= 0.4) {
            actions.add(withList(Level.MEDIUM, "Бухгалтерия",
                    st.topSource + " билан ҳисоб-китобни солиштиринг: тушган тўловлар PMS'да белгиланганми.",
                    LIST_SOURCE + st.topSource, st.rows));
        }
        if (st.old60.signum() > 0) {
            actions.add(withList(Level.MEDIUM, "Раҳбарият",
                    "60 кундан эски қарзларни алоҳида кўриб чиқинг: ундириб бўлмайдиганларини ҳисобдан чиқаринг, "
                            + "компания/агентларга расмий талабнома юборинг.", LIST_OLD60, st.rows));
        }
        if (s.inHouseCount() > 0) {
            actions.add(withList(Level.LOW, "Ресепшн",
                    "Ҳозир яшаётган " + s.inHouseCount() + " та меҳмондан (" + st.catMoney.get(0)
                            + ") check-out пайтида тўлиқ тўловни назорат қилинг; узоқ яшайдиганлардан оралиқ тўлов олинг.",
                    LIST_IN_HOUSE, st.rows));
        }
        if (st.unpaidCount * 4 >= st.rows.size() && st.unpaidCount > 0) {
            actions.add(withList(Level.LOW, "Раҳбарият",
                    "Олдиндан тўлов (prepayment) талабини кучайтиринг — " + st.unpaidCount + " та яшаш умуман тўловсиз.",
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
            p = r -> src.equals(r.source() == null || r.source().isBlank() ? "Номаълум" : r.source());
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
                        reason = g.rows().size() + " та хона · " + reason;
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
        String paid = paidTotal.signum() == 0 ? "умуман тўланмаган" : "қисман тўланган";
        return switch (r.category()) {
            case CHECKED_OUT -> (r.ageDays() == 0 ? "Бугун кетган, " : "Кетган, " + r.ageDays() + " кун ўтди, ") + paid + " — ундириш керак";
            case NOT_CHECKED_OUT -> "Кетиш санасидан " + r.ageDays() + " кун ўтган, PMS'да ҳамон яшаяпти — текширинг";
            case IN_HOUSE -> (r.departure().isAfter(today.plusDays(1))
                    ? "Яшаяпти, " + r.departure().format(DAY) + " да кетади"
                    : (r.departure().isAfter(today) ? "Эртага кетади" : "Бугун кетади")) + ", " + paid;
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
                String src = r.source() == null || r.source().isBlank() ? "Номаълум" : r.source();
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
