package behzoddev.hotelpulse.analysis;

import behzoddev.hotelpulse.analysis.DebtAnalysis.Level;
import behzoddev.hotelpulse.analysis.DebtAnalysis.Point;
import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.kpi.DebtReport.Category;
import behzoddev.hotelpulse.kpi.DebtReport.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebtAnalyzerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private final DebtAnalyzer analyzer = new DebtAnalyzer(new Formats(),
            Clock.fixed(Instant.parse("2026-10-05T07:00:00Z"), ZoneId.of("Asia/Tashkent")));

    private static Row row(String no, String source, Category c, long debt, long paid, long age, int departIn) {
        return new Row(no, "Mehmon " + no, source, TODAY.plusDays(departIn - 3), TODAY.plusDays(departIn), 3, c,
                BigDecimal.valueOf(debt + paid), BigDecimal.valueOf(paid), BigDecimal.valueOf(debt), age);
    }

    private static DebtReport report(List<Row> rows) {
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        long[] counts = new long[3];
        for (Row r : rows) {
            sums[r.category().ordinal()] = sums[r.category().ordinal()].add(r.debt());
            counts[r.category().ordinal()]++;
        }
        BigDecimal total = sums[0].add(sums[1]).add(sums[2]);
        return new DebtReport(rows, new DebtReport.Summary(total, rows.size(), sums[0], counts[0],
                sums[1], counts[1], sums[2], counts[2]), List.of());
    }

    @Test
    void noDebt() {
        DebtAnalysis a = analyzer.analyze(report(List.of()), "UZS", true);
        assertThat(a.summary()).contains("Qarzdorlik yo'q");
        assertThat(a.risks()).isEmpty();
        assertThat(a.priorities()).isEmpty();
        assertThat(a.warning()).isNull();
    }

    @Test
    void notCheckedOutDominates() {
        List<Row> rows = List.of(
                row("A", "Booking.com", Category.NOT_CHECKED_OUT, 5_000_000, 0, 100, -100),
                row("B", "Direct", Category.NOT_CHECKED_OUT, 3_000_000, 500_000, 70, -70),
                row("C", "Direct", Category.IN_HOUSE, 1_000_000, 0, 0, 4),
                row("D", "Direct", Category.CHECKED_OUT, 1_000_000, 200_000, 10, -10));
        DebtAnalysis a = analyzer.analyze(report(rows), "UZS", false);

        assertThat(a.warning()).contains("Exely Connect");
        assertThat(a.summary()).contains("Jami qarz 10", "4 ta yashash", "ma'lumot tozaligi");
        Point first = a.risks().get(0);
        assertThat(first.level()).isEqualTo(Level.HIGH);
        assertThat(first.title()).startsWith("Vyselenie qilinmagan");
        assertThat(a.risks()).anyMatch(p -> p.title().startsWith("Eski qarz"));
        assertThat(a.actions().get(0).title()).isEqualTo("Resepshn");
        // Eng eski va katta "vyselenie qilinmagan" — birinchi; uzoq yashaydigan mehmon — oxirida.
        assertThat(a.priorities().get(0).bookingNumber()).isEqualTo("A");
        assertThat(a.priorities().get(a.priorities().size() - 1).bookingNumber()).isEqualTo("C");
        assertThat(a.priorities().get(0).reason()).contains("100 kun");
    }

    @Test
    void multiRoomBookingIsOneEntryAndPlaceholderNamesAreHidden() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(new Row("BIG", "--- ---", "Direct", TODAY.minusDays(520), TODAY.minusDays(518), 2,
                    Category.NOT_CHECKED_OUT, BigDecimal.valueOf(4_000_000), BigDecimal.ZERO,
                    BigDecimal.valueOf(4_000_000), 518));
        }
        rows.add(row("NEW", "Direct", Category.CHECKED_OUT, 9_000_000, 0, 20, -20));
        DebtAnalysis a = analyzer.analyze(report(rows), "UZS", true);

        assertThat(a.priorities()).extracting(DebtAnalysis.Priority::bookingNumber).containsExactly("BIG", "NEW");
        DebtAnalysis.Priority big = a.priorities().get(0);
        assertThat(big.debt()).isEqualByComparingTo("16000000");
        assertThat(big.reason()).startsWith("4 ta xona");
        assertThat(big.guestName()).isNull();
    }

    @Test
    void veryOldDebtDoesNotOutweighLargerRecentOne() {
        List<Row> rows = List.of(
                row("OLD", "Direct", Category.CHECKED_OUT, 2_000_000, 0, 500, -500),
                row("BIGGER", "Direct", Category.CHECKED_OUT, 6_000_000, 0, 30, -30));
        DebtAnalysis a = analyzer.analyze(report(rows), "UZS", true);
        assertThat(a.priorities().get(0).bookingNumber()).isEqualTo("BIGGER");
    }

    @Test
    void mostlyInHouseIsNormalAndOtaIsFlagged() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(row("H" + i, "Booking.com", Category.IN_HOUSE, 2_000_000, 1_000_000, 0, 3));
        }
        rows.add(row("X", "Direct", Category.CHECKED_OUT, 500_000, 100_000, 5, -5));
        DebtAnalysis a = analyzer.analyze(report(rows), "UZS", true);

        assertThat(a.warning()).isNull();
        assertThat(a.summary()).contains("me'yorida");
        assertThat(a.risks()).anyMatch(p -> p.title().startsWith("Manba: Booking.com") && p.text().contains("OTA"));
        assertThat(a.actions()).anyMatch(p -> p.text().contains("Booking.com bilan hisob-kitob"));
        // Ketgan mehmon summasi kichik bo'lsa ham — ustuvor (ketgan — undirish qiyinroq).
        assertThat(a.priorities()).extracting(DebtAnalysis.Priority::bookingNumber).contains("X");
    }

    @Test
    void actionsCarryAttachableListsSortedByDebt() {
        List<Row> rows = List.of(
                row("A", "Booking.com", Category.NOT_CHECKED_OUT, 5_000_000, 0, 100, -100),
                row("D1", "Direct", Category.CHECKED_OUT, 1_000_000, 200_000, 10, -10),
                row("D2", "Direct", Category.CHECKED_OUT, 3_000_000, 0, 70, -70),
                row("C", "", Category.IN_HOUSE, 1_000_000, 0, 0, 4));
        DebtAnalysis a = analyzer.analyze(report(rows), "UZS", true);

        Point checkedOut = a.actions().stream().filter(p -> DebtAnalyzer.LIST_CHECKED_OUT.equals(p.listKey())).findFirst().orElseThrow();
        assertThat(checkedOut.listSize()).isEqualTo(2);
        assertThat(checkedOut.text()).doesNotContain("pastdagi");
        // Ro'yxat — qarz bo'yicha kamayish tartibida.
        assertThat(DebtAnalyzer.listRows(rows, DebtAnalyzer.LIST_CHECKED_OUT)).extracting(Row::bookingNumber)
                .containsExactly("D2", "D1");
        assertThat(DebtAnalyzer.listRows(rows, DebtAnalyzer.LIST_OLD60)).extracting(Row::bookingNumber)
                .containsExactly("A", "D2");
        assertThat(DebtAnalyzer.listRows(rows, DebtAnalyzer.LIST_UNPAID)).extracting(Row::bookingNumber)
                .containsExactly("A", "D2", "C");
        assertThat(DebtAnalyzer.listRows(rows, DebtAnalyzer.LIST_SOURCE + "Noma'lum")).extracting(Row::bookingNumber)
                .containsExactly("C");
        assertThat(DebtAnalyzer.listRows(rows, DebtAnalyzer.LIST_BOOKING + "D1")).hasSize(1);
        assertThat(DebtAnalyzer.listRows(rows, "NONSENSE")).isEmpty();
        assertThat(DebtAnalyzer.listRows(rows, null)).isEmpty();
        // Har bir ro'yxatli tavsiyada soni haqiqiy ro'yxatga teng.
        a.actions().stream().filter(p -> p.listKey() != null)
                .forEach(p -> assertThat(DebtAnalyzer.listRows(rows, p.listKey())).hasSize(p.listSize()));
    }
}
