package behzoddev.hotelpulse.exely;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExelyClientTest {

    private static final String BASE = "https://exely.test";
    private static final ExelyClient.Credentials CREDS = new ExelyClient.Credentials("500821", "client-1", "secret-1");

    private MockRestServiceServer server;
    private ExelyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ExelyProperties props = new ExelyProperties(BASE, 400, 1000, Duration.ZERO, false);
        client = new ExelyClient(props, builder, Clock.fixed(Instant.parse("2026-10-04T05:00:00Z"), ZoneId.of("Asia/Tashkent")));
    }

    @Test
    void tokenIsRequestedWithClientCredentialsAndCached() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials", "client_id", "client-1", "client_secret", "secret-1")))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings?count=1000&lastModification=2025-01-01T00:00:00Z"))
                .andExpect(header("Authorization", "Bearer jwt-token-1"))
                .andRespond(withSuccess(ExelySamples.SUMMARIES_PAGE_1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings?count=1000&continueToken=TOKEN-1"))
                .andExpect(header("Authorization", "Bearer jwt-token-1"))
                .andRespond(withSuccess(ExelySamples.SUMMARIES_PAGE_2, MediaType.APPLICATION_JSON));

        ExelyApi.BookingSummaries p1 = client.listBookings(CREDS, Instant.parse("2025-01-01T00:00:00Z"), null, 1000);
        ExelyApi.BookingSummaries p2 = client.listBookings(CREDS, null, "TOKEN-1", 1000);

        assertTrue(p1.hasMoreData());
        assertEquals("TOKEN-1", p1.continueToken());
        assertEquals("20230622-500821-12025196", p1.bookingSummaries().get(0).number());
        assertFalse(p2.hasMoreData());
        assertEquals("Cancelled", p2.bookingSummaries().get(0).status());
        server.verify();
    }

    @Test
    void bookingDetailsAreParsed() {
        server.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings/20210325-500360-6987835"))
                .andRespond(withSuccess(ExelySamples.BOOKING_CANCELLED, MediaType.APPLICATION_JSON));

        ExelyApi.Booking b = client.getBooking(CREDS, "20210325-500360-6987835");

        assertEquals("Cancelled", b.status());
        assertEquals("EUR", b.currencyCode());
        assertEquals(1, b.roomStays().size());
        assertEquals("2021-03-25T14:00", b.roomStays().get(0).stayDates().arrivalDateTime());
        assertEquals(0, new BigDecimal("28").compareTo(b.roomStays().get(0).total().priceAfterTax()));
        assertEquals("2021-03-19T15:18:53Z", b.cancellation().cancelledDateTime());
        assertEquals("PA2", b.source().code());
        assertNull(b.customer());
    }

    @Test
    void expiredTokenIsRefreshedOnceOn401() {
        server.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings/N1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN.replace("jwt-token-1", "jwt-token-2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings/N1"))
                .andExpect(header("Authorization", "Bearer jwt-token-2"))
                .andRespond(withSuccess(ExelySamples.BOOKING_CANCELLED, MediaType.APPLICATION_JSON));

        assertNotNull(client.getBooking(CREDS, "N1"));
        server.verify();
    }

    @Test
    void wrongCredentialsGiveClearMessage() {
        server.expect(requestTo(BASE + "/auth/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        ExelyException e = assertThrows(ExelyException.class, () -> client.getBooking(CREDS, "N1"));
        assertTrue(e.getMessage().contains("client_secret"));
    }

    @Test
    void forbiddenAndRateLimitAreTranslated() {
        server.expect(requestTo(BASE + "/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings/N1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(BASE + "/api/read-reservation/v1/properties/500821/bookings/N2"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        ExelyException forbidden = assertThrows(ExelyException.class, () -> client.getBooking(CREDS, "N1"));
        assertTrue(forbidden.getMessage().contains("Read Reservation API"));
        assertFalse(forbidden.isRateLimited());
        ExelyException limited = assertThrows(ExelyException.class, () -> client.getBooking(CREDS, "N2"));
        assertTrue(limited.isRateLimited());
    }

    @Test
    void secretIsNotInToString() {
        assertFalse(CREDS.toString().contains("secret-1"));
    }
}
