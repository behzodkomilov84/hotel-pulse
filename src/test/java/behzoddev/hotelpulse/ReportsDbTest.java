package behzoddev.hotelpulse;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.kpi.Period;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.report.LayoutService;
import behzoddev.hotelpulse.report.OtbSnapshotJob;
import behzoddev.hotelpulse.report.ReportCatalog;
import behzoddev.hotelpulse.report.ReportService;
import behzoddev.hotelpulse.report.ReportTable;
import behzoddev.hotelpulse.report.UsaliExpenseService;
import behzoddev.hotelpulse.report.UsaliLine;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.DemoDataService;
import behzoddev.hotelpulse.service.KpiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Hisobotlar: barcha jadval hisobotlari, ekran tarkibi (view/edit), USALI xarajatlari, talab suratlari. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class ReportsDbTest {

    @Autowired private ReportService reportService;
    @Autowired private LayoutService layoutService;
    @Autowired private UsaliExpenseService expenseService;
    @Autowired private OtbSnapshotJob snapshotJob;
    @Autowired private KpiService kpiService;
    @Autowired private DemoDataService demoDataService;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;

    private Hotel hotel;
    private CustomUserDetails owner;
    private CustomUserDetails staff;
    private MockMvc mvc;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        today = kpiService.today();
        Hotel h = new Hotel();
        h.setName("Report Hotel");
        h.setRoomsCount(20);
        hotel = hotelRepository.save(h);
        demoDataService.generate(hotel);
        // Xona turi, xona va komissiyali PMS yashashi + xom Exely xona ma'lumotlari.
        Booking b = new Booking();
        b.setHotelId(hotel.getId());
        b.setOrigin(DataOrigin.EXELY_PMS);
        b.setExternalId("pms:R-1#rs1");
        b.setSource("Booking.com");
        b.setStatus(BookingStatus.CHECKED_IN);
        b.setGuestName("Karimov Aziz");
        b.setArrivalDate(today.minusDays(1));
        b.setDepartureDate(today.plusDays(2));
        b.setTotalAmount(new BigDecimal("900000"));
        b.setBalanceDue(new BigDecimal("300000"));
        b.setBookedAt(LocalDateTime.now().minusDays(10));
        b.setRoomTypeId("T1");
        b.setRoomId("R7");
        b.setAgentCommission(new BigDecimal("135000"));
        bookingRepository.save(b);
        jdbc.update("insert into exely_raw (hotel_id, kind, external_id, payload, fetched_at) values (?, 'room_type', 'T1', ?, now())",
                hotel.getId(), "{\"id\": \"T1\", \"name\": \"Deluxe King\"}");
        jdbc.update("insert into exely_raw (hotel_id, kind, external_id, payload, fetched_at) values (?, 'room', 'R7', ?, now())",
                hotel.getId(), "{\"id\": \"R7\", \"name\": \"307\", \"roomTypeId\": \"T1\"}");

        owner = new CustomUserDetails(saveUser("rep-owner", Role.HOTEL_OWNER));
        staff = new CustomUserDetails(saveUser("rep-staff", Role.HOTEL_STAFF));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void everyTableReportBuildsRendersAndExports() throws Exception {
        Period month = Period.resolve("month", null, null, today);
        for (ReportCatalog.ReportDef d : ReportCatalog.all()) {
            if (!d.isTable()) {
                continue;
            }
            ReportTable t = reportService.build(d.key(), hotel, month);
            assertNotNull(t, d.key());
            assertFalse(t.getColumns().isEmpty(), d.key());
            String page = html("/reports/" + d.key() + "?hotel=" + hotel.getId() + "&period=month", owner);
            assertTrue(page.contains("class=\"rt\"") || page.contains("ma'lumot yo'q"), d.key());
            var csv = mvc.perform(get("/reports/" + d.key() + "/export.csv").param("hotel", hotel.getId().toString())
                    .param("period", "30d").with(user(owner))).andReturn().getResponse();
            assertEquals(200, csv.getStatus(), d.key());
            assertTrue(new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8).startsWith("﻿" + t.getColumns().get(0).label()), d.key());
        }
        // Xona turi, xona raqami va komissiya xom Exely ma'lumotidan.
        ReportTable inhouse = reportService.build("inhouse", hotel, Period.resolve("today", null, null, today));
        assertTrue(inhouse.getRows().stream().anyMatch(r -> r.cells().get(0).text().equals("307")
                && r.cells().get(1).text().equals("Deluxe King")), "xona va xona turi nomi");
        ReportTable agents = reportService.build("agents", hotel, Period.resolve("custom", today.minusDays(5), today, today));
        assertTrue(agents.getRows().stream().anyMatch(r -> r.cells().get(0).text().equals("Booking.com")
                && r.cells().get(4).text().replaceAll("\\D", "").equals("135000")), "agent komissiyasi");

        // Katalog: barcha hisobotlar, Exely bermaydiganlari belgilangan.
        String catalog = html("/reports", owner);
        assertTrue(catalog.contains("Xonalarni tozalash") && catalog.contains("Exely bermaydi"));
        assertTrue(html("/reports/housekeeping", owner).contains("API orqali"));
        // Menyu barcha sahifalarda.
        assertTrue(html("/", owner).contains("/reports/usali-summary"));
    }

    @Test
    void workspaceLayoutIsPerUserAddReorderReset() throws Exception {
        assertEquals(ReportCatalog.DEFAULT_LAYOUT, layoutService.get(owner.getId()));
        String page = html("/hotels/" + hotel.getId(), owner);
        assertTrue(page.contains("id=\"block-kpi\"") && page.contains("data-ws-edit"));
        assertFalse(page.contains("id=\"block-arrivals\""));
        // Standart: davr paneli "Bugun"dan keyin (avvalgi sahifa kabi).
        assertTrue(page.indexOf("class=\"period-bar") > page.indexOf("id=\"block-today\"")
                && page.indexOf("class=\"period-bar") < page.indexOf("id=\"block-kpi\""));
        assertEquals(1, page.split("class=\"period-bar", -1).length - 1, "davr paneli bitta");

        // Ro'yxatdan qo'shish — joriy (tahrirlangan) tartib bilan.
        String back = "/hotels/" + hotel.getId();
        mvc.perform(post("/reports/layout/add").with(user(owner)).with(csrf())
                .param("key", "arrivals").param("keys", "flow,today,kpi").param("back", back));
        assertEquals(List.of("flow", "today", "kpi", "arrivals"), layoutService.get(owner.getId()));
        page = html(back, owner);
        assertTrue(page.contains("id=\"block-arrivals\""));
        assertTrue(page.indexOf("id=\"block-flow\"") < page.indexOf("id=\"block-today\""), "tartib saqlangan");
        assertFalse(page.contains("dailyChart\""), "olib tashlangan grafik yo'q");
        assertTrue(page.indexOf("class=\"period-bar") < page.indexOf("id=\"block-flow\""), "Bugun birinchi emas — panel tepada");

        // Tartib saqlash: noma'lum va mavjud bo'lmagan kalitlar tashlanadi.
        mvc.perform(post("/reports/layout").with(user(owner)).with(csrf())
                .param("keys", "arrivals,kpi,housekeeping,nonsense,kpi").param("back", back));
        assertEquals(List.of("arrivals", "kpi"), layoutService.get(owner.getId()));
        // Boshqa foydalanuvchiga ta'sir qilmaydi.
        assertEquals(ReportCatalog.DEFAULT_LAYOUT, layoutService.get(staff.getId()));
        // Ochiq redirect yo'q.
        String loc = mvc.perform(post("/reports/layout").with(user(owner)).with(csrf()).param("keys", "kpi").param("back", "https://evil.example"))
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals("/services/tasks", loc);

        mvc.perform(post("/reports/layout/reset").with(user(owner)).with(csrf()).param("back", back));
        assertEquals(ReportCatalog.DEFAULT_LAYOUT, layoutService.get(owner.getId()));

        // Hamma mavjud hisobot bitta ekranda ham ochiladi.
        layoutService.save(owner.getId(), ReportCatalog.all().stream().filter(ReportCatalog.ReportDef::isAvailable)
                .map(ReportCatalog.ReportDef::key).toList());
        assertTrue(html(back + "?period=30d", owner).contains("id=\"block-usali-summary\""));
    }

    @Test
    void usaliExpensesDriveGop() throws Exception {
        YearMonth m = YearMonth.from(today);
        mvc.perform(post("/reports/usali/expenses").with(user(owner)).with(csrf())
                .param("hotel", hotel.getId().toString()).param("month", m.toString())
                .param("line_ROOMS_PAYROLL", "12 500 000").param("line_UTILITIES", "3000000").param("line_AG", ""));
        Map<UsaliLine, BigDecimal> saved = expenseService.month(hotel.getId(), m);
        assertEquals(0, new BigDecimal("12500000").compareTo(saved.get(UsaliLine.ROOMS_PAYROLL)));
        assertFalse(saved.containsKey(UsaliLine.AG));

        Period p = new Period("custom", m.atDay(1), m.atEndOfMonth());
        BigDecimal revenue = kpiService.metrics(hotel, p).totalRevenue();
        ReportTable summary = reportService.build("usali-summary", hotel, p);
        String gopRow = summary.getRows().stream().filter(r -> r.cells().get(0).text().startsWith("GOP"))
                .findFirst().orElseThrow().cells().get(1).text().replaceAll("[^0-9-]", "");
        // GOP = daromad − (Rooms ish haqi + OTA komissiyasi (shu oyga tushgan qismi)) − kommunal.
        long gop = Long.parseLong(gopRow);
        long upper = revenue.subtract(new BigDecimal("15500000")).longValue();
        assertTrue(gop <= upper && gop >= upper - 135000, "GOP: " + gop + " ≈ " + upper);

        assertTrue(html("/reports/usali/expenses?hotel=" + hotel.getId() + "&month=" + m, owner).contains("12 500 000"));
        assertEquals(403, mvc.perform(get("/reports/usali/expenses").with(user(staff))).andReturn().getResponse().getStatus());
    }

    @Test
    void demandSnapshotsGivePickup() {
        snapshotJob.capture(hotel, today.minusDays(1));
        // Kecha surat olingandan keyin yangi bron — ertaga uchun +1.
        Booking b = new Booking();
        b.setHotelId(hotel.getId());
        b.setOrigin(DataOrigin.EXELY_PMS);
        b.setExternalId("pms:R-2#rs1");
        b.setSource("Direct");
        b.setStatus(BookingStatus.CONFIRMED);
        b.setArrivalDate(today.plusDays(1));
        b.setDepartureDate(today.plusDays(2));
        b.setTotalAmount(new BigDecimal("500000"));
        b.setBookedAt(LocalDateTime.now());
        bookingRepository.save(b);

        ReportTable t = reportService.build("demand-intensity", hotel, Period.resolve("month", null, null, today));
        ReportTable.Row tomorrow = t.getRows().get(1);
        assertEquals("+1", tomorrow.cells().get(4).text());
        assertEquals("—", tomorrow.cells().get(5).text(), "7 kunlik surat yo'q");
    }

    // ---------------------------------------------------------------- yordamchilar

    private String html(String url, CustomUserDetails u) throws Exception {
        var res = mvc.perform(get(url).with(user(u))).andReturn().getResponse();
        assertEquals(200, res.getStatus(), url);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private User saveUser(String username, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}x");
        u.setRole(role);
        u.getHotels().addAll(Set.of(hotel));
        return userRepository.save(u);
    }
}
