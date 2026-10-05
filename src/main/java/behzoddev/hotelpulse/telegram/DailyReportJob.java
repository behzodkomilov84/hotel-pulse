package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Har kuni ertalab (standart 05:00, mehmonxona vaqti — app.telegram.daily-report-cron) kechagi kun hisoboti.
 * 3 tagacha mehmonxona — har biri bo'yicha to'liq hisobot, ko'p bo'lsa — bitta jadval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportJob {

    static final int FULL_REPORT_LIMIT = 3;

    private final UserRepository userRepository;
    private final TelegramBotService bot;
    private final TelegramReportService reports;
    private final TelegramGateway gateway;
    private final TelegramProperties props;

    @Scheduled(cron = "${app.telegram.daily-report-cron:0 0 5 * * *}", zone = "${app.zone:Asia/Tashkent}")
    public void run() {
        if (!props.enabled()) {
            return;
        }
        int sent = 0;
        for (User user : userRepository.findAllByTelegramChatIdIsNotNullAndTelegramDailyReportTrueAndEnabledTrue()) {
            try {
                if (sendTo(user)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // Bitta foydalanuvchi (masalan botni bloklagan) qolganlarga to'sqinlik qilmasin.
                log.warn("Kunlik hisobot {} ga yuborilmadi: {}", user.getUsername(), e.getMessage());
            }
        }
        log.info("Kunlik Telegram hisobot: {} ta foydalanuvchiga yuborildi", sent);
    }

    /** @return yuborildimi (mehmonxonasi yo'q foydalanuvchiga yuborilmaydi) */
    boolean sendTo(User user) {
        List<Hotel> hotels = bot.hotels(user).stream().filter(Hotel::isActive).toList();
        if (hotels.isEmpty()) {
            return false;
        }
        long chatId = user.getTelegramChatId();
        gateway.sendMessage(chatId, "☀️ <b>Xayrli tong!</b> Kechagi kun natijalari:", null);
        if (hotels.size() <= FULL_REPORT_LIMIT) {
            for (Hotel h : hotels) {
                gateway.sendMessage(chatId, reports.hotelReport(h, "yesterday"), null);
            }
        } else {
            gateway.sendMessage(chatId, reports.summaryReport(hotels, "yesterday"), null);
        }
        return true;
    }
}
