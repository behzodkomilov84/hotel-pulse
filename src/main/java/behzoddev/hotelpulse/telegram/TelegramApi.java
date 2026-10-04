package behzoddev.hotelpulse.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API klienti (https://core.telegram.org/bots/api).
 * Token URL ichida bo'ladi — shuning uchun xato xabarlarida URL logga chiqarilmaydi.
 */
@Slf4j
@Component
public class TelegramApi implements TelegramGateway {

    /** getUpdates long polling kutish vaqti (soniya). */
    static final int POLL_TIMEOUT_SECONDS = 50;

    private final TelegramProperties props;
    private final RestClient rest;
    private volatile String botUsername;

    @Autowired
    public TelegramApi(TelegramProperties props) {
        this(props, defaultBuilder());
    }

    /** Testlar uchun: soxta server ulangan builder bilan. */
    TelegramApi(TelegramProperties props, RestClient.Builder builder) {
        this.props = props;
        this.rest = builder
                .baseUrl(props.apiUrl() + "/bot" + (props.botToken() == null ? "" : props.botToken()))
                .build();
    }

    private static RestClient.Builder defaultBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // Long polling'dan biroz uzunroq.
        factory.setReadTimeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 15));
        return RestClient.builder().requestFactory(factory);
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Bot ma'lumoti (va bot nomini keshlaydi). */
    public TelegramModels.BotInfo getMe() {
        TelegramModels.BotInfo me = call("getMe", Map.of(), new ParameterizedTypeReference<>() {
        });
        botUsername = me.username();
        return me;
    }

    /** Webhook o'rnatilgan bo'lsa, getUpdates ishlamaydi (409) — shuning uchun o'chiriladi. */
    public void deleteWebhook() {
        call("deleteWebhook", Map.of("drop_pending_updates", false), new ParameterizedTypeReference<Boolean>() {
        });
    }

    public void setCommands(List<Map<String, String>> commands) {
        call("setMyCommands", Map.of("commands", commands), new ParameterizedTypeReference<Boolean>() {
        });
    }

    public List<TelegramModels.Update> getUpdates(long offset) {
        return call("getUpdates",
                Map.of("offset", offset, "timeout", POLL_TIMEOUT_SECONDS,
                        "allowed_updates", List.of("message", "callback_query")),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void sendMessage(long chatId, String html, Map<String, Object> replyMarkup) {
        if (!enabled()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        call("sendMessage", body, new ParameterizedTypeReference<Map<String, Object>>() {
        });
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
        if (!enabled()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) {
            body.put("text", text);
        }
        call("answerCallbackQuery", body, new ParameterizedTypeReference<Boolean>() {
        });
    }

    @Override
    public String botUsername() {
        return botUsername;
    }

    private <T> T call(String method, Map<String, ?> body,
                       ParameterizedTypeReference<T> resultType) {
        TelegramModels.Response<T> response;
        try {
            response = rest.post()
                    .uri("/{method}", method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType(resultType));
        } catch (RestClientResponseException e) {
            // URL'da token bor — faqat status va Telegram'ning izohini chiqaramiz.
            throw new TelegramException(method + ": HTTP " + e.getStatusCode().value() + " " + safeBody(e), e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new TelegramException(method + ": " + e.getClass().getSimpleName(), 0);
        }
        if (response == null || !response.ok()) {
            throw new TelegramException(method + ": " + (response == null ? "bo'sh javob" : response.description()),
                    response == null || response.errorCode() == null ? 0 : response.errorCode());
        }
        return response.result();
    }

    @SuppressWarnings("unchecked")
    private static <T> ParameterizedTypeReference<TelegramModels.Response<T>> responseType(ParameterizedTypeReference<T> inner) {
        java.lang.reflect.Type type = org.springframework.core.ResolvableType
                .forClassWithGenerics(TelegramModels.Response.class,
                        org.springframework.core.ResolvableType.forType(inner.getType()))
                .getType();
        return ParameterizedTypeReference.forType(type);
    }

    private static String safeBody(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** Telegram API xatosi; code — HTTP yoki Telegram error_code (409 — boshqa nusxa ishlayapti). */
    public static class TelegramException extends RuntimeException {
        private final int code;

        public TelegramException(String message, int code) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
