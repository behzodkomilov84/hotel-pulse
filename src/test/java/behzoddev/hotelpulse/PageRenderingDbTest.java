package behzoddev.hotelpulse;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.DemoDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sahifalarni haqiqiy MySQL (alohida hotel_pulse_test bazasi) va demo
 * ma'lumotlar bilan to'liq render qiladi — Thymeleaf ifodalaridagi xatolar
 * va ruxsatlarni tekshiradi.
 */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password"
})
class PageRenderingDbTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DemoDataService demoDataService;

    private MockMvc mvc;
    private CustomUserDetails owner;
    private CustomUserDetails hotelOwner;
    private Hotel hotelA;
    private Hotel hotelB;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userRepository.deleteAll();
        hotelRepository.deleteAll();

        hotelA = hotel("Test Hotel A", 30);
        hotelB = hotel("Test Hotel B", 12);
        demoDataService.generate(hotelA);

        owner = new CustomUserDetails(saveUser("owner-t", Role.OWNER, Set.of()));
        hotelOwner = new CustomUserDetails(saveUser("hotel-owner-t", Role.HOTEL_OWNER, Set.of(hotelA)));
    }

    @Test
    void hotelPageRendersForEveryPeriod() throws Exception {
        for (String period : new String[]{"today", "7d", "30d", "month", "prevmonth", "next30"}) {
            mvc.perform(get("/hotels/{id}", hotelA.getId()).param("period", period).with(user(owner)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("RevPAR")))
                    .andExpect(content().string(containsString("dailyChart")));
        }
        mvc.perform(get("/hotels/{id}", hotelA.getId())
                        .param("period", "custom").param("from", "2026-01-01").param("to", "2026-02-15")
                        .with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("01.01.2026")));
    }

    @Test
    void hotelWithoutDataShowsDemoButtonForOwner() throws Exception {
        mvc.perform(get("/hotels/{id}", hotelB.getId()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sinov ma'lumotlarini yaratish")));
    }

    @Test
    void hotelOwnerSeesOnlyOwnHotels() throws Exception {
        mvc.perform(get("/").with(user(hotelOwner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Test Hotel A")))
                .andExpect(content().string(not(containsString("Test Hotel B"))));
        mvc.perform(get("/hotels/{id}", hotelB.getId()).with(user(hotelOwner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/hotels").with(user(hotelOwner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerDashboardAndAdminPagesRender() throws Exception {
        mvc.perform(get("/").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Test Hotel B")))
                .andExpect(content().string(containsString("Bandlik (shu oy)")));
        mvc.perform(get("/admin/hotels").with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/admin/hotels/new").with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/admin/hotels/{id}", hotelA.getId()).with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/admin/users").with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/admin/users/new").with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/profile").with(user(owner))).andExpect(status().isOk());
        mvc.perform(get("/profile").with(user(hotelOwner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Telegram")));
    }

    @Test
    void devLoginDoesNotExistWithoutLocalProfile() throws Exception {
        mvc.perform(get("/dev-login").param("user", "owner-t"))
                .andExpect(status().isNotFound());
    }

    private Hotel hotel(String name, int rooms) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setRoomsCount(rooms);
        return hotelRepository.save(h);
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
