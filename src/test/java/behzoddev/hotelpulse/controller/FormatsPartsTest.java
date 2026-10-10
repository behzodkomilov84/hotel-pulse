package behzoddev.hotelpulse.controller;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Yaxlitlangan qismlar yig'indisi ko'rsatilgan jamiga teng bo'lishi kerak. */
class FormatsPartsTest {

    private final Formats fmt = new Formats();

    private static double number(String s) {
        return Double.parseDouble(s.replaceAll("[^0-9,]", "").replace(',', '.'));
    }

    @Test
    void moneyPartsAddUpToShownTotal() {
        // Alohida yaxlitlansa: 125,0 + 753,3 + 105,2 = 983,5 ≠ 983,6
        BigDecimal in = new BigDecimal("124980000"), out = new BigDecimal("753340000"), not = new BigDecimal("105240000");
        BigDecimal total = in.add(out).add(not);
        assertThat(fmt.moneyShort(total, "UZS")).startsWith("983,6");

        List<String> parts = fmt.moneyShortParts(total, List.of(in, out, not), "UZS");
        double sum = parts.stream().mapToDouble(FormatsPartsTest::number).sum();
        assertThat(Math.round(sum * 10)).isEqualTo(9836);
        assertThat(parts).allMatch(p -> p.endsWith("mln so'm"));
    }

    @Test
    void zeroPartAndSmallTotals() {
        List<String> parts = fmt.moneyShortParts(new BigDecimal("5000000"),
                List.of(new BigDecimal("5000000"), BigDecimal.ZERO), "UZS");
        assertThat(parts.get(0)).startsWith("5").contains("mln");
        assertThat(parts.get(1)).isEqualTo(fmt.money(BigDecimal.ZERO, "UZS"));

        // 1 mln dan kichik — aniq summalar, yig'indisi o'z-o'zidan to'g'ri.
        assertThat(fmt.moneyShortParts(new BigDecimal("700000"),
                List.of(new BigDecimal("512000"), new BigDecimal("188000")), "UZS"))
                .containsExactly(fmt.money(new BigDecimal("512000"), "UZS"), fmt.money(new BigDecimal("188000"), "UZS"));
    }

    @Test
    void billionTotalKeepsMillionPrecisionInParts() {
        // Jami ~1 mlrd: qismlar "0,1 mlrd" emas — mln aniqligida; yig'indisi jamiga teng (mln'da).
        BigDecimal out = new BigDecimal("829100000"), not = new BigDecimal("118400000"), in = new BigDecimal("52500000");
        BigDecimal total = out.add(not).add(in);
        List<String> parts = fmt.moneyShortParts(total, List.of(in, out, not), "UZS");
        assertThat(parts.get(1)).startsWith("829,1").contains("mln");
        assertThat(parts.get(2)).startsWith("118,4").contains("mln");
        assertThat(Math.round(parts.stream().mapToDouble(FormatsPartsTest::number).sum() * 10)).isEqualTo(10000);

        // 1 mlrd'dan katta qism — mlrd'da.
        List<String> big = fmt.moneyShortParts(new BigDecimal("1500000000"),
                List.of(new BigDecimal("1234000000"), new BigDecimal("266000000")), "UZS");
        assertThat(big.get(0)).startsWith("1,23").contains("mlrd");
        assertThat(big.get(1)).startsWith("266").contains("mln");
    }

    @Test
    void percentPartsAddUpTo100() {
        // Alohida: 53,1 + 3,4 + 0,6 + 43,0 = 100,1%
        List<String> parts = fmt.pctParts(List.of(0.53055, 0.03385, 0.00565, 0.42995));
        double sum = parts.stream().mapToDouble(FormatsPartsTest::number).sum();
        assertThat(Math.round(sum * 10)).isEqualTo(1000);
    }
}
