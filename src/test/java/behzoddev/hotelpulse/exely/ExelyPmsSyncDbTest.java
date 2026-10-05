package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.TodaySnapshot;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import behzoddev.hotelpulse.service.DemoDataService;
import behzoddev.hotelpulse.service.KpiService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Exely PMS Universal API orqali to'liq sinxronlash: soxta PMS serveri → hotel_pulse_test bazasi. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "spring.main.allow-bean-definition-overriding=true",
        "app.owner.password=test-owner-password",
        "app.exely.pms-base-url=https://pms.test/api/webpms/v1",
        "app.exely.request-delay=0ms",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class ExelyPmsSyncDbTest {

    private static final String API = "https://pms.test/api/webpms/v1";
    private static final String KEY = "6ac19413-test-0000-1111-222233334444";

    @TestConfiguration
    static class MockPms {
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
    @Autowired
    private behzoddev.hotelpulse.repository.ServiceRevenueRepository serviceRevenueRepository;
    @Autowired
    private ExelyRawStore rawStore;
    @Autowired
    private ExelyVerifyService verifyService;
    @Autowired
    private org.springframework.web.context.WebApplicationContext webContext;
    @Autowired
    private behzoddev.hotelpulse.repository.UserRepository userRepository;
    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private KpiService kpiService;

    private Hotel hotel;

    @BeforeEach
    void setUp() {
        MockPms.SERVER.reset();
        hotelRepository.deleteAll();
        Hotel h = new Hotel();
        h.setName("ARDA Test Hotel");
        h.setRoomsCount(20);
        h.setExelyPmsKey(KEY);
        hotel = hotelRepository.save(h);
        demoDataService.generate(hotel);   // PMS'ga o'tganda o'chirilishi kerak
    }

    private void expectPms() {
        MockRestServiceServer s = MockPms.SERVER;
        s.expect(ExpectedCount.manyTimes(), requestTo(API + "/rooms"))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess("[{\"id\":\"1\",\"name\":\"101\",\"roomTypeId\":\"t1\"},"
                        + "{\"id\":\"2\",\"name\":\"102\",\"roomTypeId\":\"t1\"},{\"id\":\"3\",\"name\":\"201\",\"roomTypeId\":\"t2\"}]",
                        MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/bookings?state=Active")))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess(ExelyPmsSamples.NUMBERS_ACTIVE, MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/bookings?state=Cancelled")))
                .andRespond(withSuccess(ExelyPmsSamples.NUMBERS_CANCELLED, MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(API + "/bookings/20261001-508098-1001"))
                .andRespond(withSuccess(ExelyPmsSamples.BOOKING_1001, MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(API + "/bookings/20261002-508098-1002"))
                .andRespond(withSuccess(ExelyPmsSamples.BOOKING_1002, MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(API + "/bookings/20261003-508098-1003"))
                .andRespond(withSuccess(ExelyPmsSamples.BOOKING_1003_CANCELLED, MediaType.APPLICATION_JSON));
        // Har bir 30 kunlik oynaga bir xil javob — to'lovlar faqat o'z sanasi tushgan oynada yoziladi.
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/analytics/payments?")))
                .andRespond(withSuccess(ExelyPmsSamples.PAYMENTS, MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(API + "/companies"))
                .andRespond(withSuccess("[{\"id\": 77, \"name\": \"Tour LLC\", \"type\": \"Customer\"}]", MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(org.hamcrest.Matchers.matchesPattern(API + "/bookings/[^/]+/invoices\\?language=ru")))
                .andRespond(withSuccess("[{\"id\": \"inv1\", \"items\": []}]", MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/guests/")))
                .andRespond(withSuccess("{\"id\": \"g1\", \"lastName\": \"Karimov\"}", MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/analytics/services/cancelled?")))
                .andRespond(withSuccess("{\"data\": {\"services\": [], \"reservations\": []}}", MediaType.APPLICATION_JSON));
        s.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/analytics/services?")))
                .andExpect(header("X-API-KEY", KEY))
                .andRespond(withSuccess(ExelyPmsSamples.SERVICES, MediaType.APPLICATION_JSON));
    }

    private List<Booking> hotelBookings() {
        return bookingRepository.findAll().stream().filter(b -> b.getHotelId().equals(hotel.getId())).toList();
    }

    @Test
    void fullPmsSyncReplacesDemoAndBringsPaymentsAndDebt() {
        expectPms();

        ExelySyncService.SyncResult r = syncService.sync(hotel.getId());

        assertTrue(r.ok(), r.message());
        assertTrue(r.message().contains("Exely PMS"), r.message());
        assertTrue(r.message().contains("demo"), "demo o'chirilgani haqida xabar: " + r.message());

        List<Booking> rows = hotelBookings();
        assertTrue(rows.stream().allMatch(b -> b.getOrigin() == DataOrigin.EXELY_PMS), "demo qolmasligi kerak");
        assertEquals(4, rows.size(), "1001 (1 xona) + 1002 (2 xona) + 1003 (bekor)");
        assertEquals(2, rows.stream().filter(b -> b.getStatus() == BookingStatus.CHECKED_IN).count());
        assertEquals(1, rows.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).count());

        var payments = paymentRepository.findAll().stream().filter(p -> p.getHotelId().equals(hotel.getId())).toList();
        assertEquals(3, payments.size(), "2 to'lov + 1 qaytarish; bekor qilinganlar hisobga olinmaydi");
        BigDecimal net = payments.stream().map(p -> p.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("3150000").compareTo(net), "900 000 + 2 400 000 − 150 000");

        Hotel h = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertTrue(kpiService.paymentsComplete(h), "PMS — to'lovlar to'liq");
        TodaySnapshot t = kpiService.todaySnapshot(h);
        assertEquals(0, new BigDecimal("300000").compareTo(t.debt()), "qarz = PMS toPayAmount");
        assertEquals(1, t.debtorCount());
        assertNotNull(h.getPmsBookingsSyncedUntil());
        assertNotNull(h.getPmsPaymentsSyncedUntil());
        assertEquals(Boolean.TRUE, h.getExelyLastSyncOk());
        assertEquals(3, h.getRoomsCount(), "xonalar soni Exely /rooms dan");

        // Xizmatlar: 01.10 — yashash 600 000 + nonushta 90 000; 02.10 — yashash 600 000.
        var svc = serviceRevenueRepository.findAll().stream().filter(x -> x.getHotelId().equals(hotel.getId())).toList();
        assertEquals(5, svc.size(), "har qator faqat o'z oynasida yoziladi");
        assertNotNull(h.getPmsServicesFrom());
        assertTrue(h.getPmsServicesUntil().isAfter(java.time.LocalDate.now()), "kelajak ham qamraladi");
        var day = kpiService.report(h, new behzoddev.hotelpulse.kpi.Period("custom",
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 1))).stays();
        assertEquals(0, new BigDecimal("650000").compareTo(day.roomRevenue()), "yashash + kech chiqish (DRR kabi)");
        assertEquals(0, new BigDecimal("110000").compareTo(day.extrasRevenue()), "nonushta + kir yuvish");
        assertEquals(0, new BigDecimal("90000").compareTo(day.mealsRevenue()), "nonushta (Meals)");

        // Xom arxiv: Exely bergan hamma narsa saqlanadi.
        var archive = rawStore.counts(hotel.getId());
        assertEquals(3L, archive.get(ExelyRawStore.BOOKING), "3 ta bron");
        assertEquals(3L, archive.get(ExelyRawStore.INVOICES));
        assertEquals(5L, archive.get(ExelyRawStore.SERVICE), "xizmat qatorlari");
        assertEquals(1L, archive.get(ExelyRawStore.RESERVATION));
        assertEquals(5L, archive.get(ExelyRawStore.PAYMENT), "xomda bekor qilingan to'lovlar ham bor");
        assertTrue(archive.get(ExelyRawStore.GUEST) >= 1, "mehmon profillari");

        // Solishtirish: sinxronlashdan keyin sayt = Exely.
        ExelyVerifyService.Report report = verifyService.verify(hotel.getId());
        assertNull(report.error());
        assertTrue(report.ok(), () -> report.checks().toString());
        assertEquals(4, report.checks().size());
        Hotel verified = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertEquals(Boolean.TRUE, verified.getExelyVerifyOk());
        assertTrue(ExelyVerifyService.read(verified).ok(), "saqlangan natija o'qiladi");

        // Saytdan bitta xizmat qatori o'chsa — farq topiladi.
        serviceRevenueRepository.delete(serviceRevenueRepository.findAll().stream()
                .filter(x -> x.getHotelId().equals(hotel.getId())).findFirst().orElseThrow());
        ExelyVerifyService.Report broken = verifyService.verify(hotel.getId());
        assertFalse(broken.ok());
        assertTrue(broken.checks().stream().anyMatch(c -> c.name().startsWith("Xizmatlar") && !c.ok() && !c.details().isEmpty()));
        assertEquals(3L, archive.get(ExelyRawStore.ROOM));
        assertEquals(1L, archive.get(ExelyRawStore.COMPANY));
        assertTrue(r.message().contains("xonalar soni: 20 → 3"), r.message());
    }

    @Test
    void resyncIsIdempotentAndKeepsFirstSeenTime() {
        expectPms();
        assertTrue(syncService.sync(hotel.getId()).ok());
        var firstSeen = hotelBookings().stream().filter(b -> b.getExternalId().endsWith("#rs1")).findFirst().orElseThrow().getBookedAt();

        assertTrue(syncService.sync(hotel.getId()).ok());

        assertEquals(4, hotelBookings().size(), "takror qator yo'q");
        assertEquals(3, paymentRepository.findAll().stream().filter(p -> p.getHotelId().equals(hotel.getId())).count());
        assertEquals(firstSeen, hotelBookings().stream().filter(b -> b.getExternalId().endsWith("#rs1")).findFirst().orElseThrow().getBookedAt());
    }

    @Test
    void archivedDataPagesAndExcelExport() throws Exception {
        expectPms();
        assertTrue(syncService.sync(hotel.getId()).ok());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var owner = new behzoddev.hotelpulse.security.CustomUserDetails(userRepository.findAll().stream()
                .filter(u -> u.getRole() == behzoddev.hotelpulse.entity.Role.OWNER).findFirst().orElseThrow());
        var asOwner = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner);
        var get = (java.util.function.Function<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>)
                url -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(asOwner);
        var ok = org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk();
        var has = (java.util.function.Function<String, org.springframework.test.web.servlet.ResultMatcher>)
                s -> org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString(s));

        // Admin sahifasidagi arxiv kartalari — havola.
        mvc.perform(get.apply("/admin/hotels/" + hotel.getId())).andExpect(ok)
                .andExpect(has.apply("/exely/data/booking"));
        // Ro'yxat: ustunlar, yozuv va to'liq JSON tafsiloti.
        mvc.perform(get.apply("/admin/hotels/" + hotel.getId() + "/exely/data/booking")).andExpect(ok)
                .andExpect(has.apply("Bron raqami"))
                .andExpect(has.apply("20261001-508098-1001"))
                .andExpect(has.apply("raw-json"));
        // Qidiruv.
        String found = mvc.perform(get.apply("/admin/hotels/" + hotel.getId() + "/exely/data/service?q=Laundry"))
                .andExpect(ok).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(found.contains("1 ta yozuv"), "faqat kir yuvish xizmati");
        // Excel: BOM, barcha maydonlar (ichma-ich ham), barcha yozuvlar.
        byte[] csv = mvc.perform(get.apply("/admin/hotels/" + hotel.getId() + "/exely/data/booking.csv")).andExpect(ok)
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿Kalit;Bron raqami;Sana;Olingan;"), text.lines().findFirst().orElse(""));
        assertTrue(text.lines().findFirst().orElseThrow().contains("roomStays[0].totalPrice.amount"), "ichma-ich maydon ustuni");
        assertEquals(1 + 3, text.lines().count(), "sarlavha + 3 bron");
        // Noma'lum tur — 404.
        mvc.perform(get.apply("/admin/hotels/" + hotel.getId() + "/exely/data/nope"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void manualRoomsCountIsNotOverwritten() {
        expectPms();
        Hotel h = hotelRepository.findById(hotel.getId()).orElseThrow();
        h.setRoomsCount(98);
        h.setRoomsCountManual(true);
        hotelRepository.save(h);

        ExelySyncService.SyncResult r = syncService.sync(hotel.getId());

        assertTrue(r.ok(), r.message());
        assertEquals(98, hotelRepository.findById(hotel.getId()).orElseThrow().getRoomsCount(), "qo'lda kiritilgan son saqlanadi");
        assertFalse(r.message().contains("xonalar soni"), r.message());
    }

    @Test
    void wrongKeyIsRecordedAsFailure() {
        MockPms.SERVER.expect(ExpectedCount.manyTimes(), requestTo(startsWith(API + "/")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        ExelySyncService.SyncResult r = syncService.sync(hotel.getId());

        assertFalse(r.ok());
        Hotel h = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertEquals(Boolean.FALSE, h.getExelyLastSyncOk());
        assertTrue(h.getExelyLastSyncMessage().contains("kalit"));
    }
}
