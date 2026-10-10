package behzoddev.hotelpulse.faktura;

import behzoddev.hotelpulse.faktura.FakturaService.Candidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Chiquvchi hisob-fakturani bronga avtomatik bog'lash qoidalari. */
class FakturaMatchTest {

    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    private static Map<String, Candidate> candidates(Candidate... cs) {
        Map<String, Candidate> m = new LinkedHashMap<>();
        for (Candidate c : cs) {
            m.put(c.number(), c);
        }
        return m;
    }

    private static Candidate booking(String number, String payer, LocalDate arrival, LocalDate departure, String total) {
        return new Candidate(number, Set.of(FakturaParser.normalizeName(payer)), arrival, departure,
                new BigDecimal(total), new BigDecimal(total));
    }

    @Test
    void matchesByNameAmountAndDate() {
        Map<String, Candidate> c = candidates(
                booking("100-01", "Tashkent Travel MChJ", OCT.minusDays(5), OCT.minusDays(2), "3000000"),
                booking("200-01", "Silk Road LLC", OCT.minusDays(5), OCT.minusDays(2), "3000000"));

        assertEquals("100-01", FakturaService.match(null, "\"TASHKENT TRAVEL\" MChJ", OCT, new BigDecimal("3001000"), c, Map.of()));
        // summa mos emas
        assertNull(FakturaService.match(null, "Tashkent Travel", OCT, new BigDecimal("2500000"), c, Map.of()));
        // sana juda uzoq
        assertNull(FakturaService.match(null, "Tashkent Travel", OCT.plusDays(200), new BigDecimal("3000000"), c, Map.of()));
    }

    @Test
    void innFindsExelyCustomerName() {
        Map<String, Candidate> c = candidates(booking("300-01", "Uzbektourism", OCT.minusDays(3), OCT, "1500000"));

        assertEquals("300-01", FakturaService.match("301234567", "Ўзбектуризм АЖ", OCT, new BigDecimal("1500000"), c,
                Map.of("301234567", "Uzbektourism")));
    }

    @Test
    void closestDepartureWinsAndTiesAreLeftForManual() {
        Map<String, Candidate> c = candidates(
                booking("1", "Acme Group", OCT.minusDays(40), OCT.minusDays(35), "1000000"),
                booking("2", "Acme Group", OCT.minusDays(5), OCT.minusDays(1), "1000000"));
        assertEquals("2", FakturaService.match(null, "Acme Group", OCT, new BigDecimal("1000000"), c, Map.of()));

        Map<String, Candidate> tie = candidates(
                booking("1", "Acme Group", OCT.minusDays(5), OCT.minusDays(1), "1000000"),
                booking("2", "Acme Group", OCT.minusDays(4), OCT.minusDays(1), "1000000"));
        assertNull(FakturaService.match(null, "Acme Group", OCT, new BigDecimal("1000000"), tie, Map.of()));
    }

    @Test
    void stableIdWithoutUniqueId() {
        FakturaParser.Parsed a = new FakturaParser.Parsed(null, 2, 24, "15", OCT, "301234567", "X", null, null, null);
        assertEquals(FakturaService.idOf(a), FakturaService.idOf(a));
        assertTrue(FakturaService.idOf(a).length() <= 64);
        assertNull(FakturaService.idOf(new FakturaParser.Parsed(null, 2, 24, null, null, null, null, null, null, null)));
    }
}
