package behzoddev.hotelpulse.exely;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExelyPmsClientTest {

    private static final String BASE = "https://pms.test/api/webpms/v1";
    private static final String KEY = "test-key-0000-1111";

    private MockRestServiceServer server;
    private ExelyPmsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ExelyPmsClient(new ExelyProperties(null, BASE, 400, 1000, Duration.ZERO, false), builder);
    }

    @Test
    void searchSendsKeyHeaderAndDocumentedDateFormat() {
        server.expect(requestTo(BASE + "/bookings?state=Active&modifiedFrom=2026-09-01T10:05&modifiedTo=2026-10-01T10:05"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess(ExelyPmsSamples.NUMBERS_ACTIVE, MediaType.APPLICATION_JSON));

        List<String> numbers = client.modifiedBookings(KEY, "Active",
                LocalDateTime.of(2026, 9, 1, 10, 5), LocalDateTime.of(2026, 10, 1, 10, 5));

        assertEquals(List.of("20261001-508098-1001", "20261002-508098-1002"), numbers);
        server.verify();
    }

    @Test
    void bookingDetailsAreParsed() {
        server.expect(requestTo(BASE + "/bookings/20261001-508098-1001"))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess(ExelyPmsSamples.BOOKING_1001, MediaType.APPLICATION_JSON));

        ExelyPmsApi.Booking b = client.booking(KEY, "20261001-508098-1001");

        assertEquals("UZS", b.currencyId());
        assertEquals("Booking.com", b.sourceChannelName());
        ExelyPmsApi.RoomStay rs = b.roomStays().get(0);
        assertEquals("CheckedOut", rs.status());
        assertEquals(0, new BigDecimal("300000").compareTo(rs.totalPrice().toPayAmount()));
        assertEquals(2, rs.guestCountInfo().adults());
    }

    @Test
    void paymentsUseAnalyticsDateFormat() {
        server.expect(requestTo(BASE + "/analytics/payments?startDateTime=202609010000&endDateTime=202610010000&includeExternalPayments=true"))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess(ExelyPmsSamples.PAYMENTS, MediaType.APPLICATION_JSON));

        List<ExelyPmsApi.Payment> p = client.payments(KEY,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 10, 1, 0, 0));

        assertEquals(5, p.size());
        assertEquals("202610031445", p.get(2).cancellationDateTime());
        assertEquals(1, p.get(3).actionKind());
    }

    @Test
    void errorsAreTranslated() {
        server.expect(requestTo(BASE + "/rooms")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        ExelyException e = assertThrows(ExelyException.class, () -> client.testKey(KEY));
        assertTrue(e.getMessage().contains("kalit noto'g'ri"));

        server.reset();
        server.expect(requestTo(BASE + "/bookings/X")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"E1\",\"message\":\"Period is too long\"}]}"));
        ExelyException bad = assertThrows(ExelyException.class, () -> client.booking(KEY, "X"));
        assertTrue(bad.getMessage().contains("Period is too long"), bad.getMessage());

        server.reset();
        server.expect(requestTo(BASE + "/bookings/Y")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertTrue(assertThrows(ExelyException.class, () -> client.booking(KEY, "Y")).isRateLimited());
    }
}
