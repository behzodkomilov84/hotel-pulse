package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Kechagi kun hisoboti — har bir mehmonxona uchun o'z vaqtida (hotels.daily_report_time, mehmonxona vaqti).
 * Har daqiqada tekshiriladi: vaqti kelgan va bugun hali yuborilmagan mehmonxona hisoboti uni ko'ra oladigan,
 * Telegram'ni ulagan va kunlik hisobotni yoqqan foydalanuvchilarga yuboriladi.
 * Server o'sha paytda ishlamagan bo'lsa — keyingi CATCH_UP ichida yuboriladi; undan keyin — ertaga
 * (vaqt o'zgartirilganda yoki yangi mehmonxonada kutilmagan "kechikkan" xabar bo'lmasligi uchun).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportJob {

    static final Duration CATCH_UP = Duration.ofHours(3);

    private final HotelRepository hotelRepository;
    private final UserRepository userRepository;
    private final TelegramBotService bot;
    private final TelegramReportService reports;
    private final TelegramGateway gateway;
    private final TelegramProperties props;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *", zone = "${app.zone:Asia/Tashkent}")
    public void run() {
        if (!props.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (Hotel hotel : hotelRepository.findAll()) {
            if (hotel.isActive() && isDue(hotel, now)) {
                send(hotel, now.toLocalDate());
            }
        }
    }

    /** Vaqti kelgan (CATCH_UP ichida) va bugun hali yuborilmagan. */
    static boolean isDue(Hotel hotel, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (today.equals(hotel.getDailyReportSentOn())) {
            return false;
        }
        LocalDateTime at = today.atTime(hotel.getDailyReportTime());
        return !now.isBefore(at) && now.isBefore(at.plus(CATCH_UP));
    }

    /** @return hisobot yuborilgan foydalanuvchilar soni */
    int send(Hotel hotel, LocalDate today) {
        markSent(hotel.getId(), today);   // avval belgilanadi — xato bo'lsa ham takror yuborilmasin
        int sent = 0;
        String text = null;
        for (User user : recipients()) {
            if (bot.hotels(user).stream().noneMatch(h -> h.getId().equals(hotel.getId()))) {
                continue;
            }
            try {
                if (text == null) {
                    text = "☀️ <b>Xayrli tong!</b> Kechagi kun natijalari:\n\n" + reports.hotelReport(hotel, "yesterday");
                }
                gateway.sendMessage(user.getTelegramChatId(), text, null);
                sent++;
            } catch (RuntimeException e) {
                // Bitta foydalanuvchi (masalan, botni bloklagan) qolganlarga to'sqinlik qilmasin.
                log.warn("Kunlik hisobot ({}) {} ga yuborilmadi: {}", hotel.getName(), user.getUsername(), e.getMessage());
            }
        }
        log.info("Kunlik Telegram hisobot ({}): {} ta foydalanuvchiga yuborildi", hotel.getName(), sent);
        return sent;
    }

    List<User> recipients() {
        return userRepository.findAllByTelegramChatIdIsNotNullAndTelegramDailyReportTrueAndEnabledTrue();
    }

    @Transactional
    void markSent(Long hotelId, LocalDate day) {
        hotelRepository.findById(hotelId).ifPresent(h -> {
            h.setDailyReportSentOn(day);
            hotelRepository.save(h);
        });
    }
}
