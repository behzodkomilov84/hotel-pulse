package behzoddev.hotelpulse.kpi;

import behzoddev.hotelpulse.controller.Formats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioSummaryTest {

    @Test
    void averageAdrIsWeightedByRoomNights() {
        // A: 100 kecha × 500 000; B: 10 kecha × 1 000 000. Oddiy o'rtacha 750 000 bo'lardi — noto'g'ri.
        PortfolioSummary p = new PortfolioSummary("UZS", 2, 110, 200,
                new BigDecimal("60000000"), new BigDecimal("70000000"), new BigDecimal("50000000"), new BigDecimal("20000000"));
        assertEquals(new BigDecimal("545455"), p.adr());
        assertEquals(new BigDecimal("300000"), p.revpar());
        assertEquals(0.55, p.occupancy(), 1e-9);
    }

    @Test
    void gapIsSigned() {
        Formats fmt = new Formats();
        assertEquals("+" + fmt.moneyShort(new BigDecimal("20000000"), "UZS"), fmt.signedMoneyShort(new BigDecimal("20000000"), "UZS"));
        assertEquals("−" + fmt.moneyShort(new BigDecimal("4100000"), "UZS"), fmt.signedMoneyShort(new BigDecimal("-4100000"), "UZS"));
        assertEquals("ҳали тўланмаган қисм", fmt.gapNote(BigDecimal.ONE));
        assertEquals("олдиндан тўловлар кўпроқ", fmt.gapNote(BigDecimal.ONE.negate()));
    }
}
