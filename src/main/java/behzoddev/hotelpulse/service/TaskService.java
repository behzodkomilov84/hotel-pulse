package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.analysis.DebtAnalyzer;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.TaskItem;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.Task;
import behzoddev.hotelpulse.entity.TaskAction;
import behzoddev.hotelpulse.entity.TaskEvent;
import behzoddev.hotelpulse.entity.TaskStatus;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.TaskEventRepository;
import behzoddev.hotelpulse.repository.TaskItemRepository;
import behzoddev.hotelpulse.repository.TaskRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.telegram.TaskNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Topshiriqlar: tahlil tavsiyasidan (yoki qo'lda) mehmonxona xodimiga beriladi, xodim bajaradi,
 * topshiriq beruvchi tekshiradi (tasdiqlaydi yoki qaytaradi). Har bir amal tarixga yoziladi va
 * Telegram ulangan bo'lsa — ikkinchi tomonga botda xabar boradi.
 *
 * Ruxsatlar: topshiriqni platforma egasi, mehmonxona egasi va boshqaruv kompaniyasi beradi va tekshiradi
 * (mehmonxonaga ruxsati bo'lsa); bajaruvchi — faqat shu mehmonxonaga biriktirilgan xodim (HOTEL_STAFF).
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** Topshiriq bera oladigan va tekshira oladigan rollar. */
    public static final Set<Role> ASSIGNER_ROLES = EnumSet.of(Role.OWNER, Role.HOTEL_OWNER, Role.MANAGEMENT_COMPANY);

    static final int TITLE_MAX = 255;

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final TaskItemRepository itemRepository;
    private final DebtService debtService;
    private final UserRepository userRepository;
    private final HotelRepository hotelRepository;
    private final HotelService hotelService;
    private final TaskNotifier notifier;
    private final Clock clock;

    /**
     * Topshiriq berish uchun ma'lumotlar.
     *
     * @param listKey ilova qilinadigan bronlar ro'yxati (DebtAnalyzer.LIST_*); yo'q — null
     */
    public record NewTask(Long hotelId, Long assigneeId, String title, String description, String department,
                          String bookingNumber, LocalDate dueDate, String source, String listKey) {
        public NewTask(Long hotelId, Long assigneeId, String title, String description, String department,
                       String bookingNumber, LocalDate dueDate, String source) {
            this(hotelId, assigneeId, title, description, department, bookingNumber, dueDate, source, null);
        }
    }

    /** Ilova ro'yxati bo'yicha qisqacha: qatorlar, bajarilganlari, jami qarz. */
    public record ItemSummary(long count, long done, BigDecimal debt) {
    }

    public static boolean canAssign(CustomUserDetails user) {
        return ASSIGNER_ROLES.contains(user.getRole());
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Topshiriq beriladigan xodimlar — mehmonxonaga biriktirilgan faol HOTEL_STAFF. */
    @Transactional(readOnly = true)
    public List<User> staff(Long hotelId) {
        return userRepository.findActiveByHotelAndRole(hotelId, Role.HOTEL_STAFF);
    }

    @Transactional
    public Task create(CustomUserDetails by, NewTask n, String channel) {
        if (!canAssign(by)) {
            throw new AccessDeniedException("Topshiriq berishga ruxsat yo'q");
        }
        Hotel hotel = hotelService.getAccessible(by, n.hotelId());
        String title = n.title() == null ? "" : n.title().strip();
        if (title.isEmpty()) {
            throw new TaskException("Topshiriq matnini kiriting.");
        }
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX - 1) + "…";
        }
        if (n.dueDate() != null && n.dueDate().isBefore(today())) {
            throw new TaskException("Muddat o'tgan sana bo'lishi mumkin emas.");
        }
        User assignee = staff(hotel.getId()).stream()
                .filter(u -> u.getId().equals(n.assigneeId()))
                .findFirst()
                .orElseThrow(() -> new TaskException("Bajaruvchi shu mehmonxona xodimlaridan tanlanishi kerak."));

        LocalDateTime now = LocalDateTime.now(clock);
        Task task = new Task();
        task.setHotel(hotel);
        task.setTitle(title);
        task.setDescription(blankToNull(n.description()));
        task.setDepartment(blankToNull(n.department()));
        task.setBookingNumber(blankToNull(n.bookingNumber()));
        task.setSource(n.source() == null ? Task.SOURCE_MANUAL : n.source());
        task.setAssignedBy(userRepository.getReferenceById(by.getId()));
        task.setAssignee(assignee);
        task.setDueDate(n.dueDate());
        task.setStatus(TaskStatus.NEW);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        int attached = attachList(task, hotel, n.listKey());
        log(task, by.getId(), TaskAction.CREATED, channel,
                attached > 0 ? "Ilova: " + attached + " ta qarzdor yashash ro'yxati" : null);

        Task full = taskRepository.findFull(task.getId()).orElseThrow();
        notifier.created(full, itemRepository.findAllByTaskIdOrderByPositionAsc(full.getId()));
        return full;
    }

    /** Tahlil tavsiyasiga tegishli bronlar ro'yxati — joriy qarzdorlik hisobotidan, topshiriq berilgan paytdagi holat. */
    private int attachList(Task task, Hotel hotel, String listKey) {
        if (listKey == null || listKey.isBlank()) {
            return 0;
        }
        DebtReport report = debtService.report(hotel, null, null, "debt");
        List<DebtReport.Row> rows = DebtAnalyzer.listRows(report.rows(), listKey.strip());
        int pos = 0;
        for (DebtReport.Row r : rows) {
            TaskItem i = new TaskItem();
            i.setTask(task);
            i.setPosition(++pos);
            i.setBookingNumber(r.bookingNumber());
            i.setGuestName(r.guestName() == null ? null : cut(r.guestName(), 255));
            i.setSource(r.source() == null ? null : cut(r.source(), 255));
            i.setCategory(r.category().getLabel());
            i.setArrival(r.arrival());
            i.setDeparture(r.departure());
            i.setTotal(r.total());
            i.setPaid(r.paid());
            i.setDebt(r.debt());
            i.setAgeDays((int) r.ageDays());
            itemRepository.save(i);
        }
        return rows.size();
    }

    /** Ilova ro'yxati (topshiriqni ko'ra oladiganlar uchun). */
    @Transactional(readOnly = true)
    public List<TaskItem> items(CustomUserDetails user, Long taskId) {
        get(user, taskId);
        return itemRepository.findAllByTaskIdOrderByPositionAsc(taskId);
    }

    /** Ro'yxatlar bo'yicha qisqacha (topshiriqlar jadvali uchun). */
    @Transactional(readOnly = true)
    public Map<Long, ItemSummary> itemSummaries(List<Task> tasks) {
        Map<Long, ItemSummary> result = new HashMap<>();
        if (tasks.isEmpty()) {
            return result;
        }
        for (Object[] r : itemRepository.summarize(tasks.stream().map(Task::getId).toList())) {
            result.put((Long) r[0], new ItemSummary(((Number) r[1]).longValue(), ((Number) r[2]).longValue(), (BigDecimal) r[3]));
        }
        return result;
    }

    /** Xodim ro'yxat qatorini bajarildi deb belgilaydi / belgini olib tashlaydi. */
    @Transactional
    public TaskItem toggleItem(CustomUserDetails user, Long taskId, Long itemId) {
        Task task = forAssignee(user, taskId);
        if (!task.getStatus().isOpen()) {
            throw new TaskException("Topshiriq holati: " + task.getStatus().getLabel() + " — ro'yxatni o'zgartirib bo'lmaydi.");
        }
        TaskItem item = itemRepository.findById(itemId)
                .filter(i -> i.getTask().getId().equals(taskId))
                .orElseThrow(() -> new NotFoundException("Ro'yxat qatori topilmadi"));
        item.setDone(!item.isDone());
        item.setDoneAt(item.isDone() ? LocalDateTime.now(clock) : null);
        task.setUpdatedAt(LocalDateTime.now(clock));
        return item;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Foydalanuvchi ko'radigan topshiriqlar (yangilari birinchi). */
    @Transactional(readOnly = true)
    public List<Task> visible(CustomUserDetails user) {
        List<Long> hotelIds = canAssign(user)
                ? hotelService.accessibleHotels(user).stream().map(Hotel::getId).toList()
                : List.of();
        return taskRepository.findVisible(user.getId(), hotelIds.isEmpty() ? List.of(-1L) : hotelIds);
    }

    @Transactional(readOnly = true)
    public Task get(CustomUserDetails user, Long id) {
        Task task = taskRepository.findFull(id).orElseThrow(() -> new NotFoundException("Topshiriq topilmadi"));
        if (!isAssignee(user, task) && !canReview(user, task)) {
            throw new AccessDeniedException("Bu topshiriqqa ruxsat yo'q");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public List<TaskEvent> history(CustomUserDetails user, Long id) {
        get(user, id);
        return eventRepository.findAllByTaskIdOrderByCreatedAtAscIdAsc(id);
    }

    public static boolean isAssignee(CustomUserDetails user, Task task) {
        return task.getAssignee().getId().equals(user.getId());
    }

    /** Tekshira oladi: topshiriq beruvchi roldagi va mehmonxonaga ruxsati bor foydalanuvchi. */
    public boolean canReview(CustomUserDetails user, Task task) {
        return canAssign(user)
                && (user.isOwner() || hotelRepository.isUserLinked(user.getId(), task.getHotel().getId()));
    }

    // ---------------------------------------------------------------- Xodim amallari

    @Transactional
    public Task start(CustomUserDetails user, Long id, String channel) {
        Task task = forAssignee(user, id);
        if (task.getStatus() != TaskStatus.NEW && task.getStatus() != TaskStatus.RETURNED) {
            throw new TaskException("Topshiriq holati: " + task.getStatus().getLabel() + " — boshlab bo'lmaydi.");
        }
        change(task, TaskStatus.IN_PROGRESS);
        log(task, user.getId(), TaskAction.STARTED, channel, null);
        return task;
    }

    @Transactional
    public Task complete(CustomUserDetails user, Long id, String comment, String channel) {
        Task task = forAssignee(user, id);
        if (!task.getStatus().isOpen()) {
            throw new TaskException("Topshiriq holati: " + task.getStatus().getLabel() + ".");
        }
        change(task, TaskStatus.REVIEW);
        task.setCompletedAt(LocalDateTime.now(clock));
        // Ilova bo'lsa — nechta qator belgilangani tekshiruvchiga ham ko'rinsin.
        List<TaskItem> items = itemRepository.findAllByTaskIdOrderByPositionAsc(id);
        String progress = items.isEmpty() ? null
                : "Ro'yxat: " + items.stream().filter(TaskItem::isDone).count() + " / " + items.size() + " ta belgilangan";
        String text = progress == null ? blankToNull(comment)
                : blankToNull(comment) == null ? progress : comment.strip() + "\n" + progress;
        log(task, user.getId(), TaskAction.COMPLETED, channel, text);
        notifier.completed(task, text);
        return task;
    }

    // ---------------------------------------------------------------- Tekshiruvchi amallari

    @Transactional
    public Task accept(CustomUserDetails user, Long id, String comment, String channel) {
        Task task = forReviewer(user, id);
        if (task.getStatus() != TaskStatus.REVIEW) {
            throw new TaskException("Faqat \"Tekshiruvda\" turgan topshiriqni tasdiqlash mumkin.");
        }
        change(task, TaskStatus.DONE);
        task.setReviewedAt(LocalDateTime.now(clock));
        log(task, user.getId(), TaskAction.ACCEPTED, channel, comment);
        notifier.accepted(task, blankToNull(comment));
        return task;
    }

    @Transactional
    public Task returnTask(CustomUserDetails user, Long id, String comment, String channel) {
        Task task = forReviewer(user, id);
        if (task.getStatus() != TaskStatus.REVIEW) {
            throw new TaskException("Faqat \"Tekshiruvda\" turgan topshiriqni qaytarish mumkin.");
        }
        if (blankToNull(comment) == null) {
            throw new TaskException("Qaytarish sababini yozing — xodim nimani tuzatishini bilishi kerak.");
        }
        change(task, TaskStatus.RETURNED);
        task.setReviewedAt(LocalDateTime.now(clock));
        log(task, user.getId(), TaskAction.RETURNED, channel, comment);
        notifier.returned(task, comment.strip());
        return task;
    }

    @Transactional
    public Task cancel(CustomUserDetails user, Long id, String comment, String channel) {
        Task task = forReviewer(user, id);
        if (task.getStatus().isFinished()) {
            throw new TaskException("Topshiriq allaqachon yakunlangan.");
        }
        change(task, TaskStatus.CANCELLED);
        log(task, user.getId(), TaskAction.CANCELLED, channel, comment);
        notifier.cancelled(task, blankToNull(comment));
        return task;
    }

    /** Izoh — xodim ham, tekshiruvchi ham yozadi; ikkinchi tomonga botda boradi. */
    @Transactional
    public Task comment(CustomUserDetails user, Long id, String text, String channel) {
        Task task = get(user, id);
        if (blankToNull(text) == null) {
            throw new TaskException("Izoh bo'sh.");
        }
        task.setUpdatedAt(LocalDateTime.now(clock));
        log(task, user.getId(), TaskAction.COMMENT, channel, text);
        notifier.commented(task, user.getId(), user.getDisplayName(), text.strip());
        return task;
    }

    // ---------------------------------------------------------------- Eslatmalar

    /**
     * Muddati bugun yoki o'tgan ochiq topshiriqlar — bugun hali eslatilmaganlari. Har biri uchun bitta eslatma
     * (xodimga; muddati o'tganlari — topshiriq beruvchiga ham). @return eslatilgan topshiriqlar soni
     */
    @Transactional
    public int remindDue() {
        LocalDate today = today();
        List<Task> due = taskRepository.findAllByStatusInAndDueDateLessThanEqual(TaskStatus.OPEN, today).stream()
                .filter(t -> !today.equals(t.getLastRemindedOn()))
                .toList();
        for (Task t : due) {
            t.setLastRemindedOn(today);
            log(t, null, TaskAction.REMINDED, TaskEvent.SYSTEM, t.isOverdue(today) ? "Muddati o'tgan" : "Muddat — bugun");
        }
        if (!due.isEmpty()) {
            notifier.reminders(due, today);
        }
        return due.size();
    }

    // ---------------------------------------------------------------- yordamchilar

    private Task forAssignee(CustomUserDetails user, Long id) {
        Task task = taskRepository.findFull(id).orElseThrow(() -> new NotFoundException("Topshiriq topilmadi"));
        if (!isAssignee(user, task)) {
            throw new AccessDeniedException("Bu amalni faqat bajaruvchi qiladi");
        }
        return task;
    }

    private Task forReviewer(CustomUserDetails user, Long id) {
        Task task = taskRepository.findFull(id).orElseThrow(() -> new NotFoundException("Topshiriq topilmadi"));
        if (!canReview(user, task)) {
            throw new AccessDeniedException("Bu amalni topshiriq beruvchi qiladi");
        }
        return task;
    }

    private void change(Task task, TaskStatus status) {
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now(clock));
    }

    private void log(Task task, Long userId, TaskAction action, String channel, String comment) {
        TaskEvent e = new TaskEvent();
        e.setTask(task);
        e.setUser(userId == null ? null : userRepository.getReferenceById(userId));
        e.setAction(action);
        e.setChannel(channel);
        e.setComment(blankToNull(comment));
        e.setCreatedAt(LocalDateTime.now(clock));
        eventRepository.save(e);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Foydalanuvchiga ko'rsatiladigan xato (noto'g'ri holat yoki ma'lumot). */
    public static class TaskException extends RuntimeException {
        public TaskException(String message) {
            super(message);
        }
    }
}
