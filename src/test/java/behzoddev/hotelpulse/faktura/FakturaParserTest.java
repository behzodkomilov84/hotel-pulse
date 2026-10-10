package behzoddev.hotelpulse.faktura;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FakturaParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    @Test
    void flatPascalCaseDocument() {
        JsonNode doc = json("""
                {"UniqueId":"7f3c-01","DocumentType":2,"Status":24,"DocumentNumber":"15","DocumentDate":"2026-09-30T00:00:00",
                 "SellerTin":"301 234 567","SellerName":"\\"Toza Suv\\" MChJ","BuyerTin":"309999999","BuyerName":"Hotel",
                 "TotalPrice":1000000,"TotalVat":120000,"TotalPriceWithVat":1120000}""");

        FakturaParser.Parsed in = FakturaParser.parse(doc, true);
        assertEquals("7f3c-01", in.uniqueId());
        assertEquals(2, in.type());
        assertEquals(24, in.status());
        assertEquals("15", in.number());
        assertEquals(LocalDate.of(2026, 9, 30), in.date());
        assertEquals("301234567", in.counterpartyInn());
        assertEquals("\"Toza Suv\" MChJ", in.counterpartyName());
        assertEquals(0, new BigDecimal("1000000").compareTo(in.net()));

        FakturaParser.Parsed out = FakturaParser.parse(doc, false);
        assertEquals("309999999", out.counterpartyInn());
        assertEquals("Hotel", out.counterpartyName());
    }

    @Test
    void importModelSnakeCaseDocument() {
        JsonNode doc = json("""
                {"id":"x1","head":{"sender":{"sender_info":{"INN":"301234567","company_name":"Supplier"}},
                                   "receiver":{"receiver_info":{"INN":"309999999","company_name":"Hotel LLC"}}},
                 "document":{"document_number":"A-7","document_date":"01.10.2026"},
                 "column_summary_values":{"column_vat_value":"12 000","column_subtotal_with_taxes":"112 000"}}""");

        FakturaParser.Parsed in = FakturaParser.parse(doc, true);
        assertEquals("x1", in.uniqueId());
        assertEquals("A-7", in.number());
        assertEquals(LocalDate.of(2026, 10, 1), in.date());
        assertEquals("301234567", in.counterpartyInn());
        assertEquals("Supplier", in.counterpartyName());
        assertNull(in.total());
        assertEquals(0, new BigDecimal("100000").compareTo(in.net()));

        assertEquals("Hotel LLC", FakturaParser.parse(doc, false).counterpartyName());
    }

    @Test
    void datesInVariousFormats() {
        assertEquals(LocalDate.of(2026, 10, 1), FakturaParser.parseDate("/Date(1790812800000)/"));
        assertEquals(LocalDate.of(2026, 10, 1), FakturaParser.parseDate("2026-09-30T20:00:00Z"));
        assertEquals(LocalDate.of(2026, 10, 1), FakturaParser.parseDate("1790812800"));
        assertEquals(LocalDate.of(2026, 10, 1), FakturaParser.parseDate("01.10.2026 12:00"));
        assertNull(FakturaParser.parseDate("kecha"));
    }

    @Test
    void namesAreNormalized() {
        assertEquals("toza suv", FakturaParser.normalizeName("\"TOZA SUV\" MChJ"));
        assertEquals("tashkent travel", FakturaParser.normalizeName("ООО «Tashkent Travel»"));
        assertEquals("o'zbekiston temir yo'llari", FakturaParser.normalizeName("AJ \"O‘zbekiston temir yo‘llari\""));
    }
}
