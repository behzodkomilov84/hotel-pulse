package behzoddev.hotelpulse.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Telegram Bot API javob modellari (faqat kerakli maydonlar). */
public final class TelegramModels {

    private TelegramModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response<T>(boolean ok, T result, String description,
                              @JsonProperty("error_code") Integer errorCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(@JsonProperty("update_id") long updateId,
                         Message message,
                         @JsonProperty("callback_query") CallbackQuery callbackQuery) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(@JsonProperty("message_id") long messageId, Chat chat, User from, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, @JsonProperty("first_name") String firstName, String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BotInfo(long id, String username) {
    }
}
