package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.controller.Formats;
import behzoddev.hotelpulse.entity.Task;
import behzoddev.hotelpulse.entity.TaskItem;
import behzoddev.hotelpulse.entity.TaskStatus;
import behzoddev.hotelpulse.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static behzoddev.hotelpulse.telegram.TelegramReportService.esc;

/**
 * Topshiriqlar bo'yicha Telegram xabarlari: xodimga — yangi/qaytarilgan topshiriq va eslatma,
 * topshiriq beruvchiga — "bajarildi, tekshiring". Telegram'ni ulamagan foydalanuvchiga hech narsa yuborilmaydi;
 * yuborishdagi xato topshiriq amalini to'xtatmaydi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskNotifier {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Tugmalar: k:{amal}:{topshiriq id}. */
    static final String START = "s", COMPLETE = "c", ACCEPT = "a", RETURN = "r", VIEW = "v";

    private final TelegramGateway gateway;
    private final TelegramProperties props;
    private final Clock clock;
    private final Formats fmt;

    /** Botda ilovadan nechta qator ko'rsatiladi (to'liq ro'yxat — saytda). */
    static final int ITEMS_IN_MESSAGE = 10;

    public void created(Task t, List<TaskItem> items) {
        send(t.getAssignee(), "📌 <b>Сизга янги топшириқ</b>\n\n" + card(t) + itemsBlock(t, items), assigneeButtons(t));
    }

    /** Ilova: jami va eng katta qatorlar (to'liq ro'yxat — saytda). */
    public String itemsBlock(Task t, List<TaskItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        String cur = t.getHotel().getCurrency();
        BigDecimal debt = items.stream().map(TaskItem::getDebt).reduce(BigDecimal.ZERO, BigDecimal::add);
        long done = items.stream().filter(TaskItem::isDone).count();
        StringBuilder sb = new StringBuilder("\n\n📎 <b>Илова: ").append(items.size()).append(" та яшаш</b> · қарз ")
                .append(fmt.moneyShort(debt, cur));
        if (done > 0) {
            sb.append(" · бажарилган ").append(done).append(" / ").append(items.size());
        }
        sb.append("\n");
        for (TaskItem i : items.stream().limit(ITEMS_IN_MESSAGE).toList()) {
            sb.append(i.isDone() ? "☑️ " : "").append(i.getPosition()).append(". <code>").append(esc(i.getBookingNumber())).append("</code> ")
                    .append(esc(i.getGuestName() == null ? "—" : i.getGuestName())).append(" — <b>")
                    .append(fmt.moneyShort(i.getDebt(), cur)).append("</b>");
            if (i.getCategory() != null) {
                sb.append(" · ").append(esc(i.getCategory().toLowerCase()));
            }
            if (i.getAgeDays() > 0) {
                sb.append(", ").append(i.getAgeDays()).append(" кун");
            }
            sb.append("\n");
        }
        if (items.size() > ITEMS_IN_MESSAGE) {
            sb.append("… яна ").append(items.size() - ITEMS_IN_MESSAGE)
                    .append(" та — тўлиқ рўйхат сайтда (топшириқ саҳифаси, Excel).\n");
        }
        return sb.toString();
    }

    public void completed(Task t, String comment) {
        send(t.getAssignedBy(), "✅ <b>Топшириқ бажарилди — текширинг</b>\n\n" + card(t)
                + commentLine(name(t.getAssignee()), comment), reviewerButtons(t));
    }

    public void accepted(Task t, String comment) {
        send(t.getAssignee(), "👍 <b>Топшириқ тасдиқланди</b>\n\n" + card(t)
                + commentLine(name(t.getAssignedBy()), comment), siteButton(t));
    }

    public void returned(Task t, String comment) {
        send(t.getAssignee(), "↩️ <b>Топшириқ қайта бажаришга қайтарилди</b>\n\n" + card(t)
                + commentLine(name(t.getAssignedBy()), comment), assigneeButtons(t));
    }

    public void cancelled(Task t, String comment) {
        send(t.getAssignee(), "🚫 <b>Топшириқ бекор қилинди</b>\n\n" + card(t)
                + commentLine(name(t.getAssignedBy()), comment), null);
    }

    /** Izoh — yozgan odamdan boshqa tomonga. */
    public void commented(Task t, Long authorId, String authorName, String text) {
        User to = t.getAssignee().getId().equals(authorId) ? t.getAssignedBy() : t.getAssignee();
        send(to, "💬 <b>Топшириқ #" + t.getId() + " бўйича изоҳ</b>\n<i>" + esc(t.getTitle()) + "</i>"
                + commentLine(authorName, text), siteButton(t));
    }

    /** Eslatmalar: har bir xodimga bitta xabar (har topshiriq tugmalari bilan), muddati o'tganlari — beruvchiga ham. */
    public void reminders(List<Task> due, LocalDate today) {
        for (Task t : due) {
            String head = t.isOverdue(today)
                    ? "⏰ <b>Топшириқ муддати ўтди</b> (" + ChronoUnit.DAYS.between(t.getDueDate(), today) + " кун)"
                    : "⏳ <b>Бугун — топшириқ муддати</b>";
            send(t.getAssignee(), head + "\n\n" + card(t), assigneeButtons(t));
        }
        Map<Long, List<Task>> byAssigner = new LinkedHashMap<>();
        for (Task t : due) {
            if (t.isOverdue(today) && t.getAssignedBy() != null) {
                byAssigner.computeIfAbsent(t.getAssignedBy().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        byAssigner.values().forEach(list -> {
            StringBuilder sb = new StringBuilder("⏰ <b>Муддати ўтган топшириқлар</b> (сиз берган)\n\n");
            for (Task t : list) {
                sb.append("• #").append(t.getId()).append(" ").append(esc(t.getTitle()))
                        .append("\n   ").append(esc(name(t.getAssignee()))).append(" · ").append(esc(t.getHotel().getName()))
                        .append(" · муддат ").append(t.getDueDate().format(DAY)).append(" · ")
                        .append(t.getStatus().getLabel().toLowerCase()).append("\n");
            }
            send(list.get(0).getAssignedBy(), sb.toString(), null);
        });
    }

    // ---------------------------------------------------------------- matn

    /** Topshiriq kartasi (HTML). */
    public String card(Task t) {
        LocalDate today = LocalDate.now(clock);
        StringBuilder sb = new StringBuilder();
        sb.append("<b>#").append(t.getId()).append(" · ").append(esc(t.getTitle())).append("</b>\n");
        if (t.getDescription() != null) {
            sb.append(esc(t.getDescription())).append("\n");
        }
        sb.append("\n🏨 ").append(esc(t.getHotel().getName()));
        if (t.getDepartment() != null) {
            sb.append(" · ").append(esc(t.getDepartment()));
        }
        if (t.getBookingNumber() != null) {
            sb.append("\n🧾 Брон: <code>").append(esc(t.getBookingNumber())).append("</code>");
        }
        sb.append("\n👤 Бажарувчи: ").append(esc(name(t.getAssignee())));
        if (t.getAssignedBy() != null) {
            sb.append(" · берди: ").append(esc(name(t.getAssignedBy())));
        }
        if (t.getDueDate() != null) {
            sb.append("\n📅 Муддат: <b>").append(t.getDueDate().format(DAY)).append("</b>").append(dueNote(t, today));
        }
        sb.append("\n📍 Ҳолат: ").append(t.getStatus().getLabel());
        return sb.toString();
    }

    static String dueNote(Task t, LocalDate today) {
        if (!t.getStatus().isOpen() || t.getDueDate() == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(today, t.getDueDate());
        if (days < 0) {
            return " — <b>" + (-days) + " кун ўтди</b>";
        }
        return days == 0 ? " — бугун" : " — " + days + " кун қолди";
    }

    private static String commentLine(String author, String comment) {
        return comment == null ? "" : "\n\n💬 <b>" + esc(author) + "</b>: " + esc(comment);
    }

    public static String name(User u) {
        if (u == null) {
            return "—";
        }
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    // ---------------------------------------------------------------- tugmalar

    /** Xodim uchun: holatga mos "Boshladim" / "Bajarildi". */
    Map<String, Object> assigneeButtons(Task t) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        List<Map<String, Object>> row = new ArrayList<>();
        if (t.getStatus() == TaskStatus.NEW || t.getStatus() == TaskStatus.RETURNED) {
            row.add(button("▶️ Бошладим", START, t));
        }
        if (t.getStatus().isOpen()) {
            row.add(button("✅ Бажарилди", COMPLETE, t));
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        addSite(rows, t);
        return rows.isEmpty() ? null : Map.of("inline_keyboard", rows);
    }

    /** Tekshiruvchi uchun: "Tasdiqlash" / "Qaytarish". */
    Map<String, Object> reviewerButtons(Task t) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (t.getStatus() == TaskStatus.REVIEW) {
            rows.add(List.of(button("👍 Тасдиқлаш", ACCEPT, t), button("↩️ Қайтариш", RETURN, t)));
        }
        addSite(rows, t);
        return rows.isEmpty() ? null : Map.of("inline_keyboard", rows);
    }

    private Map<String, Object> siteButton(Task t) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        addSite(rows, t);
        return rows.isEmpty() ? null : Map.of("inline_keyboard", rows);
    }

    private void addSite(List<List<Map<String, Object>>> rows, Task t) {
        if (props.hasPublicSiteUrl()) {
            rows.add(List.of(Map.of("text", "🌐 Сайтда очиш",
                    "url", props.siteUrl().replaceAll("/+$", "") + "/services/tasks/" + t.getId())));
        }
    }

    private static Map<String, Object> button(String text, String action, Task t) {
        return Map.of("text", text, "callback_data", "k:" + action + ":" + t.getId());
    }

    private void send(User to, String html, Map<String, Object> markup) {
        if (to == null || to.getTelegramChatId() == null || !to.isEnabled()) {
            return;
        }
        try {
            gateway.sendMessage(to.getTelegramChatId(), html, markup);
        } catch (RuntimeException e) {
            log.warn("Topshiriq xabari {} ga yuborilmadi: {}", to.getUsername(), e.getMessage());
        }
    }
}
