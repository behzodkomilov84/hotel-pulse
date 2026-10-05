package behzoddev.hotelpulse;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.DebtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Qarzdorlik hisoboti (hotel_pulse_test bazasi). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class DebtReportDbTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private DebtService debtService;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private Clock clock;

    private Hotel hotel;
    private Hotel other;
    private LocalDate today;
    private int seq;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        today = LocalDate.now(clock);
        hotel = hotel("Debt Hotel");
        other = hotel("Other Hotel");

        // PMS: ketgan, qarz 300 000, 40 kun oldin ketgan
        pms("Karimov", BookingStatus.CHECKED_OUT, today.minusDays(43), today.minusDays(40), "1000000", "300000");
        // PMS: hozir yashayapti, qarz 200 000
        pms("Smith", BookingStatus.CHECKED_IN, today.minusDays(1), today.plusDays(2), "600000", "200000");
        // PMS: ketish sanasi 100 kun oldin o'tgan, lekin hamon CheckedIn — vyselenie qilinmagan, qarz 500 000
        pms("Valiyev", BookingStatus.CHECKED_IN, today.minusDays(103), today.minusDays(100), "500000", "500000");
        // PMS: kelmagan (CONFIRMED, sanasi o'tgan) va bekor qilingan — qarz emas
        pms("NoShow", BookingStatus.CONFIRMED, today.minusDays(5), today.minusDays(3), "400000", "400000");
        pms("Cancelled", BookingStatus.CANCELLED, today.minusDays(5), today.minusDays(3), "400000", "0");
        // PMS: to'liq to'langan
        pms("Paid", BookingStatus.CHECKED_OUT, today.minusDays(10), today.minusDays(8), "700000", "0");

        // Qoldig'i noma'lum manba (qo'lda): narx 800 000, to'langan 500 000 → qarz 300 000
        Booking manual = booking(hotel, "Rashidov", BookingStatus.CHECKED_OUT, today.minusDays(20), today.minusDays(15), "800000", null, DataOrigin.MANUAL);
        Payment p = new Payment();
        p.setHotelId(hotel.getId());
        p.setBookingId(manual.getId());
        p.setOrigin(DataOrigin.MANUAL);
        p.setExternalId("m-1");
        p.setAmount(new BigDecimal("500000"));
        p.setPaidAt(today.minusDays(15).atTime(11, 0));
        paymentRepository.save(p);

        // Boshqa mehmonxona qarzi — bu hisobotga tushmasligi kerak
        booking(other, "Begona", BookingStatus.CHECKED_OUT, today.minusDays(5), today.minusDays(3), "999000", "999000", DataOrigin.EXELY_PMS);
    }

    @Test
    void summaryCategoriesAndAging() {
        DebtReport r = debtService.report(hotel, null, null, null);
        DebtReport.Summary s = r.summary();

        assertEquals(4, s.count(), "kelmagan, bekor qilingan va to'langanlar kirmaydi");
        assertEquals(0, new BigDecimal("1300000").compareTo(s.total()));
        assertEquals(0, new BigDecimal("200000").compareTo(s.inHouse()));
        assertEquals(0, new BigDecimal("600000").compareTo(s.checkedOut()), "PMS 300 000 + qo'lda 300 000");
        assertEquals(0, new BigDecimal("500000").compareTo(s.notCheckedOut()));
        assertEquals(1, s.notCheckedOutCount());

        // Standart saralash — eng katta qarz birinchi.
        assertEquals("Valiyev", r.rows().get(0).guestName());
        assertEquals(100, r.rows().get(0).ageDays());

        // Yosh: 0–30 (Smith 0, Rashidov 15) = 500 000; 31–60 (Karimov 40) = 300 000; 90+ (Valiyev) = 500 000
        assertEquals(0, new BigDecimal("500000").compareTo(r.aging().get(0).amount()));
        assertEquals(0, new BigDecimal("300000").compareTo(r.aging().get(1).amount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.aging().get(2).amount()));
        assertEquals(0, new BigDecimal("500000").compareTo(r.aging().get(3).amount()));
    }

    @Test
    void filterSearchAndSort() {
        assertEquals(1, debtService.report(hotel, DebtReport.Category.NOT_CHECKED_OUT, null, null).rows().size());
        assertEquals("Smith", debtService.report(hotel, null, "smi", null).rows().get(0).guestName());
        assertEquals(1, debtService.report(hotel, null, "pms-num-1", null).rows().size(), "bron raqami bo'yicha");
        var byAge = debtService.report(hotel, null, null, "age").rows();
        assertEquals("Valiyev", byAge.get(0).guestName());
        assertEquals("Karimov", byAge.get(1).guestName());
        // Filtr kartochkalardagi umumiy raqamni o'zgartirmaydi.
        assertEquals(4, debtService.report(hotel, DebtReport.Category.IN_HOUSE, null, null).summary().count());
    }

    @Test
    void pageAndCsvRenderAndAccessIsChecked() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        CustomUserDetails owner = new CustomUserDetails(userRepository.findByUsername("owner")
                .orElseGet(() -> saveUser("owner-d", Role.OWNER, Set.of())));

        mvc.perform(get("/hotels/{id}/debts", hotel.getId()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vyselenie qilinmagan")))
                .andExpect(content().string(containsString("Valiyev")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Begona"))));

        mvc.perform(get("/hotels/{id}", hotel.getId()).with(user(owner)))
                .andExpect(content().string(containsString("/hotels/" + hotel.getId() + "/debts")));

        byte[] csv = mvc.perform(get("/hotels/{id}/debts.csv", hotel.getId()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("qarzdorlik-")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿Bron raqami;Mehmon"), "BOM + sarlavha");
        assertEquals(5, text.lines().count(), "sarlavha + 4 qator");
        assertTrue(text.contains("pms-num-3;Valiyev;"));

        CustomUserDetails stranger = new CustomUserDetails(saveUser("stranger", Role.HOTEL_OWNER, Set.of(other)));
        mvc.perform(get("/hotels/{id}/debts", hotel.getId()).with(user(stranger))).andExpect(status().isForbidden());
        mvc.perform(get("/hotels/{id}/debts.csv", hotel.getId()).with(user(stranger))).andExpect(status().isForbidden());
    }

    @Test
    void analysisRendersOnRequestAndAccessIsChecked() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        CustomUserDetails owner = new CustomUserDetails(saveUser("owner-an", Role.OWNER, Set.of()));

        // Sahifaning o'zida tahlil yo'q — faqat tugma (tahlil qo'lda boshlanadi).
        mvc.perform(get("/hotels/{id}/debts", hotel.getId()).with(user(owner)))
                .andExpect(content().string(containsString("Qarzdorlik tahlili")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<h4>Asosiy xulosa"))));

        mvc.perform(get("/hotels/{id}/debts/analysis", hotel.getId()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))))
                .andExpect(content().string(containsString("Asosiy xulosa")))
                .andExpect(content().string(containsString("Vyselenie qilinmagan")))
                .andExpect(content().string(containsString("pms-num-3")));

        CustomUserDetails stranger = new CustomUserDetails(saveUser("stranger-an", Role.HOTEL_OWNER, Set.of(other)));
        mvc.perform(get("/hotels/{id}/debts/analysis", hotel.getId()).with(user(stranger)))
                .andExpect(status().isForbidden());
    }

    private Hotel hotel(String name) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setRoomsCount(10);
        return hotelRepository.save(h);
    }

    private void pms(String guest, BookingStatus status, LocalDate in, LocalDate out, String total, String due) {
        booking(hotel, guest, status, in, out, total, due, DataOrigin.EXELY_PMS);
    }

    private Booking booking(Hotel h, String guest, BookingStatus status, LocalDate in, LocalDate out,
                            String total, String due, DataOrigin origin) {
        Booking b = new Booking();
        b.setHotelId(h.getId());
        b.setOrigin(origin);
        seq++;
        b.setExternalId(origin == DataOrigin.EXELY_PMS ? "pms:pms-num-" + seq + "#rs" + seq : "manual-" + seq);
        b.setSource("Test");
        b.setStatus(status);
        b.setGuestName(guest);
        b.setArrivalDate(in);
        b.setDepartureDate(out);
        b.setTotalAmount(new BigDecimal(total));
        b.setBalanceDue(due == null ? null : new BigDecimal(due));
        b.setBookedAt(LocalDateTime.of(in.minusDays(10), java.time.LocalTime.NOON));
        return bookingRepository.save(b);
    }

    private User saveUser(String username, Role role, Set<Hotel> hotels) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}x");
        u.setRole(role);
        u.getHotels().addAll(hotels);
        return userRepository.save(u);
    }
}
