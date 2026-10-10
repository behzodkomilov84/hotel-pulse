package behzoddev.hotelpulse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvoiceReportServiceTest {

    @Test
    void serviceLineIsMappedToOwnOrGroupAccount() {
        // Real holat (Belinter Olga, guruh broni): o'z hisobida faqat transport (38 986 000),
        // yashash va nonushta — guruhning umumiy hisobida.
        List<BigDecimal> ownItems = new ArrayList<>(List.of(new BigDecimal("38986000")));
        List<String> own = List.of("1264192044-35");
        List<String> general = List.of("1264192044-34");

        assertEquals("1264192044-35 (хонанинг ўз ҳисоби)",
                InvoiceReportService.accountOf(new BigDecimal("38986000"), ownItems, own, general, "гуруҳнинг умумий ҳисоби"));
        assertEquals("1264192044-34 (гуруҳнинг умумий ҳисоби)",
                InvoiceReportService.accountOf(new BigDecimal("3600000"), ownItems, own, general, "гуруҳнинг умумий ҳисоби"));
        assertEquals("1264192044-34 (гуруҳнинг умумий ҳисоби)",
                InvoiceReportService.accountOf(new BigDecimal("600000"), ownItems, own, general, "гуруҳнинг умумий ҳисоби"));

        // Umumiy hisob yo'q — o'z hisobi; hech qanday hisob yo'q — null.
        assertEquals("N-01 (хонанинг ўз ҳисоби)",
                InvoiceReportService.accountOf(BigDecimal.TEN, new ArrayList<>(), List.of("N-01"), List.of(), "брон ҳисоби"));
        assertNull(InvoiceReportService.accountOf(BigDecimal.TEN, new ArrayList<>(), List.of(), List.of(), "брон ҳисоби"));
    }

    @Test
    void longAccountListsAreShortenedForTable() {
        assertEquals("A-01", InvoiceReportService.shortList("A-01"));
        assertEquals("A-01, A-02", InvoiceReportService.shortList("A-01, A-02"));
        assertEquals("A-1 · +63 та", InvoiceReportService.shortList(String.join(", ",
                java.util.stream.IntStream.rangeClosed(1, 64).mapToObj(i -> "A-" + i).toList())));
        assertEquals("", InvoiceReportService.shortList(""));
    }

    @Test
    void serviceLabels() {
        assertEquals("Яшаш", InvoiceReportService.label(0, "Accommodation"));
        assertEquals("Эрта кириш", InvoiceReportService.label(3, null));
        assertEquals("Кеч чиқиш", InvoiceReportService.label(4, "Late check-out"));
        assertEquals("Нонушта", InvoiceReportService.label(1, "Buffet breakfast"));
        assertEquals("Transportation services", InvoiceReportService.label(1, "Transportation services"));
    }
}
