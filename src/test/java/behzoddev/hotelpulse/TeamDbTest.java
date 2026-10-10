package behzoddev.hotelpulse;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.repository.DepartmentRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.TaskRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.TaskService;
import behzoddev.hotelpulse.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Bo'limlar va xodimlar: mehmonxona egasi o'z jamoasini boshqaradi; topshiriq bo'lim bo'yicha (hotel_pulse_test). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class TeamDbTest {

    @Autowired private TeamService teamService;
    @Autowired private TaskService taskService;
    @Autowired private HotelService hotelService;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private WebApplicationContext context;
    @Autowired private TransactionTemplate tx;
    @Autowired private Clock clock;

    private Hotel hotel;
    private Hotel foreign;
    private CustomUserDetails owner;
    private CustomUserDetails otherOwner;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        hotel = hotel("Team Hotel");
        foreign = hotel("Foreign Hotel");
        owner = new CustomUserDetails(saveUser("team-owner", Role.HOTEL_OWNER, Set.of(hotel)));
        otherOwner = new CustomUserDetails(saveUser("foreign-owner", Role.HOTEL_OWNER, Set.of(foreign)));
    }

    @Test
    void ownerCreatesDepartmentsAndStaffInSeveralDepartments() {
        Department reception = teamService.createDepartment(owner, hotel.getId(), " Resepshn ");
        Department accounting = teamService.createDepartment(owner, hotel.getId(), "Buxgalteriya");
        assertEquals("Resepshn", reception.getName());
        assertThrows(IllegalArgumentException.class, () -> teamService.createDepartment(owner, hotel.getId(), "resepshn"),
                "bir mehmonxonada nomi takrorlanmaydi");
        assertThrows(AccessDeniedException.class, () -> teamService.createDepartment(otherOwner, hotel.getId(), "X"),
                "begona mehmonxonaga bo'lim qo'shib bo'lmaydi");
        assertThrows(IllegalArgumentException.class, () -> teamService.createStaff(owner, "no-dept", "password1", null, null, List.of()),
                "bo'limsiz xodim yo'q");

        User staff = teamService.createStaff(owner, "aziza", "password1", "Aziza", "+998901234567",
                List.of(reception.getId(), accounting.getId()));
        User saved = userRepository.findWithTeam(staff.getId()).orElseThrow();
        assertEquals(Role.HOTEL_STAFF, saved.getRole());
        assertEquals(Set.of(hotel.getId()), saved.getHotels().stream().map(Hotel::getId).collect(java.util.stream.Collectors.toSet()),
                "mehmonxona — bo'limlardan");
        assertEquals(2, saved.getDepartments().size());

        // Begona egasi bu xodimni ko'rmaydi va o'zgartira olmaydi, o'z bo'limiga ham qo'sha olmaydi.
        assertTrue(teamService.staff(otherOwner).isEmpty());
        assertThrows(AccessDeniedException.class, () -> teamService.staffMember(otherOwner, staff.getId()));
        Department foreignDept = teamService.createDepartment(otherOwner, foreign.getId(), "Resepshn");
        assertThrows(AccessDeniedException.class, () -> teamService.createStaff(owner, "spy", "password1", null, null,
                List.of(foreignDept.getId())));
        // Xodim o'zi jamoani boshqara olmaydi; egasini "xodim" sifatida tahrirlab bo'lmaydi.
        assertThrows(AccessDeniedException.class, () -> teamService.departments(new CustomUserDetails(saved)));
        assertThrows(AccessDeniedException.class, () -> teamService.staffMember(owner, owner.getId()));

        // Bo'limdan chiqarish va qayta nomlash; bo'lim o'chirilsa — xodim qoladi.
        teamService.updateStaff(owner, staff.getId(), "aziza", "Aziza K.", null, true, List.of(accounting.getId()), null);
        assertEquals(1, userRepository.findWithTeam(staff.getId()).orElseThrow().getDepartments().size());
        teamService.renameDepartment(owner, accounting.getId(), "Moliya");
        assertEquals("Moliya", departmentRepository.findById(accounting.getId()).orElseThrow().getName());
        teamService.deleteDepartment(owner, accounting.getId());
        assertTrue(userRepository.findWithTeam(staff.getId()).orElseThrow().getDepartments().isEmpty());
        assertTrue(userRepository.findById(staff.getId()).isPresent());
    }

    @Test
    void taskIsGivenWithinDepartment() {
        Department reception = teamService.createDepartment(owner, hotel.getId(), "Resepshn");
        Department accounting = teamService.createDepartment(owner, hotel.getId(), "Buxgalteriya");
        User cashier = teamService.createStaff(owner, "kassir", "password1", "Kassir", null, List.of(accounting.getId()));
        LocalDate today = LocalDate.now(clock);

        // Aniq bo'lim tanlansa — xodim shu bo'limda bo'lishi shart.
        assertThrows(TaskService.TaskException.class, () -> taskService.create(owner, new TaskService.NewTask(hotel.getId(),
                cashier.getId(), "Vyselenie", null, null, null, today, Task.SOURCE_MANUAL, null, reception.getId()), TaskEvent.SITE));
        Task ok = taskService.create(owner, new TaskService.NewTask(hotel.getId(), cashier.getId(), "Qarzni undiring",
                null, null, null, today, Task.SOURCE_MANUAL, null, accounting.getId()), TaskEvent.SITE);
        assertEquals("Buxgalteriya", ok.getDepartment());
        assertEquals(accounting.getId(), departmentId(ok.getId()));

        // Tahlildan faqat nomi keladi: xodim shu bo'limda bo'lsa — bog'lanadi, bo'lmasa — nomi qoladi.
        Task byName = taskService.create(owner, new TaskService.NewTask(hotel.getId(), cashier.getId(), "Undiring",
                null, "buxgalteriya", null, today, Task.SOURCE_DEBT_ANALYSIS), TaskEvent.SITE);
        assertEquals(accounting.getId(), departmentId(byName.getId()));
        Task other = taskService.create(owner, new TaskService.NewTask(hotel.getId(), cashier.getId(), "Vyselenie",
                null, "Resepshn", null, today, Task.SOURCE_DEBT_ANALYSIS), TaskEvent.SITE);
        assertNull(departmentId(other.getId()));
        assertEquals("Resepshn", taskRepository.findById(other.getId()).orElseThrow().getDepartment());
    }

    @Test
    void newHotelGetsDefaultDepartments() {
        Hotel h = hotelService.save(null, "Fresh Hotel", "Xiva", 10, "UZS", null, null, null, null, true);
        assertEquals(List.of("Buxgalteriya", "Rahbariyat", "Resepshn"),
                departmentRepository.findAllByHotelIds(List.of(h.getId())).stream().map(Department::getName).toList());
    }

    @Test
    void pagesForOwnerAndForbiddenForStaff() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Department reception = teamService.createDepartment(owner, hotel.getId(), "Resepshn");

        // Menyu: egasida "Bo'limlar" va "Xodimlar" bor, platforma bo'limlari yo'q.
        String home = html(mvc, "/", owner);
        assertTrue(home.contains("/team/departments") && home.contains("/team/staff"));
        assertFalse(home.contains("/admin/users"));

        assertTrue(html(mvc, "/team/departments", owner).contains("Resepshn"));
        assertTrue(html(mvc, "/team/staff/new", owner).contains("name=\"departmentIds\""));

        // Forma orqali xodim qo'shish.
        String location = mvc.perform(post("/team/staff").with(user(owner)).with(csrf())
                        .param("username", "dilnoza").param("password", "password1").param("fullName", "Dilnoza")
                        .param("departmentIds", reception.getId().toString()))
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals("/team/staff", location);
        User staff = userRepository.findByUsername("dilnoza").orElseThrow();
        String list = html(mvc, "/team/staff", owner);
        assertTrue(list.contains("Dilnoza") && list.contains("Resepshn"));
        assertTrue(html(mvc, "/team/staff/" + staff.getId(), owner).contains("dilnoza"));

        // Topshiriq oynasi: bo'lim tanlovi va xodimning bo'limlari.
        String debts = html(mvc, "/hotels/" + hotel.getId() + "/debts", owner);
        assertTrue(debts.contains("id=\"taskDept\""));
        assertTrue(debts.contains("data-depts=\"" + reception.getId() + "\""), "xodim bo'limlari optionda");

        // Xodim va begona egasi jamoa sahifalariga kira olmaydi.
        CustomUserDetails s = new CustomUserDetails(staff);
        assertEquals(403, mvc.perform(get("/team/staff").with(user(s))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/team/staff/" + staff.getId()).with(user(otherOwner))).andReturn().getResponse().getStatus());
    }

    // ---------------------------------------------------------------- yordamchilar

    private Long departmentId(Long taskId) {
        return tx.execute(st -> {
            Department d = taskRepository.findById(taskId).orElseThrow().getDepartmentRef();
            return d == null ? null : d.getId();
        });
    }

    private static String html(MockMvc mvc, String url, CustomUserDetails u) throws Exception {
        var res = mvc.perform(get(url).with(user(u))).andReturn().getResponse();
        assertEquals(200, res.getStatus(), url);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private Hotel hotel(String name) {
        Hotel h = new Hotel();
        h.setName(name);
        h.setRoomsCount(10);
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
