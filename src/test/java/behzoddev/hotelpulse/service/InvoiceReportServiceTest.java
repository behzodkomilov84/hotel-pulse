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

        assertEquals("1264192044-35 (xonaning o'z hisobi)",
                InvoiceReportService.accountOf(new BigDecimal("38986000"), ownItems, own, general, "guruhning umumiy hisobi"));
        assertEquals("1264192044-34 (guruhning umumiy hisobi)",
                InvoiceReportService.accountOf(new BigDecimal("3600000"), ownItems, own, general, "guruhning umumiy hisobi"));
        assertEquals("1264192044-34 (guruhning umumiy hisobi)",
                InvoiceReportService.accountOf(new BigDecimal("600000"), ownItems, own, general, "guruhning umumiy hisobi"));

        // Umumiy hisob yo'q — o'z hisobi; hech qanday hisob yo'q — null.
        assertEquals("N-01 (xonaning o'z hisobi)",
                InvoiceReportService.accountOf(BigDecimal.TEN, new ArrayList<>(), List.of("N-01"), List.of(), "bron hisobi"));
        assertNull(InvoiceReportService.accountOf(BigDecimal.TEN, new ArrayList<>(), List.of(), List.of(), "bron hisobi"));
    }

    @Test
    void serviceLabels() {
        assertEquals("Yashash", InvoiceReportService.label(0, "Accommodation"));
        assertEquals("Erta kirish", InvoiceReportService.label(3, null));
        assertEquals("Kech chiqish", InvoiceReportService.label(4, "Late check-out"));
        assertEquals("Nonushta", InvoiceReportService.label(1, "Buffet breakfast"));
        assertEquals("Transportation services", InvoiceReportService.label(1, "Transportation services"));
    }
}
