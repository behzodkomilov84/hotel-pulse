package behzoddev.hotelpulse.kpi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hisobot davri: [from, to] — ikkala sana ham kiradi.
 * key — sahifadagi tanlov tugmasi (7d, 30d, month, prevmonth, next30, custom).
 */
public record Period(String key, LocalDate from, LocalDate to) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    public static final String DEFAULT_KEY = "month";

    /** Sahifadagi tayyor davr tugmalari: kalit → nomi. */
    public static final Map<String, String> OPTIONS = new LinkedHashMap<>();

    static {
        OPTIONS.put("today", "Bugun");
        OPTIONS.put("7d", "7 kun");
        OPTIONS.put("30d", "30 kun");
        OPTIONS.put("month", "Shu oy");
        OPTIONS.put("prevmonth", "O'tgan oy");
        OPTIONS.put("next30", "Kelgusi 30 kun");
    }

    public Period {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Davr oxiri boshidan oldin bo'lmasligi kerak");
        }
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public LocalDate toExclusive() {
        return to.plusDays(1);
    }

    public String label() {
        return from.equals(to) ? from.format(DATE) : from.format(DATE) + " — " + to.format(DATE);
    }

    /** Tanlov tugmasi bo'yicha davrni aniqlaydi. Noma'lum kalit — shu oy. */
    public static Period resolve(String key, LocalDate customFrom, LocalDate customTo, LocalDate today) {
        if (key == null) {
            key = DEFAULT_KEY;
        }
        return switch (key) {
            case "today" -> new Period(key, today, today);
            case "7d" -> new Period(key, today.minusDays(6), today);
            case "30d" -> new Period(key, today.minusDays(29), today);
            case "prevmonth" -> {
                LocalDate first = today.minusMonths(1).withDayOfMonth(1);
                yield new Period(key, first, first.with(TemporalAdjusters.lastDayOfMonth()));
            }
            case "next30" -> new Period(key, today, today.plusDays(29));
            case "custom" -> {
                if (customFrom == null || customTo == null) {
                    yield resolve(DEFAULT_KEY, null, null, today);
                }
                LocalDate from = customFrom.isAfter(customTo) ? customTo : customFrom;
                LocalDate to = customFrom.isAfter(customTo) ? customFrom : customTo;
                // Juda uzun davr sahifani sekinlashtirmasligi uchun — maksimal 1 yil.
                if (ChronoUnit.DAYS.between(from, to) > 366) {
                    from = to.minusDays(366);
                }
                yield new Period(key, from, to);
            }
            default -> new Period(DEFAULT_KEY, today.withDayOfMonth(1), today);
        };
    }

    /**
     * Taqqoslash uchun oldingi davr: oy bo'yicha davrlar — oldingi oyning
     * mos kunlari, qolganlari — xuddi shu uzunlikdagi bevosita oldingi davr.
     */
    public Period previous() {
        if (key.equals("month") || key.equals("prevmonth")) {
            LocalDate prevFrom = from.minusMonths(1);
            LocalDate prevTo = to.minusMonths(1);
            if (key.equals("prevmonth")) {
                prevTo = prevFrom.with(TemporalAdjusters.lastDayOfMonth());
            }
            return new Period(key, prevFrom, prevTo);
        }
        return new Period(key, from.minusDays(days()), from.minusDays(1));
    }
}
