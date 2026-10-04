package behzoddev.hotelpulse.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * getUpdates long polling — alohida oqimda. Token berilmagan bo'lsa ishga tushmaydi.
 * Muhim: bitta token bilan faqat bitta nusxa so'rov qila oladi (aks holda 409) —
 * lokal va production uchun alohida bot ishlating.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramPoller implements SmartLifecycle {

    private final TelegramApi api;
    private final TelegramBotService bot;

    private volatile boolean running;
    private Thread thread;

    @Override
    public void start() {
        if (!api.enabled()) {
            log.info("Telegram bot o'chirilgan (TELEGRAM_BOT_TOKEN berilmagan)");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "telegram-poller");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        try {
            var me = api.getMe();
            api.deleteWebhook();
            api.setCommands(TelegramBotService.COMMANDS);
            log.info("Telegram bot ishga tushdi: @{}", me.username());
        } catch (RuntimeException e) {
            log.error("Telegram botni ishga tushirib bo'lmadi: {}", e.getMessage());
        }

        long offset = 0;
        long backoffMs = 1000;
        while (running) {
            try {
                List<TelegramModels.Update> updates = api.getUpdates(offset);
                for (TelegramModels.Update u : updates) {
                    offset = Math.max(offset, u.updateId() + 1);
                    bot.handle(u);
                }
                backoffMs = 1000;
            } catch (TelegramApi.TelegramException e) {
                if (!running) {
                    break;
                }
                if (e.code() == 409) {
                    log.warn("Telegram: shu token bilan boshqa nusxa ishlayapti (409) — 30 soniyadan keyin qayta urinish");
                    sleep(30_000);
                } else {
                    log.warn("Telegram getUpdates xatosi: {}", e.getMessage());
                    sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 60_000);
                }
            } catch (RuntimeException e) {
                log.error("Telegram poller kutilmagan xatosi", e);
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 60_000);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
