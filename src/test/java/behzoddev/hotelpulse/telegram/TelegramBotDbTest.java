package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.service.DemoDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bot oqimlari: ulash, hisobotlar, ruxsatlar, kunlik hisobot (hotel_pulse_test bazasi). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token=",
        "app.telegram.site-url=https://hotelpulse.example"
})
class TelegramBotDbTest {

    /** Yuborilgan xabarlarni xotirada yig'adi. */
    static class FakeGateway implements TelegramGateway {
        record Sent(long chatId, String html, Map<String, Object> markup) {
        }

        final List<Sent> sent = new ArrayList<>();
        final List<String> answers = new ArrayList<>();

        @Override
        public void sendMessage(long chatId, String html, Map<String, Object> replyMarkup) {
            sent.add(new Sent(chatId, html, replyMarkup));
        }

        @Override
        public void answerCallback(String id, String text) {
            answers.add(id);
        }

        @Override
        public String botUsername() {
            return "HotelPulseTestBot";
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    @Autowired
    private TelegramBotService bot;
    @Autowired
    private TelegramLinkService linkService;
    @Autowired
    private DailyReportJob dailyReportJob;
    @Autowired
    private FakeGateway gateway;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private DemoDataService demoDataService;
    @Autowired
    private TelegramReportService reports;
    @Autowired
    private behzoddev.hotelpulse.service.KpiService kpiService;
    @Autowired
    private behzoddev.hotelpulse.controller.Formats fmtBean;

    private Hotel hotelA;
    private Hotel hotelB;
    private User single;
    private User multi;

    @BeforeEach
    void setUp() {
        gateway.sent.clear();
        gateway.answers.clear();
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        hotelA = hotel("Alpha <Hotel>", 20);
        hotelB = hotel("Beta Hotel", 10);
        demoDataService.generate(hotelA);
        single = user("single", Set.of(hotelA));
        multi = user("multi", Set.of(hotelA, hotelB));
    }

    private static TelegramModels.Update msg(long chatId, String text) {
        return new TelegramModels.Update(1, new TelegramModels.Message(1,
                new TelegramModels.Chat(chatId, "private"), new TelegramModels.User(chatId, "T", null), text), null);
    }

    private static TelegramModels.Update cb(long chatId, String data) {
        return new TelegramModels.Update(2, null, new TelegramModels.CallbackQuery("cb-" + data,
                new TelegramModels.User(chatId, "T", null),
                new TelegramModels.Message(5, new TelegramModels.Chat(chatId, "private"), null, "x"), data));
    }

    @Test
    void linkingWithTokenAndOneTimeUse() {
        String token = linkService.createToken(single.getId());

        bot.handle(msg(1001, "/start " + token));
        assertTrue(gateway.last().html().contains("Ulandi"));
        assertNotNull(gateway.last().markup().get("keyboard"), "asosiy menyu tugmalari");
        assertEquals(1001L, userRepository.findById(single.getId()).orElseThrow().getTelegramChatId());

        // Token qayta ishlatilmaydi.
        bot.handle(msg(2002, "/start " + token));
        assertTrue(gateway.last().html().contains("eskirgan"));
    }

    @Test
    void unknownChatGetsInstructions() {
        bot.handle(msg(3003, "/bugun"));
        assertTrue(gateway.last().html().contains("Profil"));
    }

    @Test
    void singleHotelUserGetsReportImmediately() {
        link(single, 1001);
        bot.handle(msg(1001, TelegramBotService.BTN_TODAY));
        String html = gateway.last().html();
        assertTrue(html.contains("Alpha &lt;Hotel&gt;"), "HTML qochirilgan bo'lishi kerak");
        assertTrue(html.contains("Bandlik"));
        assertTrue(html.contains("RevPAR"));
        // Ommaviy sayt manzili berilgan — "Saytda batafsil" tugmasi.
        assertTrue(gateway.last().markup().toString().contains("https://hotelpulse.example/hotels/" + hotelA.getId()));
    }

    @Test
    void multiHotelUserChoosesAndAccessIsChecked() {
        link(multi, 1001);
        link(single, 2002);

        bot.handle(msg(1001, "/oy"));
        String keyboard = gateway.last().markup().toString();
        assertTrue(keyboard.contains("r:month:all"));
        assertTrue(keyboard.contains("r:month:" + hotelB.getId()));

        bot.handle(cb(1001, "r:month:all"));
        assertTrue(gateway.last().html().contains("Barcha mehmonxonalar"));
        assertTrue(gateway.last().html().contains("ma'lumot yo'q"), "Beta'da ma'lumot yo'q");

        // single foydalanuvchi Beta'ga biriktirilmagan — soxta callback bilan ham ko'ra olmaydi.
        bot.handle(cb(2002, "r:month:" + hotelB.getId()));
        assertTrue(gateway.last().html().contains("ruxsat yo'q"));
    }

    @Test
    void reportsShowRevenueVsPaymentsAndPortfolioOnlyForOwnHotels() {
        demoDataService.generate(hotelB);
        Hotel foreign = hotel("Gamma Foreign", 50);
        demoDataService.generate(foreign);
        link(single, 2002);
        link(multi, 1001);

        bot.handle(msg(2002, TelegramBotService.BTN_TODAY));
        String one = gateway.last().html();
        assertTrue(one.contains("Daromad va tushum"));
        assertTrue(one.contains("Tushum (to'lovlar)"));
        assertTrue(one.contains("Farq"));

        bot.handle(cb(1001, "r:month:all"));
        String all = gateway.last().html();
        assertTrue(all.contains("<b>Jami</b> (2 ta mehmonxona)"), all);
        assertTrue(all.contains("O'rtacha ADR"));
        assertFalse(all.contains("Gamma"), "biriktirilmagan mehmonxona jamiga kirmaydi");

        // O'rtacha ADR og'irlikli: jami yashash daromadi / jami sotilgan xona-kechalar (faqat A va B).
        var period = reports.period("month");
        var p = kpiService.portfolio(List.of(hotelA, hotelB), period).get(0);
        var a = kpiService.report(hotelA, period).stays();
        var b = kpiService.report(hotelB, period).stays();
        assertEquals(a.soldRoomNights() + b.soldRoomNights(), p.soldRoomNights());
        assertEquals(0, a.roomRevenue().add(b.roomRevenue()).compareTo(p.roomRevenue()));
        assertTrue(all.contains(fmtBean.money(p.adr(), p.currency())), all);
    }

    @Test
    void dailyReportToggleAndUnlink() {
        link(single, 1001);
        bot.handle(cb(1001, "d:off"));
        assertFalse(userRepository.findById(single.getId()).orElseThrow().isTelegramDailyReport());
        assertTrue(gateway.last().html().contains("o'chirilgan"));

        bot.handle(msg(1001, "/uzish"));
        assertNull(userRepository.findById(single.getId()).orElseThrow().getTelegramChatId());
    }

    @Test
    void disabledUserIsRefused() {
        link(single, 1001);
        User u = userRepository.findById(single.getId()).orElseThrow();
        u.setEnabled(false);
        userRepository.save(u);
        bot.handle(msg(1001, "/bugun"));
        assertTrue(gateway.last().html().contains("bloklangan"));
    }

    @Test
    void dailyReportSendsYesterdayToSubscribedUsers() {
        link(single, 1001);
        link(multi, 2002);
        User m = userRepository.findById(multi.getId()).orElseThrow();
        m.setTelegramDailyReport(false);
        userRepository.save(m);

        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 6);
        int sent = dailyReportJob.send(hotelA, today);

        assertEquals(1, sent, "faqat kunlik hisobot yoqilgan va mehmonxonani ko'ra oladigan foydalanuvchi");
        assertTrue(gateway.sent.stream().anyMatch(s -> s.chatId() == 1001 && s.html().contains("Xayrli tong")
                && s.html().contains("Kecha")));
        assertTrue(gateway.sent.stream().noneMatch(s -> s.chatId() == 2002), "hisoboti o'chirilgan");
        assertEquals(today, hotelRepository.findById(hotelA.getId()).orElseThrow().getDailyReportSentOn(),
                "kuniga bir marta — yuborilgani belgilandi");
        assertEquals(List.of(single.getId()),
                userRepository.findAllByTelegramChatIdIsNotNullAndTelegramDailyReportTrueAndEnabledTrue()
                        .stream().map(User::getId).toList());
    }

