package behzoddev.hotelpulse.controller;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Task;
import behzoddev.hotelpulse.entity.TaskEvent;
import behzoddev.hotelpulse.entity.TaskItem;
import behzoddev.hotelpulse.entity.TaskStatus;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.TaskService;
import behzoddev.hotelpulse.service.TaskService.TaskException;
import behzoddev.hotelpulse.telegram.TaskNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** "Xizmatlar → Topshiriqlar": ro'yxat, tafsilot va tarix, topshiriq berish, bajarish va tekshirish. */
@Controller
@RequestMapping("/services/tasks")
@RequiredArgsConstructor
public class TaskController {

    static final int PAGE_SIZE = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Ro'yxat bo'limlari (tab). */
    static final Map<String, String> VIEWS = new LinkedHashMap<>();

    static {
        VIEWS.put("open", "Bajarilishi kerak");
        VIEWS.put("review", "Tekshiruvda");
        VIEWS.put("overdue", "Muddati o'tgan");
        VIEWS.put("finished", "Yakunlangan");
        VIEWS.put("all", "Hammasi");
    }

    private final TaskService taskService;
    private final HotelService hotelService;

    /** Bajaruvchi tanlovi: mehmonxona bo'yicha guruhlangan xodimlar ("hotelId:userId"). */
    public record StaffGroup(Hotel hotel, List<User> staff, List<behzoddev.hotelpulse.entity.Department> departments) {
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(defaultValue = "open") String view,
                       @RequestParam(required = false) Long hotel,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        String v = VIEWS.containsKey(view) ? view : "open";
        LocalDate today = taskService.today();
        List<Task> all = taskService.visible(user).stream()
                .filter(t -> hotel == null || t.getHotel().getId().equals(hotel))
                .toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        VIEWS.keySet().forEach(k -> counts.put(k, all.stream().filter(filter(k, today)).count()));
        List<Task> rows = all.stream().filter(filter(v, today)).toList();
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(Math.max(page, 1), pages);

        List<Hotel> hotels = hotelService.accessibleHotels(user);
        boolean canAssign = TaskService.canAssign(user);
        model.addAttribute("views", VIEWS);
        model.addAttribute("view", v);
        model.addAttribute("counts", counts);
        List<Task> pageRows = rows.subList((p - 1) * PAGE_SIZE, Math.min(rows.size(), p * PAGE_SIZE));
        model.addAttribute("rows", pageRows);
        model.addAttribute("itemSummaries", taskService.itemSummaries(pageRows));
        model.addAttribute("total", rows.size());
        model.addAttribute("page", p);
        model.addAttribute("pages", pages);
        model.addAttribute("hotels", hotels);
        model.addAttribute("hotel", hotel);
        model.addAttribute("showHotel", hotel == null && hotels.size() > 1);
        model.addAttribute("today", today);
        model.addAttribute("canAssign", canAssign);
        if (canAssign) {
            model.addAttribute("staffGroups", staffGroups(hotels));
        }
        return "services/tasks";
    }

    @GetMapping("/{id}")
    public String detail(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, Model model) {
        Task task = taskService.get(user, id);
        List<TaskEvent> history = taskService.history(user, id);
        model.addAttribute("t", task);
        model.addAttribute("history", history);
        model.addAttribute("items", taskService.items(user, id));
        model.addAttribute("today", taskService.today());
        model.addAttribute("isAssignee", TaskService.isAssignee(user, task));
        model.addAttribute("canReview", taskService.canReview(user, task));
        return "services/task";
    }

    /** Topshiriq berish (qarzdorlik tahlilidan yoki "Topshiriqlar" sahifasidan). target — "hotelId:userId". */
    @PostMapping
    public String create(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam String target,
                         @RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String department,
                         @RequestParam(required = false) String bookingNumber,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String listKey,
                         @RequestParam(required = false) Long departmentId,
                         @RequestParam(required = false) String back,
                         RedirectAttributes ra) {
        String[] parts = target.split(":");
        try {
            if (parts.length != 2) {
                throw new TaskException("Bajaruvchini tanlang.");
            }
            String src = Task.SOURCE_DEBT_ANALYSIS.equals(source) ? source : Task.SOURCE_MANUAL;
            Task task = taskService.create(user, new TaskService.NewTask(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    title, description, department, bookingNumber, dueDate, src, listKey, departmentId), TaskEvent.SITE);
            String tg = task.getAssignee().getTelegramChatId() != null ? " Xodimga Telegram'da xabar yuborildi."
                    : " Xodim Telegram'ni ulamagan — topshiriqni saytda ko'radi.";
            ra.addFlashAttribute("success", "Topshiriq #" + task.getId() + " berildi: " + TaskNotifier.name(task.getAssignee()) + "." + tg);
        } catch (TaskException | NumberFormatException e) {
            ra.addFlashAttribute("error", e instanceof TaskException ? e.getMessage() : "Bajaruvchini tanlang.");
        }
        return "redirect:" + safeBack(back);
    }

