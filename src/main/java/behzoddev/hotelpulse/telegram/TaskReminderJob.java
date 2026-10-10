package behzoddev.hotelpulse.telegram;

import behzoddev.hotelpulse.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Topshiriq muddati eslatmalari: har kuni soat 09:00 da (mehmonxona vaqti) — muddati bugun yoki o'tgan
 * ochiq topshiriqlar bo'yicha xodimga, muddati o'tganlari bo'yicha topshiriq beruvchiga ham.
 * Server o'sha paytda ishlamagan bo'lsa — kun davomida soatlik tekshiruvda yuboriladi (kuniga bir marta).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReminderJob {

    private final TaskService taskService;
    private final TelegramProperties props;

    @Scheduled(cron = "0 0 9-20 * * *", zone = "${app.zone:Asia/Tashkent}")
    public void run() {
        if (!props.enabled()) {
            return;
        }
        int n = taskService.remindDue();
        if (n > 0) {
            log.info("Topshiriq eslatmalari: {} ta topshiriq", n);
        }
    }
}
