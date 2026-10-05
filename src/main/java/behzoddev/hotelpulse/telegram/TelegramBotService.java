package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bot mantig'i: kelgan xabar/tugma bosilishini qayta ishlaydi.
 * Ruxsatlar saytdagi bilan bir xil — HotelService.accessibleHotels / getAccessible orqali.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramBotService {

    static final String BTN_TODAY = "📊 Bugun";
    static final String BTN_WEEK = "📅 7 kun";
    static final String BTN_MONTH = "🗓 Shu oy";
    static final String BTN_DEBT = "⚠️ Qarzlar";
    static final String BTN_HELP = "ℹ️ Yordam";

    /** Bot menyusidagi buyruqlar (setMyCommands). */
    static final List<Map<String, String>> COMMANDS = List.of(
            Map.of("command", "bugun", "description", "Bugungi ko'rsatkichlar"),
            Map.of("command", "hafta", "description", "Oxirgi 7 kun"),
            Map.of("command", "oy", "description", "Shu oy"),
            Map.of("command", "qarzlar", "description", "Qarzdorlik"),
            Map.of("command", "hisobot", "description", "Kunlik hisobotni yoqish/o'chirish"),
            Map.of("command", "uzish", "description", "Telegram'ni hisobdan uzish"),
            Map.of("command", "yordam", "description", "Yordam"));

    private final TelegramGateway gateway;
    private final TelegramLinkService linkService;
    private final TelegramReportService reports;
    private final HotelService hotelService;
    private final TelegramProperties props;

    public void handle(TelegramModels.Update update) {
        try {
            if (update.callbackQuery() != null) {
                handleCallback(update.callbackQuery());
            } else if (update.message() != null && update.message().text() != null) {
                handleMessage(update.message());
            }
        } catch (RuntimeException e) {
            log.error("Telegram update {} ni qayta ishlashda xato", update.updateId(), e);
            Long chatId = chatIdOf(update);
            if (chatId != null) {
                safeSend(chatId, "😕 Xatolik yuz berdi. Birozdan keyin qayta urinib ko'ring.", null);
            }
        }
    }

    // ---------------- Xabarlar ----------------

    private void handleMessage(TelegramModels.Message msg) {
        if (msg.chat() == null || !"private".equals(msg.chat().type())) {
            return; // Guruhlarda ishlamaymiz — hisobotlar shaxsiy.
        }
        long chatId = msg.chat().id();
        String text = msg.text().trim();
        String command = commandOf(text);

        if (command.equals("/start")) {
            String token = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
            handleStart(chatId, token);
            return;
        }

        Optional<User> linked = linkService.findByChat(chatId);
        if (linked.isEmpty()) {
            send(chatId, notLinkedText(), null);
            return;
        }
        User user = linked.get();
        if (!user.isEnabled()) {
            send(chatId, "⛔ Hisobingiz bloklangan. Administrator bilan bog'laning.", null);
            return;
        }

        switch (command) {
            case "/bugun", BTN_TODAY -> askOrReport(chatId, user, "today");
            case "/hafta", BTN_WEEK -> askOrReport(chatId, user, "7d");
            case "/oy", BTN_MONTH -> askOrReport(chatId, user, "month");
            case "/qarzlar", BTN_DEBT -> send(chatId, reports.debtReport(hotels(user)), null);
            case "/hisobot" -> sendDailyToggle(chatId, user);
            case "/uzish" -> {
                linkService.unlinkChat(chatId);
                send(chatId, "🔌 Telegram hisobingizdan uzildi. Qayta ulash uchun saytda <b>Profil → Telegram</b> bo'limiga kiring.",
                        Map.of("remove_keyboard", true));
            }
            default -> send(chatId, helpText(user), mainKeyboard());
        }
    }

    private void handleStart(long chatId, String token) {
        if (!token.isEmpty()) {
            Optional<User> user = linkService.link(token, chatId);
            if (user.isEmpty()) {
                send(chatId, "⚠️ Havola eskirgan yoki noto'g'ri.\n\nSaytda <b>Profil → Telegram'ni ulash</b> tugmasini bosib, yangi havola oling.", null);
                return;
            }
            send(chatId, "✅ <b>Ulandi!</b> Xush kelibsiz, " + TelegramReportService.esc(displayName(user.get())) + ".\n\n"
                    + "Endi mehmonxona ko'rsatkichlarini shu yerda ko'rasiz. Har kuni soat " + props.dailyReportTime() + " da kechagi kun hisoboti keladi "
                    + "(/hisobot bilan o'chirish mumkin).", mainKeyboard());
            return;
        }
        Optional<User> linked = linkService.findByChat(chatId);
        if (linked.isPresent()) {
            send(chatId, "👋 Qaytganingiz bilan, " + TelegramReportService.esc(displayName(linked.get())) + "!", mainKeyboard());
        } else {
            send(chatId, notLinkedText(), null);
        }
    }

    /** Bitta mehmonxona bo'lsa — darhol hisobot, bir nechta bo'lsa — tanlash tugmalari. */
    private void askOrReport(long chatId, User user, String period) {
        List<Hotel> hotels = hotels(user);
        if (hotels.isEmpty()) {
            send(chatId, "Sizga hali mehmonxona biriktirilmagan. Administrator bilan bog'laning.", null);
            return;
        }
        if (hotels.size() == 1) {
            sendHotelReport(chatId, hotels.get(0), period);
            return;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(button("📊 Hammasi", "r:" + period + ":all")));
        for (Hotel h : hotels) {
            rows.add(List.of(button("🏨 " + h.getName(), "r:" + period + ":" + h.getId())));
        }
        send(chatId, "Qaysi mehmonxona bo'yicha?", Map.of("inline_keyboard", rows));
    }

    private void sendHotelReport(long chatId, Hotel hotel, String period) {
        Map<String, Object> markup = null;
        if (props.hasPublicSiteUrl()) {
            String url = props.siteUrl().replaceAll("/+$", "") + "/hotels/" + hotel.getId();
            markup = Map.of("inline_keyboard", List.of(List.of(Map.of("text", "🌐 Saytda batafsil", "url", url))));
        }
        send(chatId, reports.hotelReport(hotel, period), markup);
    }

    private void sendDailyToggle(long chatId, User user) {
        boolean on = user.isTelegramDailyReport();
        String text = "🕘 Kunlik hisobot (har kuni " + props.dailyReportTime() + ", kechagi kun): <b>" + (on ? "yoqilgan" : "o'chirilgan") + "</b>";
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(List.of(
                on ? button("🔕 O'chirish", "d:off") : button("🔔 Yoqish", "d:on"))));
        send(chatId, text, markup);
    }

    // ---------------- Tugmalar (callback) ----------------

    private void handleCallback(TelegramModels.CallbackQuery cb) {
        if (cb.message() == null || cb.message().chat() == null || cb.data() == null) {
            gateway.answerCallback(cb.id(), null);
            return;
        }
        long chatId = cb.message().chat().id();
        Optional<User> linked = linkService.findByChat(chatId);
        if (linked.isEmpty() || !linked.get().isEnabled()) {
            gateway.answerCallback(cb.id(), "Avval hisobingizni ulang");
            return;
        }
        User user = linked.get();
        String[] parts = cb.data().split(":");

        if (parts[0].equals("r") && parts.length == 3) {
            gateway.answerCallback(cb.id(), null);
            String period = parts[1];
            if (parts[2].equals("all")) {
                send(chatId, reports.summaryReport(hotels(user), period), null);
                return;
            }
            Hotel hotel;
            try {
                hotel = hotelService.getAccessible(new CustomUserDetails(user), Long.parseLong(parts[2]));
            } catch (AccessDeniedException | NumberFormatException e) {
                send(chatId, "⛔ Bu mehmonxonaga ruxsat yo'q.", null);
                return;
            }
            sendHotelReport(chatId, hotel, period);
        } else if (parts[0].equals("d") && parts.length == 2) {
            boolean on = linkService.setDailyReport(user.getId(), parts[1].equals("on"));
            gateway.answerCallback(cb.id(), on ? "Kunlik hisobot yoqildi" : "Kunlik hisobot o'chirildi");
            user.setTelegramDailyReport(on);
            sendDailyToggle(chatId, user);
        } else {
            gateway.answerCallback(cb.id(), null);
        }
    }

    // ---------------- Yordamchilar ----------------

    List<Hotel> hotels(User user) {
        return hotelService.accessibleHotels(new CustomUserDetails(user));
    }

    static Map<String, Object> mainKeyboard() {
        return Map.of(
                "keyboard", List.of(
                        List.of(Map.of("text", BTN_TODAY), Map.of("text", BTN_WEEK)),
                        List.of(Map.of("text", BTN_MONTH), Map.of("text", BTN_DEBT)),
                        List.of(Map.of("text", BTN_HELP))),
                "resize_keyboard", true,
                "is_persistent", true);
    }

    private static Map<String, Object> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    /** "/bugun@HotelPulseBot ..." → "/bugun"; oddiy matn o'zgarishsiz. */
    static String commandOf(String text) {
        if (!text.startsWith("/")) {
            return text;
        }
        String first = text.split("\\s+", 2)[0];
        int at = first.indexOf('@');
        return (at > 0 ? first.substring(0, at) : first).toLowerCase();
    }

    private String notLinkedText() {
        return "👋 Salom! Bu — <b>HotelPulse</b> boti: mehmonxonangiz ko'rsatkichlari Telegram'da.\n\n"
                + "Ulash uchun saytga kiring → <b>Profil</b> → <b>Telegram'ni ulash</b> tugmasini bosing.";
    }

    private static String helpText(User user) {
        return "ℹ️ <b>Buyruqlar</b>\n\n"
                + "/bugun — bugungi ko'rsatkichlar\n"
                + "/hafta — oxirgi 7 kun\n"
                + "/oy — shu oy\n"
                + "/qarzlar — qarzdorlik\n"
                + "/hisobot — kunlik hisobot (" + (user.isTelegramDailyReport() ? "yoqilgan" : "o'chirilgan") + ")\n"
                + "/uzish — Telegram'ni hisobdan uzish";
    }

    private static String displayName(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private static Long chatIdOf(TelegramModels.Update u) {
        if (u.message() != null && u.message().chat() != null) {
            return u.message().chat().id();
        }
        if (u.callbackQuery() != null && u.callbackQuery().message() != null && u.callbackQuery().message().chat() != null) {
            return u.callbackQuery().message().chat().id();
        }
        return null;
    }

    private void send(long chatId, String html, Map<String, Object> markup) {
        gateway.sendMessage(chatId, html, markup);
    }

    private void safeSend(long chatId, String html, Map<String, Object> markup) {
        try {
            gateway.sendMessage(chatId, html, markup);
        } catch (RuntimeException ignored) {
            // Xato haqidagi xabarni ham yuborib bo'lmadi — logda bor.
        }
    }
}
