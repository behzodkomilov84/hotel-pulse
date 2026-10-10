package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.repository.*;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Topshiriqlar: tahlildan berish (bot va sayt), bajarish, tekshirish, ruxsatlar, eslatmalar (hotel_pulse_test). */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token=",
        "app.telegram.site-url=https://hotelpulse.example"
})
class TaskFlowDbTest {

    static class FakeGateway implements TelegramGateway {
        record Sent(long chatId, String html, Map<String, Object> markup) {
        }

        final List<Sent> sent = new ArrayList<>();

        @Override
        public void sendMessage(long chatId, String html, Map<String, Object> replyMarkup) {
            sent.add(new Sent(chatId, html, replyMarkup));
        }

        @Override
        public void answerCallback(String id, String text) {
        }

        @Override
        public String botUsername() {
            return "HotelPulseTestBot";
        }

        Sent last(long chatId) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).chatId() == chatId) {
                    return sent.get(i);
                }
            }
            fail("chat " + chatId + " ga xabar yuborilmagan");
            return null;
        }

        long count(long chatId) {
            return sent.stream().filter(s -> s.chatId() == chatId).count();
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

    static final long OWNER_CHAT = 5001, STAFF_CHAT = 5002, OTHER_CHAT = 5003;

    @Autowired private TelegramBotService bot;
    @Autowired private TelegramLinkService linkService;
    @Autowired private FakeGateway gateway;
    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskEventRepository eventRepository;
    @Autowired private TaskItemRepository itemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private WebApplicationContext context;
    @Autowired private Clock clock;

    private Hotel hotel;
    private User owner;
    private User staff;
    private User otherStaff;
    private LocalDate today;
    private int seq;

    @BeforeEach
    void setUp() {
        gateway.sent.clear();
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        today = LocalDate.now(clock);
        hotel = hotel("Task Hotel");
        Hotel other = hotel("Other Hotel");
        // Qarzlar: ketgan mehmon, vyselenie qilinmagan eski yashash, hozir yashayotgan.
        booking("Karimov", BookingStatus.CHECKED_OUT, today.minusDays(43), today.minusDays(40), "1000000", "300000");
        booking("Valiyev", BookingStatus.CHECKED_IN, today.minusDays(103), today.minusDays(100), "500000", "500000");
        booking("Smith", BookingStatus.CHECKED_IN, today.minusDays(1), today.plusDays(2), "600000", "200000");

        owner = saveUser("hotel-owner", Role.HOTEL_OWNER, Set.of(hotel));
        owner.setFullName("Egasi");
        owner = userRepository.save(owner);
        staff = saveUser("reception", Role.HOTEL_STAFF, Set.of(hotel));
        staff.setFullName("Resepshn Xodimi");
        staff = userRepository.save(staff);
        otherStaff = saveUser("other-staff", Role.HOTEL_STAFF, Set.of(other));
        link(owner, OWNER_CHAT);
        link(staff, STAFF_CHAT);
        link(otherStaff, OTHER_CHAT);
        gateway.sent.clear();
    }

    @Test
    void analysisInBotBecomesTaskThenStaffCompletesAndOwnerReviews() {
        // 1) Tahlil botda — egasiga "📌" tugmalari bilan.
        bot.handle(msg(OWNER_CHAT, TelegramBotService.BTN_ANALYSIS));
        var analysis = gateway.last(OWNER_CHAT);
        assertTrue(analysis.html().contains("Қарздорлик таҳлили"), analysis.html());
        assertTrue(analysis.html().contains("Тавсиялар"));
        String kb = analysis.markup().toString();
        assertTrue(kb.contains("ta:a:" + hotel.getId() + ":0"), kb);
        assertTrue(kb.contains("ta:p:" + hotel.getId() + ":0"), "брон бўйича ҳам топшириқ");

        // 2) Tavsiya → xodim (faqat shu mehmonxona xodimi) → muddat → topshiriq.
        bot.handle(cb(OWNER_CHAT, "ta:a:" + hotel.getId() + ":0"));
        String staffKb = gateway.last(OWNER_CHAT).markup().toString();
        assertTrue(staffKb.contains("tb:a:" + hotel.getId() + ":0:" + staff.getId()), staffKb);
        assertFalse(staffKb.contains(":" + otherStaff.getId() + "\""), "бошқа меҳмонхона ходими рўйхатда йўқ");
        bot.handle(cb(OWNER_CHAT, "tb:a:" + hotel.getId() + ":0:" + staff.getId()));
        assertTrue(gateway.last(OWNER_CHAT).markup().toString().contains("tc:a:" + hotel.getId() + ":0:" + staff.getId() + ":3"));
        bot.handle(cb(OWNER_CHAT, "tc:a:" + hotel.getId() + ":0:" + staff.getId() + ":3"));
        assertTrue(gateway.last(OWNER_CHAT).html().contains("Топшириқ берилди"));

        Task task = taskRepository.findAll().get(0);
        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(Task.SOURCE_DEBT_ANALYSIS, task.getSource());
        assertEquals(today.plusDays(3), task.getDueDate());
        assertNotNull(task.getDepartment(), "бўлим тавсиядан олинади");
        long id = task.getId();

        // Tavsiyaga tegishli ro'yxat ilova qilingan (1-tavsiya — vyselenie qilinmagan: Valiyev).
        var items = itemRepository.findAllByTaskIdOrderByPositionAsc(task.getId());
        assertEquals(1, items.size());
        assertEquals("Valiyev", items.get(0).getGuestName());
        assertEquals(0, new BigDecimal("500000").compareTo(items.get(0).getDebt()));

        // Xodimga botda xabar — ilova va "Boshladim" / "Bajarildi" tugmalari bilan.
        var toStaff = gateway.last(STAFF_CHAT);
        assertTrue(toStaff.html().contains("Илова: 1 та яшаш"), toStaff.html());
        assertTrue(toStaff.html().contains("Valiyev"));
        assertTrue(toStaff.html().contains("Сизга янги топшириқ"));
        assertTrue(toStaff.markup().toString().contains("k:s:" + id));
        assertTrue(toStaff.markup().toString().contains("k:c:" + id));

        // 3) Xodim: bajarildi → izoh yozadi; egasiga "tekshiring" va izoh boradi.
        bot.handle(cb(STAFF_CHAT, "k:c:" + id));
        assertEquals(TaskStatus.REVIEW, status(id));
        var review = gateway.last(OWNER_CHAT);
        assertTrue(review.html().contains("текширинг"));
        assertTrue(review.markup().toString().contains("k:a:" + id));
        bot.handle(msg(STAFF_CHAT, "2 ta yashash vyselenie qilindi"));
        assertTrue(gateway.last(OWNER_CHAT).html().contains("2 ta yashash vyselenie qilindi"));

        // 4) Egasi: qaytaradi — sabab so'raladi, keyingi matn sabab bo'ladi.
        bot.handle(cb(OWNER_CHAT, "k:r:" + id));
        assertTrue(gateway.last(OWNER_CHAT).html().contains("сабабини ёзинг"));
        bot.handle(msg(OWNER_CHAT, "Valiyev hali ham yashayapti"));
        assertEquals(TaskStatus.RETURNED, status(id));
        assertTrue(gateway.last(STAFF_CHAT).html().contains("Valiyev hali ham yashayapti"));

        // 5) Qayta bajarildi → tasdiqlandi.
        bot.handle(cb(STAFF_CHAT, "k:c:" + id));
        bot.handle(cb(OWNER_CHAT, "k:a:" + id));
        assertEquals(TaskStatus.DONE, status(id));
        assertTrue(gateway.last(STAFF_CHAT).html().contains("тасдиқланди"));

        // Tarix — har bir amal qayd etilgan.
        List<TaskAction> actions = eventRepository.findAllByTaskIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(TaskEvent::getAction).toList();
        assertEquals(List.of(TaskAction.CREATED, TaskAction.COMPLETED, TaskAction.COMMENT, TaskAction.RETURNED,
                TaskAction.COMPLETED, TaskAction.ACCEPTED), actions);

        // Ro'yxat botda: egasida — tekshiruvda/muddati o'tgan yo'q, xodimda — ochiq yo'q.
        bot.handle(msg(STAFF_CHAT, TelegramBotService.BTN_TASKS));
        assertTrue(gateway.last(STAFF_CHAT).html().contains("Очиқ топшириқ йўқ"));
    }

    @Test
    void menuKeyboardCanBeHiddenAndShownAgain() {
        bot.handle(msg(STAFF_CHAT, TelegramBotService.BTN_HIDE));
        assertEquals(Boolean.TRUE, gateway.last(STAFF_CHAT).markup().get("remove_keyboard"));
        bot.handle(msg(STAFF_CHAT, "/menyu"));
        String kb = gateway.last(STAFF_CHAT).markup().toString();
        assertTrue(kb.contains(TelegramBotService.BTN_TASKS) && kb.contains(TelegramBotService.BTN_HIDE), kb);
    }

    @Test
    void permissionsAreEnforced() {
        CustomUserDetails o = new CustomUserDetails(owner);
        CustomUserDetails s = new CustomUserDetails(staff);
        CustomUserDetails x = new CustomUserDetails(otherStaff);

        assertThrows(AccessDeniedException.class, () -> taskService.create(s, newTask(staff.getId()), TaskEvent.SITE),
                "xodim topshiriq bera olmaydi");
        assertThrows(TaskService.TaskException.class, () -> taskService.create(o, newTask(otherStaff.getId()), TaskEvent.SITE),
                "boshqa mehmonxona xodimiga berib bo'lmaydi");

        Task t = taskService.create(o, newTask(staff.getId()), TaskEvent.SITE);
        assertThrows(AccessDeniedException.class, () -> taskService.get(x, t.getId()), "бегона ходим кўрмайди");
        assertThrows(AccessDeniedException.class, () -> taskService.complete(o, t.getId(), null, TaskEvent.SITE),
                "bajarildi — faqat bajaruvchi");
        taskService.complete(s, t.getId(), null, TaskEvent.SITE);
        assertThrows(AccessDeniedException.class, () -> taskService.accept(s, t.getId(), null, TaskEvent.SITE),
                "xodim o'zini tasdiqlay olmaydi");
        assertThrows(TaskService.TaskException.class, () -> taskService.returnTask(o, t.getId(), " ", TaskEvent.SITE),
                "qaytarishda sabab majburiy");

        // Xodim botda tahlilni ko'radi, lekin topshiriq berish tugmalari yo'q.
        bot.handle(msg(STAFF_CHAT, "/tahlil"));
        var a = gateway.last(STAFF_CHAT);
        assertTrue(a.html().contains("Қарздорлик таҳлили"));
        assertNull(a.markup());
        bot.handle(cb(STAFF_CHAT, "ta:a:" + hotel.getId() + ":0"));
        assertTrue(gateway.last(STAFF_CHAT).html().contains("Рухсат йўқ"));
    }

    @Test
    void overdueRemindersAreSentOncePerDay() {
        Task t = taskService.create(new CustomUserDetails(owner), newTask(staff.getId()), TaskEvent.SITE);
        t.setDueDate(today.minusDays(2));
        taskRepository.save(t);
        gateway.sent.clear();

        assertEquals(1, taskService.remindDue());
        assertTrue(gateway.last(STAFF_CHAT).html().contains("муддати ўтди"));
        assertTrue(gateway.last(STAFF_CHAT).html().contains("2 кун"));
        assertTrue(gateway.last(OWNER_CHAT).html().contains("Муддати ўтган топшириқлар"));

        long before = gateway.sent.size();
        assertEquals(0, taskService.remindDue(), "бугун аллақачон эслатилган");
        assertEquals(before, gateway.sent.size());
        assertTrue(taskRepository.findById(t.getId()).orElseThrow().isOverdue(today));
    }

    @Test
    void sitePagesCreateCompleteAndReview() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        CustomUserDetails o = new CustomUserDetails(owner);
        CustomUserDetails s = new CustomUserDetails(staff);

        // Qarzdorlik sahifasi va tahlil: egasida — oyna va "Topshiriq berish" tugmalari, xodimda — yo'q.
        String debts = html(mvc, "/hotels/" + hotel.getId() + "/debts", o);
        assertTrue(debts.contains("id=\"taskModal\""));
        assertTrue(debts.contains(hotel.getId() + ":" + staff.getId()), "ходим танловда");
        assertTrue(html(mvc, "/hotels/" + hotel.getId() + "/debts/analysis", o).contains("data-task-title"));
        assertFalse(html(mvc, "/hotels/" + hotel.getId() + "/debts", s).contains("id=\"taskModal\""));
        assertFalse(html(mvc, "/hotels/" + hotel.getId() + "/debts/analysis", s).contains("data-task-title"));

        // Topshiriq berish (forma).
        String location = mvc.perform(post("/services/tasks").with(user(o)).with(csrf())
                        .param("target", hotel.getId() + ":" + staff.getId())
                        .param("title", "Valiyev yashashini vyselenie qiling")
                        .param("department", "Resepshn")
                        .param("dueDate", today.plusDays(1).toString())
                        .param("source", "DEBT_ANALYSIS")
                        .param("listKey", "CHECKED_OUT")
                        .param("back", "/hotels/" + hotel.getId() + "/debts"))
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals("/hotels/" + hotel.getId() + "/debts", location);
        Task t = taskRepository.findAll().get(0);
        assertTrue(gateway.last(STAFF_CHAT).html().contains("Valiyev yashashini vyselenie qiling"));

        // Ochiq redirect yo'q.
        String evil = mvc.perform(post("/services/tasks").with(user(o)).with(csrf())
                        .param("target", hotel.getId() + ":" + staff.getId()).param("title", "x").param("back", "//evil.example"))
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals("/services/tasks", evil);

        // Ro'yxat va tafsilot.
        String list = html(mvc, "/services/tasks", s);
        assertTrue(list.contains("Valiyev yashashini vyselenie qiling"));
        String detail = html(mvc, "/services/tasks/" + t.getId(), s);
        assertTrue(detail.contains("Бажарилди"));
        assertTrue(detail.contains("Тарих"));

        // Ilova: ketgan mehmon (Karimov) — sahifada, Excel'da; xodim qatorni belgilaydi, begona belgilay olmaydi.
        var items = itemRepository.findAllByTaskIdOrderByPositionAsc(t.getId());
        assertEquals(1, items.size());
        assertEquals("Karimov", items.get(0).getGuestName());
        assertTrue(detail.contains("Ilova:"));
        assertTrue(detail.contains("Karimov"));
        assertTrue(html(mvc, "/services/tasks", o).contains("📎 1 та"));
        long itemId = items.get(0).getId();
        mvc.perform(post("/services/tasks/" + t.getId() + "/items/" + itemId).with(user(o)).with(csrf()));
        assertFalse(itemRepository.findById(itemId).orElseThrow().isDone(), "фақат бажарувчи белгилайди");
        mvc.perform(post("/services/tasks/" + t.getId() + "/items/" + itemId).with(user(s)).with(csrf()));
        assertTrue(itemRepository.findById(itemId).orElseThrow().isDone());
        String itemsCsv = new String(mvc.perform(get("/services/tasks/" + t.getId() + "/items.csv").with(user(o)))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(itemsCsv.contains("Karimov") && itemsCsv.contains(";ҳа;"), itemsCsv);

        // Xodim bajardi → egasi tasdiqladi; ro'yxat bo'yicha natija izohga yoziladi.
        mvc.perform(post("/services/tasks/" + t.getId() + "/complete").with(user(s)).with(csrf()).param("comment", "Bajardim"));
        assertEquals(TaskStatus.REVIEW, status(t.getId()));
        assertTrue(gateway.last(OWNER_CHAT).html().contains("Рўйхат: 1 / 1 та белгиланган"));
        assertTrue(html(mvc, "/services/tasks?view=review", o).contains("Valiyev yashashini vyselenie qiling"));
        assertTrue(html(mvc, "/services/tasks/" + t.getId(), o).contains("Тасдиқлаш"));
        mvc.perform(post("/services/tasks/" + t.getId() + "/accept").with(user(o)).with(csrf()));
        assertEquals(TaskStatus.DONE, status(t.getId()));

        // Excel.
        String csv = new String(mvc.perform(get("/services/tasks/export.csv").with(user(o)))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Бажарувчи"));
        assertTrue(csv.contains("Resepshn Xodimi"));
        assertTrue(csv.contains("Тасдиқланган"));

        // Begona xodim tafsilotni ko'rmaydi.
        int code = mvc.perform(get("/services/tasks/" + t.getId()).with(user(new CustomUserDetails(otherStaff))))
                .andReturn().getResponse().getStatus();
        assertEquals(403, code);
    }

    // ---------------------------------------------------------------- yordamchilar

    private TaskService.NewTask newTask(Long assigneeId) {
        return new TaskService.NewTask(hotel.getId(), assigneeId, "Qarzni undiring", null, "Buxgalteriya", null,
                today, Task.SOURCE_MANUAL);
    }

    private TaskStatus status(long id) {
        return taskRepository.findById(id).orElseThrow().getStatus();
    }

    private static String html(MockMvc mvc, String url, CustomUserDetails u) throws Exception {
        var res = mvc.perform(get(url).with(user(u))).andReturn().getResponse();
        assertEquals(200, res.getStatus(), url);
        return res.getContentAsString(StandardCharsets.UTF_8);
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

    private void link(User u, long chatId) {
        linkService.link(linkService.createToken(u.getId()), chatId);
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

    private void booking(String guest, BookingStatus status, LocalDate in, LocalDate out, String total, String due) {
        Booking b = new Booking();
        b.setHotelId(hotel.getId());
        b.setOrigin(DataOrigin.EXELY_PMS);
        seq++;
        b.setExternalId("pms:task-num-" + seq + "#rs" + seq);
        b.setSource("Test");
        b.setStatus(status);
        b.setGuestName(guest);
        b.setArrivalDate(in);
        b.setDepartureDate(out);
        b.setTotalAmount(new BigDecimal(total));
        b.setBalanceDue(new BigDecimal(due));
        b.setBookedAt(LocalDateTime.of(in.minusDays(10), java.time.LocalTime.NOON));
        bookingRepository.save(b);
    }
}
