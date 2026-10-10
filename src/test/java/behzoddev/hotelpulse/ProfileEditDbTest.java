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

/** Profil sahifasidan ism/telefon va parolni o'zgartirish (hotel_pulse_test bazasi). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class ProfileEditDbTest {

    private static final String OLD_PASSWORD = "old-password-123";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HotelRepository hotelRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private User u;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        User x = new User();
        x.setUsername("profile-user");
        x.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        x.setRole(Role.HOTEL_OWNER);
        u = userRepository.save(x);
    }

    private CustomUserDetails principal() {
        return new CustomUserDetails(userRepository.findById(u.getId()).orElseThrow());
    }

    @Test
    void profilePageShowsEditAndPasswordModals() throws Exception {
        mvc.perform(get("/profile").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-modal-open=\"editModal\"")))
                .andExpect(content().string(containsString("data-modal-open=\"passwordModal\"")))
                .andExpect(content().string(containsString("<dialog class=\"modal\" id=\"editModal\"")))
                .andExpect(content().string(containsString("name=\"currentPassword\"")));
    }

    @Test
    void errorReopensModalWithMessageInside() throws Exception {
        String html = mvc.perform(get("/profile").with(user(principal()))
                        .flashAttr("error", "Joriy parol noto'g'ri").flashAttr("passwordOpen", true))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("id=\"passwordModal\" aria-labelledby=\"passwordModalTitle\" data-open-on-load=\"true\""), "modal qayta ochilishi kerak");
        // Xato faqat modal ichida — sahifa tepasida takrorlanmaydi.
        assertEquals(1, html.split("alert alert-error", -1).length - 1);
    }

    @Test
    void updatesNameAndPhoneAndRefreshesHeader() throws Exception {
        var session = mvc.perform(post("/profile").with(user(principal())).with(csrf())
                        .param("fullName", "  Ali Valiyev ").param("phone", "+998 90 123 45 67"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Профил сақланди"))
                .andReturn().getRequest().getSession();

        User saved = userRepository.findById(u.getId()).orElseThrow();
        assertEquals("Ali Valiyev", saved.getFullName());
        assertEquals("+998 90 123 45 67", saved.getPhone());

        // Sarlavhadagi ism yangilangan sessiyadan olinadi.
        mvc.perform(get("/profile").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"name\">Ali Valiyev")));
    }

    @Test
    void rejectsInvalidPhone() throws Exception {
        mvc.perform(post("/profile").with(user(principal())).with(csrf())
                        .param("fullName", "X").param("phone", "abc"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attribute("editOpen", true));
        assertNull(userRepository.findById(u.getId()).orElseThrow().getPhone());
    }

    @Test
    void passwordChangeRequiresCorrectCurrentPassword() throws Exception {
        mvc.perform(post("/profile/password").with(user(principal())).with(csrf())
                        .param("currentPassword", "wrong-one")
                        .param("newPassword", "new-password-456").param("confirmPassword", "new-password-456"))
                .andExpect(flash().attribute("error", "Жорий парол нотўғри"))
                .andExpect(flash().attribute("passwordOpen", true));
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, userRepository.findById(u.getId()).orElseThrow().getPassword()));
    }

    @Test
    void passwordChangeRejectsMismatchAndShortPassword() throws Exception {
        mvc.perform(post("/profile/password").with(user(principal())).with(csrf())
                        .param("currentPassword", OLD_PASSWORD)
                        .param("newPassword", "new-password-456").param("confirmPassword", "other-password-789"))
                .andExpect(flash().attribute("error", "Янги парол ва унинг такрори бир хил эмас"));
        mvc.perform(post("/profile/password").with(user(principal())).with(csrf())
                        .param("currentPassword", OLD_PASSWORD)
                        .param("newPassword", "short").param("confirmPassword", "short"))
                .andExpect(flash().attributeExists("error"));
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, userRepository.findById(u.getId()).orElseThrow().getPassword()));
    }

    @Test
    void passwordChangeSucceeds() throws Exception {
        mvc.perform(post("/profile/password").with(user(principal())).with(csrf())
                        .param("currentPassword", OLD_PASSWORD)
                        .param("newPassword", "new-password-456").param("confirmPassword", "new-password-456"))
                .andExpect(flash().attribute("success", "Парол ўзгартирилди"));
        String hash = userRepository.findById(u.getId()).orElseThrow().getPassword();
        assertTrue(passwordEncoder.matches("new-password-456", hash));
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, hash));
    }

    @Test
    void staleOrMissingCsrfRedirectsToLoginInsteadOf403() throws Exception {
        // Eskirgan sessiya / CSRF tokeni yo'q — "ruxsat yo'q" emas, qayta kirishga yo'naltiriladi.
        mvc.perform(post("/profile/password").with(user(principal()))
                        .param("currentPassword", OLD_PASSWORD)
                        .param("newPassword", "new-password-456").param("confirmPassword", "new-password-456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/profile").with(user(principal())).with(csrf().useInvalidToken())
                        .param("fullName", "X"))
                .andExpect(redirectedUrl("/login?expired"));
        // Parol o'zgarmagan.
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, userRepository.findById(u.getId()).orElseThrow().getPassword()));
        // Haqiqiy ruxsat yo'qligi esa hamon 403.
        mvc.perform(get("/admin/hotels").with(user(principal()))).andExpect(status().isForbidden());
        mvc.perform(get("/login").param("expired", ""))
                .andExpect(content().string(containsString("Сессия муддати тугади")));
    }
}
