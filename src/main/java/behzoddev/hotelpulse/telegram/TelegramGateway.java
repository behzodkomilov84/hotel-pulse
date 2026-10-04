package behzoddev.hotelpulse.telegram;

import java.util.Map;

/**
 * Telegram'ga xabar yuborish. Bot mantig'i shu interfeysga bog'liq —
 * testlarda haqiqiy API o'rniga xotiradagi soxta implementatsiya qo'yiladi.
 */
public interface TelegramGateway {

    /**
     * @param html        Telegram HTML formatidagi matn
     * @param replyMarkup inline yoki oddiy klaviatura (null — yo'q)
     */
    void sendMessage(long chatId, String html, Map<String, Object> replyMarkup);

    void answerCallback(String callbackQueryId, String text);

    /** Bot nomi (@siz), deep-link uchun. Bot sozlanmagan bo'lsa — null. */
    String botUsername();
}
