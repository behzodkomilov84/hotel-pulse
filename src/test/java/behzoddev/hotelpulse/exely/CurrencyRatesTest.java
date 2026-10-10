package behzoddev.hotelpulse.exely;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CurrencyRatesTest {

    private static final String CBU = "https://cbu.test/uz/arkhiv-kursov-valyut/json/";
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T07:00:00Z"), ZoneId.of("Asia/Tashkent"));

    private static String rate(String ccy, String rate, String nominal) {
        return "[{\"id\":1,\"Code\":\"840\",\"Ccy\":\"" + ccy + "\",\"Nominal\":\"" + nominal + "\",\"Rate\":\""
                + rate + "\",\"Date\":\"01.10.2026\"}]";
    }

    @Test
    void convertsUsdToUzsWithCachingAndKeepsSameCurrency() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(CBU + "USD/2026-10-01/"))
                .andRespond(withSuccess(rate("USD", "11808.76", "1"), MediaType.APPLICATION_JSON));
        CurrencyRates rates = new CurrencyRates(builder, "https://cbu.test", clock);
        LocalDate day = LocalDate.of(2026, 10, 1);

        assertEquals(new BigDecimal("1017797.02"), rates.convert(new BigDecimal("86.19"), "USD", "UZS", day));
        // Ikkinchi marta — keshdan (so'rov bitta).
        assertEquals(new BigDecimal("822007.78"), rates.convert(new BigDecimal("69.61"), "usd", "UZS", day));
        assertEquals(new BigDecimal("500000"), rates.convert(new BigDecimal("500000"), "UZS", "UZS", day));
        assertEquals(new BigDecimal("500000"), rates.convert(new BigDecimal("500000"), null, "UZS", day));
        server.verify();
    }

    @Test
    void futureDatesUseTodayAndNominalIsApplied() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(CBU + "RUB/2026-10-05/"))
                .andRespond(withSuccess(rate("RUB", "1450.00", "10"), MediaType.APPLICATION_JSON));
        CurrencyRates rates = new CurrencyRates(builder, "https://cbu.test", clock);

        assertEquals(new BigDecimal("14500.00"), rates.convert(new BigDecimal("100"), "RUB", "UZS", LocalDate.of(2027, 1, 1)));
    }

    @Test
    void unavailableRateStopsSyncForRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(CBU + "USD/2026-10-01/")).andRespond(withServerError());
        CurrencyRates rates = new CurrencyRates(builder, "https://cbu.test", clock);

        ExelyException e = assertThrows(ExelyException.class,
                () -> rates.convert(BigDecimal.TEN, "USD", "UZS", LocalDate.of(2026, 10, 1)));
        assertTrue(e.isRateLimited(), "кейинги сиклда қайта уриниш керак — курсорни силжитмаслик");
    }
}
