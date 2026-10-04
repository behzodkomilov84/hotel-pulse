package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * To'liq sinxronlash oqimi: soxta Exely serveri (hujjatdagi JSON namunalari)
 * → ExelySyncService → hotel_pulse_test bazasi.
 */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "spring.main.allow-bean-definition-overriding=true",
        "app.owner.password=test-owner-password",
        "app.exely.base-url=https://exely.test",
        "app.exely.request-delay=0ms",
        "app.exely.scheduler-enabled=false"
})
class ExelySyncDbTest {

    @TestConfiguration
    static class MockExely {
        static final RestClient.Builder BUILDER = RestClient.builder();
        static final MockRestServiceServer SERVER = MockRestServiceServer.bindTo(BUILDER).ignoreExpectOrder(true).build();

        @Bean
        RestClient.Builder exelyRestClientBuilder() {
            return BUILDER;
        }
    }

    @Autowired
    private ExelySyncService syncService;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    private Hotel hotel;

    @BeforeEach
    void setUp() {
        MockExely.SERVER.reset();
        hotelRepository.deleteAll();
        Hotel h = new Hotel();
        h.setName("Exely Test Hotel");
        h.setRoomsCount(20);
        h.setExelyPropertyId("500821");
        h.setExelyClientId("client-1");
        h.setExelyClientSecret("secret-1");
        hotel = hotelRepository.save(h);
    }

    @Test
    void fullSyncStoresBookingsPaymentsAndContinueToken() {
        MockRestServiceServer server = MockExely.SERVER;
        server.expect(ExpectedCount.between(0, 5), requestTo("https://exely.test/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://exely.test/api/read-reservation/v1/properties/500821/bookings?count=1000&lastModification=")))
                .andRespond(withSuccess(ExelySamples.SUMMARIES_PAGE_1, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://exely.test/api/read-reservation/v1/properties/500821/bookings?count=1000&continueToken=TOKEN-1"))
                .andRespond(withSuccess(ExelySamples.SUMMARIES_PAGE_2, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://exely.test/api/read-reservation/v1/properties/500821/bookings/20230622-500821-12025196"))
                .andRespond(withSuccess(ExelySamples.BOOKING_ACTIVE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://exely.test/api/read-reservation/v1/properties/500821/bookings/20210325-500360-6987835"))
                .andRespond(withSuccess(ExelySamples.BOOKING_CANCELLED, MediaType.APPLICATION_JSON));

        ExelySyncService.SyncResult result = syncService.sync(hotel.getId());

        assertTrue(result.ok(), result.message());
        assertEquals(2, result.processed());
        server.verify();

        List<Booking> rows = bookingRepository.findAll().stream()
                .filter(b -> b.getHotelId().equals(hotel.getId()))
                .sorted(Comparator.comparing(Booking::getExternalId))
                .toList();
        assertEquals(3, rows.size(), "faol bron 2 ta xona + bekor qilingan 1 ta");
        assertTrue(rows.stream().allMatch(b -> b.getOrigin() == DataOrigin.EXELY));
        assertEquals(BookingStatus.CANCELLED, rows.get(0).getStatus());
        assertEquals("20230622-500821-12025196#0", rows.get(1).getExternalId());

        assertEquals(1, paymentRepository.findAll().stream()
                .filter(p -> p.getHotelId().equals(hotel.getId()) && p.getExternalId().endsWith("#prepaid")).count());

        Hotel after = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertEquals("TOKEN-2", after.getExelyContinueToken());
        assertEquals(Boolean.TRUE, after.getExelyLastSyncOk());
        assertNotNull(after.getExelyLastSyncAt());
        assertEquals("2 ta bron yangilandi", after.getExelyLastSyncMessage());
    }

    @Test
    void resyncOfModifiedBookingReplacesRowsWithoutDuplicates() {
        MockRestServiceServer server = MockExely.SERVER;
        hotel.setExelyContinueToken("TOKEN-X");
        hotelRepository.save(hotel);

        server.expect(ExpectedCount.between(0, 5), requestTo("https://exely.test/auth/token"))
                .andRespond(withSuccess(ExelySamples.TOKEN, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.twice(),
                        requestTo("https://exely.test/api/read-reservation/v1/properties/500821/bookings?count=1000&continueToken=TOKEN-X"))
                .andRespond(withSuccess(ExelySamples.SUMMARIES_PAGE_1.replace("\"hasMoreData\": true", "\"hasMoreData\": false")
                        .replace("TOKEN-1", "TOKEN-X"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.twice(),
                        requestTo("https://exely.test/api/read-reservation/v1/properties/500821/bookings/20230622-500821-12025196"))
                .andRespond(withSuccess(ExelySamples.BOOKING_ACTIVE, MediaType.APPLICATION_JSON));

        assertTrue(syncService.sync(hotel.getId()).ok());
        assertTrue(syncService.sync(hotel.getId()).ok());

        long rows = bookingRepository.findAll().stream().filter(b -> b.getHotelId().equals(hotel.getId())).count();
        assertEquals(2, rows, "qayta sinxronlash takror qator yaratmasligi kerak");
        long payments = paymentRepository.findAll().stream().filter(p -> p.getHotelId().equals(hotel.getId())).count();
        assertEquals(1, payments);
    }

    @Test
    void wrongCredentialsAreRecordedAsFailedStatus() {
        MockExely.SERVER.expect(requestTo("https://exely.test/auth/token"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        ExelySyncService.SyncResult result = syncService.sync(hotel.getId());

        assertFalse(result.ok());
        Hotel after = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertEquals(Boolean.FALSE, after.getExelyLastSyncOk());
        assertTrue(after.getExelyLastSyncMessage().contains("client_secret"));
    }
}
