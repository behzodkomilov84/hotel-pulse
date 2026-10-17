package behzoddev.hotelpulse;

import behzoddev.hotelpulse.entity.Hotel;
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

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Mehmonxonani o'chirish: nom bilan tasdiq, bog'liq ma'lumotlar birga o'chadi, Chorvoq Stay mehmonxonasi himoyalangan. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class HotelDeletionDbTest {

    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;

    private MockMvc mvc;
    private CustomUserDetails owner;
    private User manager;

    @BeforeEach
    void setUp() {
        jdbc.update("update stay_settings set hotel_id = null");
        jdbc.update("delete from agents");
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        User u = new User();
        u.setUsername("del-owner");
        u.setPassword("{noop}x");
        u.setRole(Role.OWNER);
        owner = new CustomUserDetails(userRepository.save(u));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void deletesHotelWithAllItsDataOnlyAfterNameConfirmation() throws Exception {
        Hotel h = hotel("Test Delete Hotel");
        Hotel other = hotel("Other Hotel");
        long id = h.getId();
        jdbc.update("""
                insert into bookings (hotel_id, origin, external_id, source, status, arrival_date, departure_date, total_amount, booked_at)
                values (?, 'MANUAL', 'b1', 'Direct', 'CONFIRMED', curdate(), curdate() + interval 1 day, 100, now())""", id);
        jdbc.update("insert into payments (hotel_id, origin, external_id, amount, paid_at) values (?, 'MANUAL', 'p1', 100, now())", id);
        jdbc.update("""
                insert into agents (kind, machine, hotel_id, pairing_id, pairing_code, pairing_expires, created_at)
                values ('1C', 'PC', ?, 'pid-del', '123456', now(), now())""", id);
        User m = new User();
        m.setUsername("del-manager");
        m.setPassword("{noop}x");
        m.setRole(Role.HOTEL_OWNER);
        m.setHotels(new java.util.HashSet<>(Set.of(h, other)));
        manager = userRepository.save(m);

        // Sahifada — nima o'chishi ko'rsatiladi.
        String page = mvc.perform(get("/admin/hotels/" + id).with(user(owner))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("Меҳмонхонани ўчириш") && page.contains("брон ва яшаш"), page);

        // Nom noto'g'ri — hech narsa o'chmaydi.
        mvc.perform(post("/admin/hotels/" + id + "/delete").with(user(owner)).with(csrf()).param("confirmName", "Test"));
        assertTrue(hotelRepository.existsById(id));

        // Nom to'g'ri — mehmonxona va barcha ma'lumotlari o'chadi; boshqa mehmonxona va foydalanuvchi qoladi.
        mvc.perform(post("/admin/hotels/" + id + "/delete").with(user(owner)).with(csrf()).param("confirmName", " Test Delete Hotel "));
        assertFalse(hotelRepository.existsById(id));
        for (String table : new String[]{"bookings", "payments", "agents", "user_hotels"}) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where hotel_id = ?", Integer.class, id), table);
        }
        assertTrue(hotelRepository.existsById(other.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from user_hotels where user_id = ?", Integer.class, manager.getId()));
    }

    @Test
    void chorvoqStayHotelCannotBeDeleted() throws Exception {
        Hotel h = hotel("Chorvoq Stay");
        jdbc.update("update stay_settings set hotel_id = ?", h.getId());
        mvc.perform(post("/admin/hotels/" + h.getId() + "/delete").with(user(owner)).with(csrf()).param("confirmName", "Chorvoq Stay"));
        assertTrue(hotelRepository.existsById(h.getId()));
        jdbc.update("update stay_settings set hotel_id = null");
    }

    private Hotel hotel(String name) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setRoomsCount(10);
        return hotelRepository.save(h);
    }
}