    @Test
    void reportIsDueOncePerDayWithinCatchUpWindow() {
        Hotel h = new Hotel();
        h.setDailyReportTime(java.time.LocalTime.of(5, 0));
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 6);
        assertFalse(DailyReportJob.isDue(h, day.atTime(4, 59)), "vaqti kelmagan");
        assertTrue(DailyReportJob.isDue(h, day.atTime(5, 0)), "aynan vaqtida");
        assertTrue(DailyReportJob.isDue(h, day.atTime(7, 30)), "server o'chiq bo'lgan — 3 soat ichida yetkaziladi");
        assertFalse(DailyReportJob.isDue(h, day.atTime(8, 1)), "kech — ertaga");
        h.setDailyReportSentOn(day);
        assertFalse(DailyReportJob.isDue(h, day.atTime(5, 1)), "bugun yuborilgan");
        assertTrue(DailyReportJob.isDue(h, day.plusDays(1).atTime(5, 0)), "ertasi kuni — yana");

        // Bir nechta mehmonxona — har biri o'z vaqtida.
        Hotel other = new Hotel();
        other.setName("ARDA");
        other.setDailyReportTime(java.time.LocalTime.of(6, 30));
        h.setName("Karvon");
        assertEquals("har kuni (Karvon — 05:00, ARDA — 06:30)", TelegramBotService.dailyReportWhen(List.of(h, other)));
        other.setDailyReportTime(java.time.LocalTime.of(5, 0));
        assertEquals("har kuni soat 05:00 da", TelegramBotService.dailyReportWhen(List.of(h, other)));
    }

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @Test
    void profilePageShowsLinkButtonThenLinkedState() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        var principal = new behzoddev.hotelpulse.security.CustomUserDetails(single);

        String before = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/profile")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(before.contains("Telegram&#39;ni ulash") || before.contains("Telegram'ni ulash"));

        // "Ulash" tugmasi → t.me deep-link'ga yo'naltiradi.
        String location = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/profile/telegram/link")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andReturn().getResponse().getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.startsWith("https://t.me/HotelPulseTestBot?start="), location);

        bot.handle(msg(1001, "/start " + location.substring(location.indexOf("start=") + 6)));
        String after = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/profile")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(after.contains("badge badge-ok\">Ulangan"));
        assertTrue(after.contains("Kunlik hisobot"));
    }

    private void link(User user, long chatId) {
        linkService.link(linkService.createToken(user.getId()), chatId);
    }

    private Hotel hotel(String name, int rooms) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setRoomsCount(rooms);
        return hotelRepository.save(h);
    }

    private User user(String username, Set<Hotel> hotels) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}x");
        u.setRole(Role.HOTEL_OWNER);
        u.getHotels().addAll(hotels);
        return userRepository.save(u);
    }
}
