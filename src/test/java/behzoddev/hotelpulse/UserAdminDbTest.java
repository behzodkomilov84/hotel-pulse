package behzoddev.hotelpulse;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Admin: foydalanuvchi loginini o'zgartirish va "Boshqaruv kompaniyasi" roli (hotel_pulse_test bazasi). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class UserAdminDbTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private CustomUserDetails owner;
    private User target;

    private User save(String username, Role role) {
        User x = new User();
        x.setUsername(username);
        x.setPassword(passwordEncoder.encode("password-123"));
        x.setRole(role);
        return userRepository.save(x);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        owner = new CustomUserDetails(save("owner-admin", Role.OWNER));
        target = save("thetower", Role.HOTEL_OWNER);
        save("taken", Role.HOTEL_STAFF);
    }

    @Test
    void formShowsEditableLoginAndManagementCompanyRole() throws Exception {
        mvc.perform(get("/admin/users/{id}", target.getId()).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("value=\"thetower\"")))
                .andExpect(content().string(containsString("Бошқарув компанияси")));
    }

    @Test
    void loginAndRoleCanBeChanged() throws Exception {
        mvc.perform(post("/admin/users/{id}", target.getId()).with(user(owner)).with(csrf())
                        .param("username", "tower-group").param("role", "MANAGEMENT_COMPANY").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));

        User u = userRepository.findById(target.getId()).orElseThrow();
        assertEquals("tower-group", u.getUsername());
        assertEquals(Role.MANAGEMENT_COMPANY, u.getRole());
        assertTrue(passwordEncoder.matches("password-123", u.getPassword()), "парол ўзгармайди");
    }

    @Test
    void takenOrInvalidLoginIsRejected() throws Exception {
        mvc.perform(post("/admin/users/{id}", target.getId()).with(user(owner)).with(csrf())
                        .param("username", "taken").param("role", "HOTEL_OWNER").param("enabled", "true"))
                .andExpect(flash().attribute("error", "Бу логин банд"));
        mvc.perform(post("/admin/users/{id}", target.getId()).with(user(owner)).with(csrf())
                        .param("username", "a b").param("role", "HOTEL_OWNER").param("enabled", "true"))
                .andExpect(flash().attribute("error", containsString("Логин 3–64")));
        assertEquals("thetower", userRepository.findById(target.getId()).orElseThrow().getUsername());
    }

    @Test
    void ownerRenamingThemselvesStaysLoggedIn() throws Exception {
        var session = mvc.perform(post("/admin/users/{id}", owner.getId()).with(user(owner)).with(csrf())
                        .param("username", "boss").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession();
        assertEquals("boss", userRepository.findById(owner.getId()).orElseThrow().getUsername());
        assertNotNull(session);
    }
}
