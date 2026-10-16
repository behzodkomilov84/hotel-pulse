package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chorvoq Stay sayti: domen bo'yicha ochilishi, so'rovlar API (bazaga + Telegram), himoya, admin ro'yxati. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token=",
        "app.stay.telegram-chat-ids=777"
})
class StayDbTest {

    private static final String BOOKING = """
            {"_subject":"Chorvoq Stay — bron so'rovi: Ali","_honey":"","So'rov turi":"Bron (mehmon)","Ism":"Ali Valiyev",
             "Telefon":"+998 90 123-45-67","Kelish":"2026-11-01","Ketish":"2026-11-03","Kechalar":2,"Mehmonlar":2,
             "Toifa":"Studio","Istaklar":"<b>baland qavat</b>","Til":"UZ"}""";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private WebApplicationContext context;
    @Autowired private StaySiteFilter siteFilter;
    @MockitoBean private behzoddev.hotelpulse.telegram.TelegramApi telegram;

    private MockMvc mvc;
    private CustomUserDetails owner;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stay_requests");
        userRepository.deleteAll();
        User u = new User();
        u.setUsername("stay-owner");
        u.setPassword("{noop}x");
        u.setRole(Role.OWNER);
        owner = new CustomUserDetails(userRepository.save(u));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(siteFilter).apply(springSecurity()).build();
    }

    @Test
    void siteOpensOnItsDomainAndHidesHotelPulse() throws Exception {
        mvc.perform(get("/").with(r -> host(r, "chorvoqstay.uz"))).andExpect(forwardedUrl("/stay/index.html"));
        mvc.perform(get("/css/base.css").with(r -> host(r, "www.chorvoqstay.uz"))).andExpect(forwardedUrl("/stay/css/base.css"));
        mvc.perform(get("/js/data/rooms.js").with(r -> host(r, "chorvoqstay.uz"))).andExpect(forwardedUrl("/stay/js/data/rooms.js"));
        mvc.perform(get("/login").with(r -> host(r, "chorvoqstay.uz"))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/stay").with(r -> host(r, "chorvoqstay.uz"))).andExpect(status().isNotFound());
        mvc.perform(get("/css/../application.yaml").with(r -> host(r, "chorvoqstay.uz"))).andExpect(status().isNotFound());
        // Sayt fayllari kirishsiz ochiladi; hotel-pulse.uz'da odatdagidek (kirish sahifasiga).
        String html = mvc.perform(get("/stay/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("Chorvoq Stay"));
        mvc.perform(get("/stay/js/config.js")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
    }

    @Test
    void bookingRequestIsSavedAndSentToTelegram() throws Exception {
        String body = mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://chorvoqstay.uz").header("X-Real-IP", "10.0.0.1").content(BOOKING))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"ok\":true"), body);

        var row = jdbc.queryForMap("select kind, name, phone, status, ip, payload from stay_requests");
        assertEquals("Bron (mehmon)", row.get("kind"));
        assertEquals("Ali Valiyev", row.get("name"));
        assertEquals("+998 90 123-45-67", row.get("phone"));
        assertEquals("NEW", row.get("status"));
        assertEquals("10.0.0.1", row.get("ip"));
        assertFalse(String.valueOf(row.get("payload")).contains("_subject"), "xizmat maydonlari saqlanmaydi");
        // Telegram: sozlamadagi chat'ga, HTML belgilari xavfsiz.
        verify(telegram).sendMessage(eq(777L), org.mockito.ArgumentMatchers.argThat(
                t -> t.contains("Ali Valiyev") && t.contains("&lt;b&gt;baland qavat&lt;/b&gt;") && !t.contains("<b>baland")), isNull());

        // Admin: ro'yxatda, "ko'rib chiqildi" — yangilardan chiqadi.
        String admin = html("/admin/stay");
        assertTrue(admin.contains("Ali Valiyev") && admin.contains("baland qavat"));
        long id = jdbc.queryForObject("select id from stay_requests", Long.class);
        mvc.perform(post("/admin/stay/requests/" + id + "/done").with(user(owner)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals("DONE", jdbc.queryForObject("select status from stay_requests", String.class));
        assertTrue(html("/admin/stay").contains("Янги сўров йўқ"));
        assertTrue(html("/admin/stay?show=all").contains("Ali Valiyev"));
    }

    @Test
    void protectionAgainstBotsForeignOriginsAndFlooding() throws Exception {
        // Bot (yashirin maydon to'ldirilgan) — jimgina "ok", lekin saqlanmaydi va Telegram'ga bormaydi.
        mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON).header("X-Real-IP", "10.0.0.2")
                        .content(BOOKING.replace("\"_honey\":\"\"", "\"_honey\":\"spam\"")))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("select count(*) from stay_requests", Integer.class));
        verify(telegram, never()).sendMessage(org.mockito.ArgumentMatchers.anyLong(), anyString(), isNull());

        mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "https://evil.example").header("X-Real-IP", "10.0.0.3").content(BOOKING))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON).header("X-Real-IP", "10.0.0.3")
                .content("not json")).andExpect(status().isBadRequest());

        // Bitta IP'dan 10 daqiqada 5 tadan ortiq — 429.
        for (int i = 0; i < StayApiController.RATE_LIMIT; i++) {
            mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON).header("X-Real-IP", "10.0.0.4")
                    .content(BOOKING)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/stay/requests").contentType(MediaType.APPLICATION_JSON).header("X-Real-IP", "10.0.0.4")
                .content(BOOKING)).andExpect(status().isTooManyRequests());
        // Admin sahifasi — faqat platforma egasiga.
        mvc.perform(get("/admin/stay")).andExpect(status().is3xxRedirection());
    }

    private String html(String url) throws Exception {
        var res = mvc.perform(get(url).with(user(owner))).andReturn().getResponse();
        assertEquals(200, res.getStatus(), url);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private static org.springframework.mock.web.MockHttpServletRequest host(
            org.springframework.mock.web.MockHttpServletRequest r, String host) {
        r.setServerName(host);
        return r;
    }
}
