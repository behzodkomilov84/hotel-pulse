package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.analysis.DebtAnalysis;
import behzoddev.hotelpulse.analysis.DebtAnalyzer;
import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Task;
import behzoddev.hotelpulse.entity.TaskEvent;
import behzoddev.hotelpulse.entity.TaskStatus;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.DebtService;
import behzoddev.hotelpulse.service.HotelService;
import behzoddev.hotelpulse.service.KpiService;
import behzoddev.hotelpulse.service.NotFoundException;
import behzoddev.hotelpulse.service.TaskService;
import behzoddev.hotelpulse.service.TaskService.TaskException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static behzoddev.hotelpulse.telegram.TelegramReportService.esc;

/**
 * Botda tahlil va topshiriqlar.
 * <ul>
 *   <li>🧠 Tahlil — qarzdorlik tahlili; topshiriq bera oladiganlarga har tavsiya/bron uchun "📌" tugmasi:
 *       tavsiya → xodim → muddat → topshiriq beriladi (tugmalar: ta → tb → tc).</li>
 *   <li>📌 Topshiriqlar — xodimga: o'ziga berilgan ochiq topshiriqlar; beruvchiga: tekshiruvdagi va muddati o'tganlar.</li>
 *   <li>Topshiriq tugmalari (k:{amal}:{id}): boshladim, bajarildi, tasdiqlash, qaytarish, ko'rish.
 *       "Qaytarish" sababni so'raydi — keyingi yozilgan matn sabab bo'ladi; "Bajarildi"dan keyin izoh yozish mumkin.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TelegramTaskHandler {

    static final int MAX_TEXT = 3900;          // Telegram chegarasi 4096
    static final int LIST_LIMIT = 15;
    static final long PENDING_MINUTES = 15;
    static final int[] DUE_DAYS = {1, 3, 7};

    private final TelegramGateway gateway;
    private final TaskService taskService;
    private final TaskNotifier notifier;
    private final HotelService hotelService;
    private final DebtService debtService;
    private final DebtAnalyzer debtAnalyzer;
    private final KpiService kpiService;
    private final Formats fmt;
    private final Clock clock;

    /** Javob kutilayotgan holat: qaytarish sababi yoki "bajarildi" izohi. */
    record Pending(String kind, long taskId, Instant expires) {
    }

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- Tahlil

    /** Bitta mehmonxona bo'lsa — darhol, bir nechta bo'lsa — tanlash tugmalari. */
    public void askAnalysis(long chatId, User user, List<Hotel> hotels) {
        if (hotels.isEmpty()) {
            send(chatId, "Sizga hali mehmonxona biriktirilmagan.", null);
            return;
        }
        if (hotels.size() == 1) {
            sendAnalysis(chatId, user, hotels.get(0));
            return;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (Hotel h : hotels) {
            rows.add(List.of(button("🏨 " + h.getName(), "an:" + h.getId())));
        }
        send(chatId, "🧠 Qaysi mehmonxona qarzdorligini tahlil qilay?", Map.of("inline_keyboard", rows));
    }

    void sendAnalysis(long chatId, User user, Hotel hotel) {
        DebtAnalysis a = analysis(hotel);
        StringBuilder sb = new StringBuilder("🧠 <b>Qarzdorlik tahlili</b> · ").append(esc(hotel.getName())).append("\n\n");
        if (a.warning() != null) {
            sb.append("⚠️ <i>").append(esc(a.warning())).append("</i>\n\n");
        }
        sb.append(esc(a.summary())).append("\n");
        if (!a.risks().isEmpty()) {
            sb.append("\n<b>Xavfli nuqtalar</b>\n");
            for (DebtAnalysis.Point p : a.risks()) {
                sb.append(levelIcon(p.level())).append(" <b>").append(esc(p.title())).append("</b> — ").append(esc(p.text())).append("\n");
            }
        }
        if (!a.actions().isEmpty()) {
            sb.append("\n<b>Tavsiyalar</b>\n");
            int i = 1;
            for (DebtAnalysis.Point p : a.actions()) {
                sb.append(i++).append(". <b>").append(esc(p.title())).append(":</b> ").append(esc(p.text()));
                if (p.listSize() > 0) {
                    sb.append(" <i>(📎 ").append(p.listSize()).append(" ta ro'yxat)</i>");
                }
                sb.append("\n");
            }
        }
        if (!a.priorities().isEmpty()) {
            sb.append("\n<b>Birinchi navbatda tekshirish kerak</b>\n");
            int i = 1;
            for (DebtAnalysis.Priority r : a.priorities()) {
                sb.append("B").append(i++).append(". <code>").append(esc(r.bookingNumber())).append("</code> ")
                        .append(esc(r.guestName() == null ? "—" : r.guestName())).append(" — <b>")
                        .append(fmt.moneyShort(r.debt(), hotel.getCurrency())).append("</b>\n   <i>").append(esc(r.reason())).append("</i>\n");
            }
        }
        Map<String, Object> markup = null;
        CustomUserDetails u = new CustomUserDetails(user);
        if (TaskService.canAssign(u) && (!a.actions().isEmpty() || !a.priorities().isEmpty())) {
            sb.append("\n📌 Tavsiyani xodimga topshiriq qilib berish uchun pastdagi tugmani bosing.");
            List<List<Map<String, Object>>> rows = new ArrayList<>();
            List<Map<String, Object>> row = new ArrayList<>();
            for (int i = 0; i < a.actions().size(); i++) {
                row.add(button("📌 " + (i + 1), "ta:a:" + hotel.getId() + ":" + i));
                if (row.size() == 4) {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            }
            for (int i = 0; i < a.priorities().size(); i++) {
                row.add(button("📌 B" + (i + 1), "ta:p:" + hotel.getId() + ":" + i));
                if (row.size() == 4) {
                    rows.add(row);
                    row = new ArrayList<>();
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
            markup = Map.of("inline_keyboard", rows);
        }
        send(chatId, truncate(sb.toString()), markup);
    }

    private DebtAnalysis analysis(Hotel hotel) {
        return debtAnalyzer.analyze(debtService.report(hotel, null, null, "debt"), hotel.getCurrency(),
                kpiService.paymentsComplete(hotel));
    }

    // ---------------------------------------------------------------- Tahlildan topshiriq berish

    /** ta:{a|p}:{hotel}:{i} → xodim tanlash. */
    private void chooseStaff(long chatId, CustomUserDetails u, String kind, Hotel hotel, int index) {
        Suggestion s = suggestion(hotel, kind, index);
        List<User> staff = taskService.staff(hotel.getId());
        if (staff.isEmpty()) {
            send(chatId, "👥 " + esc(hotel.getName()) + " ga hali <b>xodim</b> biriktirilmagan.\n\n"
                    + "Saytda <b>Boshqaruv → Foydalanuvchilar</b> bo'limida \"Mehmonxona xodimi\" roldagi foydalanuvchi qo'shib, "
                    + "mehmonxonaga biriktiring — keyin topshiriq berish mumkin.", null);
            return;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (User st : staff) {
            rows.add(List.of(button("👤 " + TaskNotifier.name(st) + (st.getTelegramChatId() != null ? "" : " (Telegram yo'q)"),
                    "tb:" + kind + ":" + hotel.getId() + ":" + index + ":" + st.getId())));
        }
        send(chatId, "📌 <b>Topshiriq:</b> " + esc(s.title())
                + (s.listSize() > 0 ? "\n📎 Ilova: " + s.listSize() + " ta yashash ro'yxati" : "") + "\n\nKimga beramiz?", Map.of("inline_keyboard", rows));
    }

    /** tb:… → muddat tanlash. */
    private void chooseDue(long chatId, String base) {
        LocalDate today = taskService.today();
        List<Map<String, Object>> row = new ArrayList<>();
        for (int d : DUE_DAYS) {
            row.add(button(d + " kun (" + today.plusDays(d).format(TaskNotifier.DAY).substring(0, 5) + ")", "tc:" + base + ":" + d));
        }
        send(chatId, "📅 Muddat?", Map.of("inline_keyboard", List.of(row, List.of(button("Muddatsiz", "tc:" + base + ":0")))));
    }

    /** tc:{kind}:{hotel}:{i}:{staff}:{days} → topshiriq beriladi. */
    private void createFromAnalysis(long chatId, CustomUserDetails u, String kind, Hotel hotel, int index, long staffId, int days) {
        Suggestion s = suggestion(hotel, kind, index);
        Task task = taskService.create(u, new TaskService.NewTask(hotel.getId(), staffId, s.title(), s.description(),
                s.department(), s.booking(), days > 0 ? taskService.today().plusDays(days) : null,
                Task.SOURCE_DEBT_ANALYSIS, s.listKey()), TaskEvent.BOT);
        String tg = task.getAssignee().getTelegramChatId() != null ? "Xodimga botda xabar yuborildi."
                : "Xodim Telegram'ni ulamagan — topshiriqni saytda ko'radi.";
        send(chatId, "✅ <b>Topshiriq berildi.</b> " + tg + "\n\n" + notifier.card(task)
                + notifier.itemsBlock(task, taskService.items(u, task.getId())), null);
    }

    /** Tahlildagi tavsiya yoki bron — topshiriq matni. */
    record Suggestion(String title, String description, String department, String booking, String listKey, int listSize) {
    }

    private Suggestion suggestion(Hotel hotel, String kind, int index) {
        DebtAnalysis a = analysis(hotel);
        if (kind.equals("a") && index >= 0 && index < a.actions().size()) {
            DebtAnalysis.Point p = a.actions().get(index);
            return new Suggestion(p.text(), null, p.title(), null, p.listKey(), p.listSize());
        }
        if (kind.equals("p") && index >= 0 && index < a.priorities().size()) {
            DebtAnalysis.Priority r = a.priorities().get(index);
            String cur = hotel.getCurrency();
            return new Suggestion("Bron " + r.bookingNumber() + (r.guestName() != null ? " (" + r.guestName() + ")" : "")
                    + ": qarzni tekshirish va undirish",
                    r.reason() + ". Qarz: " + fmt.money(r.debt(), cur) + ", to'langan " + fmt.moneyShort(r.paid(), cur)
                            + " / " + fmt.moneyShort(r.total(), cur) + ".", null, r.bookingNumber(),
                    DebtAnalyzer.LIST_BOOKING + r.bookingNumber(), 1);
        }
        throw new TaskException("Tahlil yangilangan — 🧠 Tahlil'ni qayta oching.");
    }

    // ---------------------------------------------------------------- Topshiriqlar ro'yxati

    public void sendTasks(long chatId, User user) {
        CustomUserDetails u = new CustomUserDetails(user);
        LocalDate today = taskService.today();
        List<Task> visible = taskService.visible(u);
        List<Task> mine = visible.stream().filter(t -> TaskService.isAssignee(u, t) && t.getStatus().isOpen()).toList();
        StringBuilder sb = new StringBuilder();
        List<List<Map<String, Object>>> rows = new ArrayList<>();

        if (!mine.isEmpty() || !TaskService.canAssign(u)) {
            sb.append("📌 <b>Sizning topshiriqlaringiz</b>");
            if (mine.isEmpty()) {
                sb.append("\n\nOchiq topshiriq yo'q ✅");
            } else {
                sb.append(" (").append(mine.size()).append(")\n\n");
                appendList(sb, rows, mine, today, false);
            }
        }
        if (TaskService.canAssign(u)) {
            List<Task> review = visible.stream().filter(t -> t.getStatus() == TaskStatus.REVIEW).toList();
            List<Task> overdue = visible.stream().filter(t -> t.isOverdue(today)).toList();
            long open = visible.stream().filter(t -> t.getStatus().isOpen()).count();
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("🗂 <b>Xodimlar topshiriqlari</b>\n")
                    .append("Bajarilmoqda: ").append(open)
                    .append(" · tekshiruvda: <b>").append(review.size()).append("</b>")
                    .append(" · muddati o'tgan: <b>").append(overdue.size()).append("</b>\n");
            if (!review.isEmpty()) {
                sb.append("\n✅ <b>Tekshirish kerak</b>\n");
                appendList(sb, rows, review, today, true);
            }
            if (!overdue.isEmpty()) {
                sb.append("\n⏰ <b>Muddati o'tgan</b>\n");
                appendList(sb, rows, overdue, today, true);
            }
            if (visible.isEmpty()) {
                sb.append("\nHali topshiriq berilmagan. 🧠 <b>Tahlil</b> → 📌 tugmasi orqali bering.");
            }
        }
        send(chatId, truncate(sb.toString()), rows.isEmpty() ? null : Map.of("inline_keyboard", rows));
    }

    private void appendList(StringBuilder sb, List<List<Map<String, Object>>> rows, List<Task> tasks, LocalDate today,
                            boolean withAssignee) {
        List<Map<String, Object>> row = new ArrayList<>();
        for (Task t : tasks.stream().limit(LIST_LIMIT).toList()) {
            sb.append("• <b>#").append(t.getId()).append("</b> ").append(esc(shorten(t.getTitle(), 90)));
            sb.append("\n   ");
            if (withAssignee) {
                sb.append(esc(TaskNotifier.name(t.getAssignee()))).append(" · ");
            }
            sb.append(esc(t.getHotel().getName()));
            if (t.getDueDate() != null) {
                sb.append(" · ").append(t.getDueDate().format(TaskNotifier.DAY)).append(TaskNotifier.dueNote(t, today));
            }
            sb.append(" · ").append(t.getStatus().getLabel().toLowerCase()).append("\n");
            row.add(button("#" + t.getId(), "k:v:" + t.getId()));
            if (row.size() == 5) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        if (tasks.size() > LIST_LIMIT) {
            sb.append("… yana ").append(tasks.size() - LIST_LIMIT).append(" ta — saytda: Xizmatlar → Topshiriqlar\n");
        }
    }

    // ---------------------------------------------------------------- Tugmalar

    /** @return true — tugma shu handlerga tegishli edi */
    public boolean handleCallback(long chatId, User user, String cbId, String[] parts) {
        CustomUserDetails u = new CustomUserDetails(user);
        try {
            switch (parts[0]) {
                case "an" -> {
                    answer(cbId, null);
                    sendAnalysis(chatId, user, hotelService.getAccessible(u, Long.parseLong(parts[1])));
                }
                case "ta" -> {
                    answer(cbId, null);
                    requireAssigner(u);
                    chooseStaff(chatId, u, parts[1], hotelService.getAccessible(u, Long.parseLong(parts[2])), Integer.parseInt(parts[3]));
                }
                case "tb" -> {
                    answer(cbId, null);
                    requireAssigner(u);
                    hotelService.getAccessible(u, Long.parseLong(parts[2]));
                    chooseDue(chatId, String.join(":", parts[1], parts[2], parts[3], parts[4]));
                }
                case "tc" -> {
                    answer(cbId, "Topshiriq berilmoqda…");
                    requireAssigner(u);
                    createFromAnalysis(chatId, u, parts[1], hotelService.getAccessible(u, Long.parseLong(parts[2])),
                            Integer.parseInt(parts[3]), Long.parseLong(parts[4]), Integer.parseInt(parts[5]));
                }
                case "k" -> {
                    answer(cbId, null);
                    taskButton(chatId, u, parts[1], Long.parseLong(parts[2]));
                }
                default -> {
                    return false;
                }
            }
        } catch (TaskException e) {
            send(chatId, "⚠️ " + esc(e.getMessage()), null);
        } catch (AccessDeniedException e) {
            send(chatId, "⛔ Ruxsat yo'q.", null);
        } catch (NotFoundException e) {
            send(chatId, "Topshiriq topilmadi.", null);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            send(chatId, "Tugma eskirgan — qayta oching.", null);
        }
        return true;
    }

    private void taskButton(long chatId, CustomUserDetails u, String action, long id) {
        switch (action) {
            case TaskNotifier.VIEW -> {
                Task t = taskService.get(u, id);
                Map<String, Object> markup = TaskService.isAssignee(u, t) ? notifier.assigneeButtons(t)
                        : taskService.canReview(u, t) ? notifier.reviewerButtons(t) : null;
                send(chatId, truncate(notifier.card(t) + notifier.itemsBlock(t, taskService.items(u, id))), markup);
            }
            case TaskNotifier.START -> {
                Task t = taskService.start(u, id, TaskEvent.BOT);
                send(chatId, "▶️ <b>Bajarilmoqda</b>\n\n" + notifier.card(t), notifier.assigneeButtons(t));
            }
            case TaskNotifier.COMPLETE -> {
                Task t = taskService.complete(u, id, null, TaskEvent.BOT);
                pending.put(chatId, new Pending("note", id, Instant.now(clock).plus(PENDING_MINUTES, ChronoUnit.MINUTES)));
                send(chatId, "✅ <b>#" + t.getId() + " bajarildi deb belgilandi</b> — topshiriq beruvchi tekshiradi.\n\n"
                        + "Nima qilinganini yozmoqchi bo'lsangiz — shu yerga xabar yozing (ixtiyoriy).", null);
            }
            case TaskNotifier.ACCEPT -> {
                Task t = taskService.accept(u, id, null, TaskEvent.BOT);
                send(chatId, "👍 <b>#" + t.getId() + " tasdiqlandi.</b> Xodimga xabar yuborildi.", null);
            }
            case TaskNotifier.RETURN -> {
                Task t = taskService.get(u, id);
                if (!taskService.canReview(u, t)) {
                    throw new AccessDeniedException("ruxsat yo'q");
                }
                if (t.getStatus() != TaskStatus.REVIEW) {
                    throw new TaskException("Topshiriq holati: " + t.getStatus().getLabel() + " — qaytarib bo'lmaydi.");
                }
                pending.put(chatId, new Pending("return", id, Instant.now(clock).plus(PENDING_MINUTES, ChronoUnit.MINUTES)));
                send(chatId, "↩️ <b>#" + id + "</b> ni qaytarish sababini yozing — xodim nimani tuzatishini bilsin.\n"
                        + "Bekor qilish: /bekor", Map.of("force_reply", true));
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- Kutilayotgan matn

    /** Qaytarish sababi yoki bajarildi izohi kutilayotgan bo'lsa — matnni shunga ishlatadi. @return ishlatildi */
    public boolean handleText(long chatId, User user, String text) {
        Pending p = pending.get(chatId);
        if (p == null) {
            return false;
        }
        if (p.expires().isBefore(Instant.now(clock))) {
            pending.remove(chatId);
            return false;
        }
        pending.remove(chatId);
        if (text.equals("/bekor")) {
            send(chatId, "Bekor qilindi.", null);
            return true;
        }
        CustomUserDetails u = new CustomUserDetails(user);
        try {
            if (p.kind().equals("return")) {
                taskService.returnTask(u, p.taskId(), text, TaskEvent.BOT);
                send(chatId, "↩️ <b>#" + p.taskId() + " qaytarildi.</b> Xodimga sabab bilan xabar yuborildi.", null);
            } else {
                taskService.comment(u, p.taskId(), text, TaskEvent.BOT);
                send(chatId, "💬 Izoh #" + p.taskId() + " ga qo'shildi va topshiriq beruvchiga yuborildi.", null);
            }
        } catch (TaskException e) {
            send(chatId, "⚠️ " + esc(e.getMessage()), null);
        } catch (AccessDeniedException | NotFoundException e) {
            send(chatId, "⛔ Bu topshiriqqa ruxsat yo'q.", null);
        }
        return true;
    }

    /** Boshqa buyruq bosilsa — kutish bekor bo'ladi (sabab tasodifan buyruq bo'lib ketmasin). */
    public void clearPending(long chatId) {
        pending.remove(chatId);
    }

    // ---------------------------------------------------------------- yordamchilar

    private static void requireAssigner(CustomUserDetails u) {
        if (!TaskService.canAssign(u)) {
            throw new AccessDeniedException("topshiriq berishga ruxsat yo'q");
        }
    }

    private static String levelIcon(DebtAnalysis.Level level) {
        return switch (level) {
            case HIGH -> "🔴";
            case MEDIUM -> "🟠";
            case LOW -> "🟢";
        };
    }

    static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String truncate(String html) {
        return html.length() <= MAX_TEXT ? html : html.substring(0, html.lastIndexOf('\n', MAX_TEXT)) + "\n…";
    }

    private static Map<String, Object> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    private void answer(String cbId, String text) {
        gateway.answerCallback(cbId, text);
    }

    private void send(long chatId, String html, Map<String, Object> markup) {
        gateway.sendMessage(chatId, html, markup);
    }
}
