package behzoddev.hotelpulse.kpi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Test
    void monthIsFirstDayUntilToday() {
        Period p = Period.resolve("month", null, null, TODAY);
        assertEquals(LocalDate.of(2026, 10, 1), p.from());
        assertEquals(TODAY, p.to());
        assertEquals(4, p.days());
        Period prev = p.previous();
        assertEquals(LocalDate.of(2026, 9, 1), prev.from());
        assertEquals(LocalDate.of(2026, 9, 4), prev.to());
    }

    @Test
    void previousMonthIsWholeCalendarMonth() {
        Period p = Period.resolve("prevmonth", null, null, TODAY);
        assertEquals(LocalDate.of(2026, 9, 1), p.from());
        assertEquals(LocalDate.of(2026, 9, 30), p.to());
        Period prev = p.previous();
        assertEquals(LocalDate.of(2026, 8, 1), prev.from());
        assertEquals(LocalDate.of(2026, 8, 31), prev.to());
    }

    @Test
    void rollingPeriodComparesWithPrecedingSameLength() {
        Period p = Period.resolve("7d", null, null, TODAY);
        assertEquals(LocalDate.of(2026, 9, 28), p.from());
        Period prev = p.previous();
        assertEquals(LocalDate.of(2026, 9, 21), prev.from());
        assertEquals(LocalDate.of(2026, 9, 27), prev.to());
    }

    @Test
    void customSwapsReversedDatesAndUnknownKeyFallsBackToMonth() {
        Period p = Period.resolve("custom", TODAY, TODAY.minusDays(9), TODAY);
        assertEquals(TODAY.minusDays(9), p.from());
        assertEquals(10, p.days());
        assertEquals("month", Period.resolve("nonsense", null, null, TODAY).key());
        assertEquals("month", Period.resolve("custom", null, null, TODAY).key());
    }
}
