package behzoddev.hotelpulse.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramApiTest {

    private static final String BASE = "https://tg.test/bot123:ABC";

    private MockRestServiceServer server;
    private TelegramApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new TelegramApi(new TelegramProperties("123:ABC", null, "https://tg.test"), builder);
    }

    @Test
    void getUpdatesParsesMessagesAndCallbacks() {
        server.expect(requestTo(BASE + "/getUpdates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.offset").value(10))
                .andExpect(jsonPath("$.timeout").value(50))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[
                          {"update_id":10,"message":{"message_id":1,"from":{"id":77,"is_bot":false,"first_name":"Ali"},
                            "chat":{"id":77,"type":"private"},"date":1,"text":"/start tok123"}},
                          {"update_id":11,"callback_query":{"id":"cb1","from":{"id":77,"first_name":"Ali"},
                            "message":{"message_id":2,"chat":{"id":77,"type":"private"},"date":1,"text":"x"},"data":"r:today:5"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<TelegramModels.Update> updates = api.getUpdates(10);

        assertEquals(2, updates.size());
        assertEquals("/start tok123", updates.get(0).message().text());
        assertEquals(77, updates.get(0).message().chat().id());
        assertEquals("r:today:5", updates.get(1).callbackQuery().data());
        assertEquals(77, updates.get(1).callbackQuery().message().chat().id());
    }

    @Test
    void sendMessageUsesHtmlAndReplyMarkup() {
        server.expect(requestTo(BASE + "/sendMessage"))
                .andExpect(jsonPath("$.chat_id").value(77))
                .andExpect(jsonPath("$.parse_mode").value("HTML"))
                .andExpect(jsonPath("$.text").value("<b>Salom</b>"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].callback_data").value("d:on"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":5}}", MediaType.APPLICATION_JSON));

        api.sendMessage(77, "<b>Salom</b>",
                Map.of("inline_keyboard", List.of(List.of(Map.of("text", "Yoqish", "callback_data", "d:on")))));
        server.verify();
    }

    @Test
    void getMeCachesUsername() {
        server.expect(requestTo(BASE + "/getMe"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"username\":\"HotelPulseBot\"}}",
                        MediaType.APPLICATION_JSON));
        assertNull(api.botUsername());
        api.getMe();
        assertEquals("HotelPulseBot", api.botUsername());
    }

    @Test
    void conflictIsReportedWithCodeAndWithoutToken() {
        server.expect(requestTo(BASE + "/getUpdates"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: terminated by other getUpdates request\"}"));

        TelegramApi.TelegramException e = assertThrows(TelegramApi.TelegramException.class, () -> api.getUpdates(0));
        assertEquals(409, e.code());
        assertFalse(e.getMessage().contains("123:ABC"), "token xato xabarida bo'lmasligi kerak");
    }

    @Test
    void disabledBotDoesNotSend() {
        TelegramApi disabled = new TelegramApi(new TelegramProperties("", null, "https://tg.test"), RestClient.builder());
        assertFalse(disabled.enabled());
        disabled.sendMessage(1, "x", null); // so'rov yuborilmaydi, xato ham yo'q
    }

    @Test
    void commandParsing() {
        assertEquals("/bugun", TelegramBotService.commandOf("/bugun@HotelPulseBot"));
        assertEquals("/start", TelegramBotService.commandOf("/start abc"));
        assertEquals("/oy", TelegramBotService.commandOf("/OY"));
        assertEquals(TelegramBotService.BTN_TODAY, TelegramBotService.commandOf(TelegramBotService.BTN_TODAY));
    }
}