    @PostMapping("/{id}/{action}")
    public String action(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, @PathVariable String action,
                         @RequestParam(required = false) String comment, RedirectAttributes ra) {
        try {
            String msg = switch (action) {
                case "start" -> {
                    taskService.start(user, id, TaskEvent.SITE);
                    yield "Topshiriq bajarilmoqda deb belgilandi.";
                }
                case "complete" -> {
                    taskService.complete(user, id, comment, TaskEvent.SITE);
                    yield "Bajarildi deb belgilandi — topshiriq beruvchi tekshiradi.";
                }
                case "accept" -> {
                    taskService.accept(user, id, comment, TaskEvent.SITE);
                    yield "Bajarilgani tasdiqlandi.";
                }
                case "return" -> {
                    taskService.returnTask(user, id, comment, TaskEvent.SITE);
                    yield "Topshiriq qayta bajarishga qaytarildi.";
                }
                case "cancel" -> {
                    taskService.cancel(user, id, comment, TaskEvent.SITE);
                    yield "Topshiriq bekor qilindi.";
                }
                case "comment" -> {
                    taskService.comment(user, id, comment, TaskEvent.SITE);
                    yield "Izoh qo'shildi.";
                }
                default -> throw new TaskException("Noma'lum amal.");
            };
            ra.addFlashAttribute("success", msg);
        } catch (TaskException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/services/tasks/" + id;
    }

    /** Ilova ro'yxatidagi qatorni bajarildi deb belgilash / belgini olish (xodim). */
    @PostMapping("/{id}/items/{itemId}")
    public String toggleItem(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, @PathVariable Long itemId,
                             RedirectAttributes ra) {
        try {
            taskService.toggleItem(user, id, itemId);
        } catch (TaskException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/services/tasks/" + id + "#item-" + itemId;
    }

    /** Ilova ro'yxati — Excel uchun CSV. */
    @GetMapping(value = "/{id}/items.csv", produces = "text/csv")
    public ResponseEntity<byte[]> itemsCsv(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id) {
        Task task = taskService.get(user, id);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("№;Bron raqami;Mehmon;Manba;Holat;Kelish;Ketish;Narx;To'langan;Qarz;O'tgan kun;Bajarildi;Valyuta\n");
        for (TaskItem i : taskService.items(user, id)) {
            sb.append(i.getPosition()).append(';')
                    .append(csv(i.getBookingNumber())).append(';')
                    .append(csv(i.getGuestName())).append(';')
                    .append(csv(i.getSource())).append(';')
                    .append(csv(i.getCategory())).append(';')
                    .append(i.getArrival() == null ? "" : i.getArrival().format(DAY)).append(';')
                    .append(i.getDeparture() == null ? "" : i.getDeparture().format(DAY)).append(';')
                    .append(i.getTotal().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(i.getPaid().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(i.getDebt().setScale(0, RoundingMode.HALF_UP).toPlainString()).append(';')
                    .append(i.getAgeDays()).append(';')
                    .append(i.isDone() ? "ha" : "").append(';')
                    .append(task.getHotel().getCurrency()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"topshiriq-" + id + "-royxat.csv\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Excel uchun CSV — tanlangan bo'lim va mehmonxona bo'yicha barcha topshiriqlar (tarixdagi oxirgi izohsiz). */
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam(defaultValue = "all") String view,
                                      @RequestParam(required = false) Long hotel) {
        LocalDate today = taskService.today();
        String v = VIEWS.containsKey(view) ? view : "all";
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("№;Mehmonxona;Topshiriq;Izoh;Bo'lim;Bron;Bajaruvchi;Topshiriq berdi;Berilgan;Muddat;Holat;Muddati o'tgan;"
                + "Bajarildi deb belgilangan;Tekshirilgan;Ilova (yashashlar);Ilovadan bajarilgan\n");
        List<Task> visible = taskService.visible(user);
        Map<Long, TaskService.ItemSummary> sums = taskService.itemSummaries(visible);
        for (Task t : visible) {
            TaskService.ItemSummary is = sums.get(t.getId());
            if ((hotel != null && !t.getHotel().getId().equals(hotel)) || !filter(v, today).test(t)) {
                continue;
            }
            sb.append(t.getId()).append(';')
                    .append(csv(t.getHotel().getName())).append(';')
                    .append(csv(t.getTitle())).append(';')
                    .append(csv(t.getDescription())).append(';')
                    .append(csv(t.getDepartment())).append(';')
                    .append(csv(t.getBookingNumber())).append(';')
                    .append(csv(TaskNotifier.name(t.getAssignee()))).append(';')
                    .append(csv(TaskNotifier.name(t.getAssignedBy()))).append(';')
                    .append(t.getCreatedAt().format(TIME)).append(';')
                    .append(t.getDueDate() == null ? "" : t.getDueDate().format(DAY)).append(';')
                    .append(csv(t.getStatus().getLabel())).append(';')
                    .append(t.isOverdue(today) ? "ha" : "").append(';')
                    .append(t.getCompletedAt() == null ? "" : t.getCompletedAt().format(TIME)).append(';')
                    .append(t.getReviewedAt() == null ? "" : t.getReviewedAt().format(TIME)).append(';')
                    .append(is == null ? "" : String.valueOf(is.count())).append(';')
                    .append(is == null ? "" : String.valueOf(is.done())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"topshiriqlar-" + today + ".csv\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- yordamchilar

    static Predicate<Task> filter(String view, LocalDate today) {
        return switch (view) {
            case "open" -> t -> t.getStatus().isOpen();
            case "review" -> t -> t.getStatus() == TaskStatus.REVIEW;
            case "overdue" -> t -> t.isOverdue(today);
            case "finished" -> t -> t.getStatus().isFinished();
            default -> t -> true;
        };
    }

    List<StaffGroup> staffGroups(List<Hotel> hotels) {
        List<StaffGroup> groups = new ArrayList<>();
        for (Hotel h : hotels) {
            if (h.isActive()) {
                groups.add(new StaffGroup(h, taskService.staff(h.getId()), taskService.departments(h.getId())));
            }
        }
        return groups;
    }

    /** Faqat sayt ichidagi nisbiy manzil (ochiq redirect bo'lmasligi uchun). */
    static String safeBack(String back) {
        return back != null && back.startsWith("/") && !back.startsWith("//") && !back.contains("\\")
                ? back : "/services/tasks";
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ");
        return v.contains(";") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
