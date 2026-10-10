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

    static final String BTN_TODAY = "📊 Бугун";
    static final String BTN_WEEK = "📅 7 кун";
    static final String BTN_MONTH = "🗓 Шу ой";
    static final String BTN_DEBT = "⚠️ Қарзлар";
    static final String BTN_HELP = "ℹ️ Ёрдам";
    static final String BTN_ANALYSIS = "🧠 Таҳлил";
    static final String BTN_TASKS = "📌 Топшириқлар";
    static final String BTN_HIDE = "🔽 Менюни ёпиш";

    /** Buyruq va tugmalar — ular bosilganda kutilayotgan matn (qaytarish sababi, izoh) bekor bo'ladi. */
    static final java.util.Set<String> KNOWN = java.util.Set.of("/bugun", "/hafta", "/oy", "/qarzlar", "/hisobot", "/uzish",
            "/yordam", "/tahlil", "/topshiriqlar", "/menyu", BTN_HIDE, BTN_TODAY, BTN_WEEK, BTN_MONTH, BTN_DEBT, BTN_HELP, BTN_ANALYSIS, BTN_TASKS);

    /** Bot menyusidagi buyruqlar (setMyCommands). */
    static final List<Map<String, String>> COMMANDS = List.of(
            Map.of("command", "bugun", "description", "Бугунги кўрсаткичлар"),
            Map.of("command", "hafta", "description", "Охирги 7 кун"),
            Map.of("command", "oy", "description", "Шу ой"),
            Map.of("command", "qarzlar", "description", "Қарздорлик"),
            Map.of("command", "tahlil", "description", "Қарздорлик таҳлили ва тавсиялар"),
            Map.of("command", "topshiriqlar", "description", "Топшириқлар"),
            Map.of("command", "hisobot", "description", "Кунлик ҳисоботни ёқиш/ўчириш"),
            Map.of("command", "uzish", "description", "Telegram'ни ҳисобдан узиш"),
            Map.of("command", "menyu", "description", "Тугмалар менюсини очиш"),
            Map.of("command", "yordam", "description", "Ёрдам"));

    private final TelegramGateway gateway;
    private final TelegramLinkService linkService;
    private final TelegramReportService reports;
    private final HotelService hotelService;
    private final TelegramProperties props;
    private final TelegramTaskHandler taskHandler;

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
                safeSend(chatId, "😕 Хатолик юз берди. Бироздан кейин қайта уриниб кўринг.", null);
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
            send(chatId, "⛔ Ҳисобингиз блокланган. Администратор билан боғланинг.", null);
            return;
        }
        // Qaytarish sababi yoki "bajarildi" izohi kutilayotgan bo'lsa — oddiy matn shunga ketadi.
        if (KNOWN.contains(command)) {
            taskHandler.clearPending(chatId);
        } else if (taskHandler.handleText(chatId, user, text)) {
            return;
        }

        switch (command) {
            case "/bugun", BTN_TODAY -> askOrReport(chatId, user, "today");
            case "/hafta", BTN_WEEK -> askOrReport(chatId, user, "7d");
            case "/oy", BTN_MONTH -> askOrReport(chatId, user, "month");
            case "/qarzlar", BTN_DEBT -> send(chatId, reports.debtReport(hotels(user)), null);
            case "/tahlil", BTN_ANALYSIS -> taskHandler.askAnalysis(chatId, user, hotels(user));
            case "/topshiriqlar", BTN_TASKS -> taskHandler.sendTasks(chatId, user);
            case "/hisobot" -> sendDailyToggle(chatId, user);
            case "/uzish" -> {
                linkService.unlinkChat(chatId);
                send(chatId, "🔌 Telegram ҳисобингиздан узилди. Қайта улаш учун сайтда <b>Профил → Telegram</b> бўлимига киринг.",
                        Map.of("remove_keyboard", true));
            }
            case "/menyu" -> send(chatId, "⌨️ Меню очилди. Ёпиш учун — <b>" + BTN_HIDE + "</b>.", mainKeyboard());
            case BTN_HIDE -> send(chatId, "Меню ёпилди. Қайта очиш: /menyu буйруғи ёки пастдаги <b>☰ Menu</b> тугмаси.",
                    Map.of("remove_keyboard", true));
            default -> send(chatId, helpText(user), mainKeyboard());
        }
    }

    private void handleStart(long chatId, String token) {
        if (!token.isEmpty()) {
            Optional<User> user = linkService.link(token, chatId);
            if (user.isEmpty()) {
                send(chatId, "⚠️ Ҳавола эскирган ёки нотўғри.\n\nСайтда <b>Профил → Telegram'ни улаш</b> тугмасини босиб, янги ҳавола олинг.", null);
                return;
            }
            send(chatId, "✅ <b>Уланди!</b> Хуш келибсиз, " + TelegramReportService.esc(displayName(user.get())) + ".\n\n"
                    + "Энди меҳмонхона кўрсаткичларини шу ерда кўрасиз. Кечаги кун ҳисоботи " + dailyReportWhen(user.get()) + " келади "
                    + "(/hisobot билан ўчириш мумкин).", mainKeyboard());
            return;
        }
        Optional<User> linked = linkService.findByChat(chatId);
        if (linked.isPresent()) {
            send(chatId, "👋 Қайтганингиз билан, " + TelegramReportService.esc(displayName(linked.get())) + "!", mainKeyboard());
        } else {
            send(chatId, notLinkedText(), null);
        }
    }

    /** Bitta mehmonxona bo'lsa — darhol hisobot, bir nechta bo'lsa — tanlash tugmalari. */
    private void askOrReport(long chatId, User user, String period) {
        List<Hotel> hotels = hotels(user);
        if (hotels.isEmpty()) {
            send(chatId, "Сизга ҳали меҳмонхона бириктирилмаган. Администратор билан боғланинг.", null);
            return;
        }
        if (hotels.size() == 1) {
            sendHotelReport(chatId, hotels.get(0), period);
            return;
        }
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(button("📊 Ҳаммаси", "r:" + period + ":all")));
        for (Hotel h : hotels) {
            rows.add(List.of(button("🏨 " + h.getName(), "r:" + period + ":" + h.getId())));
        }
        send(chatId, "Қайси меҳмонхона бўйича?", Map.of("inline_keyboard", rows));
    }

    private void sendHotelReport(long chatId, Hotel hotel, String period) {
        Map<String, Object> markup = null;
        if (props.hasPublicSiteUrl()) {
            String url = props.siteUrl().replaceAll("/+$", "") + "/hotels/" + hotel.getId();
            markup = Map.of("inline_keyboard", List.of(List.of(Map.of("text", "🌐 Сайтда батафсил", "url", url))));
        }
        send(chatId, reports.hotelReport(hotel, period), markup);
    }

    private void sendDailyToggle(long chatId, User user) {
        boolean on = user.isTelegramDailyReport();
        String text = "🕘 Кунлик ҳисобот — кечаги кун, " + dailyReportWhen(user) + ": <b>" + (on ? "ёқилган" : "ўчирилган") + "</b>";
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(List.of(
                on ? button("🔕 Ўчириш", "d:off") : button("🔔 Ёқиш", "d:on"))));
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
            gateway.answerCallback(cb.id(), "Аввал ҳисобингизни уланг");
            return;
        }
        User user = linked.get();
        String[] parts = cb.data().split(":");
        if (taskHandler.handleCallback(chatId, user, cb.id(), parts)) {
            return;
        }

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
                send(chatId, "⛔ Бу меҳмонхонага рухсат йўқ.", null);
                return;
            }
            sendHotelReport(chatId, hotel, period);
        } else if (parts[0].equals("d") && parts.length == 2) {
            boolean on = linkService.setDailyReport(user.getId(), parts[1].equals("on"));
            gateway.answerCallback(cb.id(), on ? "Кунлик ҳисобот ёқилди" : "Кунлик ҳисобот ўчирилди");
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

    /**
     * Kunlik hisobot qachon kelishi (matnda): hamma mehmonxonada vaqt bir xil bo'lsa — "har kuni soat 05:00 da",
     * aks holda — "har kuni (Karvon — 05:00, ARDA TURAN — 06:30)".
     */
    String dailyReportWhen(User user) {
        return TelegramReportService.esc(dailyReportWhen(hotels(user).stream().filter(Hotel::isActive).toList()));
    }

    /** Oddiy matn (escape qilinmagan) — Telegram'da esc() bilan, saytda Thymeleaf o'zi escape qiladi. */
    public static String dailyReportWhen(List<Hotel> hotels) {
        if (hotels.isEmpty()) {
            return "ҳар куни эрталаб";
        }
        List<java.time.LocalTime> times = hotels.stream().map(Hotel::getDailyReportTime).distinct().toList();
        if (times.size() == 1) {
            return "ҳар куни соат " + time(times.get(0)) + " да";
        }
        return "ҳар куни (" + String.join(", ", hotels.stream()
                .map(h -> h.getName() + " — " + time(h.getDailyReportTime())).toList()) + ")";
    }

    static String time(java.time.LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    static Map<String, Object> mainKeyboard() {
        return Map.of(
                "keyboard", List.of(
                        List.of(Map.of("text", BTN_TODAY), Map.of("text", BTN_WEEK)),
                        List.of(Map.of("text", BTN_MONTH), Map.of("text", BTN_DEBT)),
                        List.of(Map.of("text", BTN_ANALYSIS), Map.of("text", BTN_TASKS)),
                        List.of(Map.of("text", BTN_HELP), Map.of("text", BTN_HIDE))),
                "resize_keyboard", true,
                // false — Telegram'da klaviatura belgisi bilan yig'ish/ochish ham mumkin.
                "is_persistent", false);
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
        return "👋 Салом! Бу — <b>HotelPulse</b> боти: меҳмонхонангиз кўрсаткичлари Telegram'да.\n\n"
                + "Улаш учун сайтга киринг → <b>Профил</b> → <b>Telegram'ни улаш</b> тугмасини босинг.";
    }

    private static String helpText(User user) {
        return "ℹ️ <b>Буйруқлар</b>\n\n"
                + "/bugun — бугунги кўрсаткичлар\n"
                + "/hafta — охирги 7 кун\n"
                + "/oy — шу ой\n"
                + "/qarzlar — қарздорлик\n"
                + "/tahlil — қарздорлик таҳлили ва тавсиялар (📌 — ходимга топшириқ)\n"
                + "/topshiriqlar — топшириқлар: бажариш ва текшириш\n"
                + "/menyu — тугмалар менюсини очиш (ёпиш — «" + BTN_HIDE + "»)\n"
                + "/hisobot — кунлик ҳисобот (" + (user.isTelegramDailyReport() ? "ёқилган" : "ўчирилган") + ")\n"
                + "/uzish — Telegram'ни ҳисобдан узиш";
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
