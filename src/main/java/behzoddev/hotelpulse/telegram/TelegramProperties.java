package behzoddev.hotelpulse.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram bot sozlamalari (app.telegram.*).
 *
 * @param botToken @BotFather bergan token. Bo'sh bo'lsa — bot o'chirilgan, sayt ishlayveradi.
 * @param siteUrl  saytning ommaviy manzili ("Saytda ochish" tugmasi uchun; localhost bo'lsa tugma chiqmaydi)
 * @param apiUrl   Telegram Bot API manzili (testlarda almashtiriladi)
 */
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(String botToken, String siteUrl, String apiUrl) {

    public TelegramProperties {
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://api.telegram.org";
        }
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
