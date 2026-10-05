package behzoddev.hotelpulse.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Telegram bot sozlamalari (app.telegram.*).
 *
 * @param botToken @BotFather bergan token. Bo'sh bo'lsa — bot o'chirilgan, sayt ishlayveradi.
 * @param siteUrl  saytning ommaviy manzili ("Saytda ochish" tugmasi uchun; localhost bo'lsa tugma chiqmaydi)
 * @param apiUrl   Telegram Bot API manzili (testlarda almashtiriladi)
 */
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(String botToken, String siteUrl, String apiUrl, String dailyReportCron) {

    /** Kunlik hisobot standart vaqti — 05:00 (mehmonxona vaqti). */
    public static final String DEFAULT_DAILY_REPORT_CRON = "0 0 5 * * *";

    @ConstructorBinding
    public TelegramProperties {
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://api.telegram.org";
        }
        if (dailyReportCron == null || dailyReportCron.isBlank()) {
            dailyReportCron = DEFAULT_DAILY_REPORT_CRON;
        }
    }

    public TelegramProperties(String botToken, String siteUrl, String apiUrl) {
        this(botToken, siteUrl, apiUrl, null);
    }

    /** Kunlik hisobot vaqti matnda ("05:00") — cron'dagi soat va daqiqadan; aniqlab bo'lmasa — "ertalab". */
    public String dailyReportTime() {
        String[] f = dailyReportCron.trim().split("\\s+");
        if (f.length >= 3 && f[1].matches("\\d{1,2}") && f[2].matches("\\d{1,2}")) {
            return String.format("%02d:%02d", Integer.parseInt(f[2]), Integer.parseInt(f[1]));
        }
        return "ertalab";
    }

    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }

    /**
     * "Saytda batafsil" tugmasi faqat ommaviy HTTPS manzil bo'lsa chiqadi:
     * Telegram localhost/IP-HTTP manzilli tugmani rad etsa, butun xabar
     * (hisobot) yuborilmay qoladi — domen + HTTPS bo'lguncha tugmasiz.
     */
    public boolean hasPublicSiteUrl() {
        return siteUrl != null && siteUrl.startsWith("https://") && !siteUrl.contains("localhost");
    }

    @Override
    public String toString() {
        // Token hech qachon logga tushmasin.
        return "TelegramProperties[enabled=" + enabled() + ", siteUrl=" + siteUrl + "]";
    }
}
