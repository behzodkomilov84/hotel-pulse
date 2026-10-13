package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Agent juftlash (mehmonxona nomi bo'yicha avtomatik), kalit bir marta beriladi, gzip yuklash, admin sahifa. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class AgentDbTest {

    @Autowired private AgentService agents;
    @Autowired private ShelterImportService shelterImport;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;

    private MockMvc mvc;
    private CustomUserDetails owner;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from agents");
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        User u = new User();
        u.setUsername("agent-owner");
        u.setPassword("{noop}x");
        u.setRole(Role.OWNER);
        owner = new CustomUserDetails(userRepository.save(u));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void pairApproveByHotelNameAndUpload() throws Exception {
        // Agent juftlashni boshlaydi (sessiyasiz, CSRF'siz).
        var res = mvc.perform(post("/api/agent/pair").param("kind", "SHELTER").param("machine", "WIN-TEST")
                .param("hotel", "Aydarko'l Resort")).andReturn().getResponse();
        assertEquals(200, res.getStatus());
        String body = res.getContentAsString();
        String pairingId = body.replaceAll(".*\"pairingId\":\"([0-9a-f]+)\".*", "$1");
        assertTrue(mvc.perform(get("/api/agent/pair/" + pairingId)).andReturn().getResponse().getContentAsString().contains("PENDING"));

        // Admin sahifada — yangi mehmonxona taklif qilinadi; tasdiqlanganda shu nom bilan yaratiladi.
        String page = mvc.perform(get("/admin/agents").with(user(owner))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("Янги: Aydarko'l Resort") || page.contains("Янги: Aydarko&#39;l Resort"), page);
        long agentId = agents.agents().get(0).id();
        var approve = mvc.perform(post("/admin/agents/" + agentId + "/approve").with(user(owner)).with(csrf()).param("hotel", "auto"))
                .andReturn().getResponse();
        assertEquals(302, approve.getStatus());
        var hotel = hotelRepository.findAll().stream().filter(h -> h.getName().equals("Aydarko'l Resort")).findFirst().orElseThrow();

        // Kalit bir marta beriladi.
        String first = mvc.perform(get("/api/agent/pair/" + pairingId)).andReturn().getResponse().getContentAsString();
        String key = first.replaceAll(".*\"key\":\"([^\"]+)\".*", "$1");
        assertTrue(first.contains("APPROVED") && key.length() > 30, first);
        assertFalse(mvc.perform(get("/api/agent/pair/" + pairingId)).andReturn().getResponse().getContentAsString().contains("\"key\""));
        assertNull(jdbc.queryForObject("select pending_key from agents where id = ?", String.class, agentId));

        // Yuklash: kalitsiz — 401; gzip bilan — ochilgan holda saqlanadi.
        assertEquals(401, mvc.perform(post("/api/agent/upload").param("kind", "discovery").content(new byte[]{1}))
                .andReturn().getResponse().getStatus());
        byte[] json = "{\"tables\":[]}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json);
        }
        var up = mvc.perform(post("/api/agent/upload").header("X-Agent-Key", key).param("kind", "discovery")
                .param("name", "shelter-discovery.json").content(bos.toByteArray())).andReturn().getResponse();
        assertEquals(200, up.getStatus(), up.getContentAsString());
        var upload = agents.uploads(5).get(0);
        assertEquals(hotel.getId(), upload.hotelId());
        assertArrayEquals(json, agents.payload(upload.id()).orElseThrow());
        assertArrayEquals(json, mvc.perform(get("/admin/agents/uploads/" + upload.id()).with(user(owner)))
                .andReturn().getResponse().getContentAsByteArray());

        // Jadval sinxronlashi: ikki qism, keyin qayta yuborilganda eski qismlar almashtiriladi.
        byte[] p0 = gzip("{\"ID\":1}\n{\"ID\":2}\n");
        byte[] p1 = gzip("{\"ID\":3}\n");
        assertEquals(400, mvc.perform(post("/api/agent/table").header("X-Agent-Key", key).param("name", "ROOMS")
                .content("plain".getBytes())).andReturn().getResponse().getStatus());
        for (int i = 0; i < 2; i++) {
            assertEquals(200, mvc.perform(post("/api/agent/table").header("X-Agent-Key", key).param("name", "ROOMS")
                    .param("part", String.valueOf(i)).param("parts", "2").param("rows", i == 0 ? "2" : "1")
                    .content(i == 0 ? p0 : p1)).andReturn().getResponse().getStatus());
        }
        assertEquals(3, agents.tableRows(hotel.getId(), "ROOMS").size());
        mvc.perform(post("/api/agent/table").header("X-Agent-Key", key).param("name", "ROOMS")
                .param("part", "0").param("parts", "1").param("rows", "1").content(gzip("{\"ID\":9}\n")));
        assertEquals(java.util.List.of("{\"ID\":9}"), agents.tableRows(hotel.getId(), "ROOMS"));
        assertEquals(200, mvc.perform(post("/api/agent/sync-done").header("X-Agent-Key", key).param("message", "1 та жадвал"))
                .andReturn().getResponse().getStatus());
        var synced = agents.agents().get(0);
        assertEquals("1 та жадвал", synced.lastSyncMessage());
        assertEquals(1, synced.tables());

        // Ikkinchi agent shu nom bilan — mavjud mehmonxona avtomatik tanlanadi.
        agents.pair("SHELTER", "WIN-2", "AYDARKO'L RESORT");
        var second = agents.agents().stream().filter(a -> !a.isApproved()).findFirst().orElseThrow();
        assertEquals(hotel.getId(), second.suggestedHotelId());
        assertEquals(hotel.getId(), agents.approveAgent(second.id(), null));
        assertEquals(1, hotelRepository.findAll().size());
    }

    @Test
    void shelterImportBuildsBookingsRevenueAndPayments() throws Exception {
        var hotel = hotelRepository.save(newHotel("Shelter Hotel"));
        agents.pair("SHELTER", "WIN-S", "Shelter Hotel");
        long agentId = agents.agents().get(0).id();
        agents.approveAgent(agentId, hotel.getId());
        var auth = new AgentService.Authenticated(agentId, hotel.getId(), "SHELTER");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent"));
        long a = java.time.temporal.ChronoUnit.DAYS.between(ShelterMapper.EPOCH, today.minusDays(3));
        long b = a + 2;
        put(auth, "ROOM_SCHEDULE", "{\"ID\":1,\"RESID\":10,\"STATE\":400,\"ENABLED\":1,\"ARRIVAL\":" + (a + 0.6)
                + ",\"DEPARTURE\":" + (b + 0.5) + ",\"FOLIO\":100,\"ROOMKIND\":1,\"IDROOM\":7,\"GUEST\":5}");
        put(auth, "FOLIO_CONTENTS",
                "{\"ID\":1,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":1000000,\"SHIFTDATE\":" + a + "}\n"
                        + "{\"ID\":2,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":1000000,\"SHIFTDATE\":" + (a + 1) + "}\n"
                        + "{\"ID\":3,\"FOLIO\":100,\"OPERATION\":85,\"ISSERVICE\":0,\"ENABLED\":1,\"COST\":1500000,\"CREATIONDATE\":" + (b + 0.4) + "}");
        put(auth, "OPERATIONS", "{\"ID\":70,\"CODE\":\"100\",\"NAME\":\"Проживание\",\"CATEGORY\":101,\"ISSERVICE\":1}\n"
                + "{\"ID\":85,\"CODE\":\"02\",\"NAME\":\"Uzcard\",\"CATEGORY\":902,\"ISSERVICE\":0}");
        put(auth, "GUESTS", "{\"ID\":5,\"NAME\":\"Rahimov\",\"NAME1\":\"Sardor\"}");
        put(auth, "ENTERPRISEDEBTOR", "");
        put(auth, "ROOMS", "{\"ID\":7,\"ROOMKIND\":1,\"ENABLED\":1,\"NUMBER\":\"101\"}\n{\"ID\":8,\"ROOMKIND\":1,\"ENABLED\":1,\"NUMBER\":\"102\"}");
        put(auth, "ROOM_KINDS", "{\"ID\":1,\"NAME\":\"Standard Twin\"}");

        String msg = shelterImport.importNow(hotel.getId());
        assertTrue(msg.contains("1 та яшаш"), msg);
        var booking = jdbc.queryForMap("select status, guest_name, total_amount, balance_due, room_id from bookings where hotel_id = ? and origin = 'SHELTER'", hotel.getId());
        assertEquals("CHECKED_OUT", booking.get("status"));
        assertEquals("Rahimov Sardor", booking.get("guest_name"));
        assertEquals(0, new java.math.BigDecimal("2000000").compareTo((java.math.BigDecimal) booking.get("total_amount")));
        assertEquals(0, new java.math.BigDecimal("500000").compareTo((java.math.BigDecimal) booking.get("balance_due")));
        assertEquals(2, jdbc.queryForObject("select count(*) from service_revenue where hotel_id = ?", Integer.class, hotel.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from payments where hotel_id = ? and booking_id is not null", Integer.class, hotel.getId()));
        var h = hotelRepository.findById(hotel.getId()).orElseThrow();
        assertEquals(2, h.getRoomsCount());
        assertNotNull(h.getPmsServicesFrom());

        // Qayta import — dublikat yo'q.
        shelterImport.importNow(hotel.getId());
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings where hotel_id = ?", Integer.class, hotel.getId()));
        // Mehmonxona sahifasi ochiladi.
        assertEquals(200, mvc.perform(get("/hotels/" + hotel.getId()).with(user(owner))).andReturn().getResponse().getStatus());
    }

    private void put(AgentService.Authenticated auth, String table, String jsonl) throws java.io.IOException {
        agents.saveTablePart(auth, table, 0, 1, jsonl.isEmpty() ? 0 : jsonl.split("\n").length, "t", gzip(jsonl.isEmpty() ? "" : jsonl + "\n"));
    }

    private static behzoddev.hotelpulse.entity.Hotel newHotel(String name) {
        var h = new behzoddev.hotelpulse.entity.Hotel();
        h.setName(name);
        return h;
    }

    private static byte[] gzip(String s) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
