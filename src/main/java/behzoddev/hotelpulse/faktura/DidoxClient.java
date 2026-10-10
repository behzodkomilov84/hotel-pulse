package behzoddev.hotelpulse.faktura;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Didox hamkor API (api-partners.didox.uz). Rasmiy hujjati ochiq emas — yo'llar Didox SDK'sidan:
 * kirish — POST /v1/auth/{INN}/password/{til} {"password"} → {"token"}; har so'rovda "Partner-Authorization"
 * (HotelPulse'ning hamkor tokeni) va "user-key" (kompaniya tokeni); hujjatlar — GET /v2/documents
 * (owner=0 — kiruvchi, 1 — chiquvchi; page 1 dan, limit ≤ 100) → {data: [...], meta: {page, totalPages, total}}.
 * Parol va tokenlar logga va xato xabariga yozilmaydi.
 */
@Component
public class DidoxClient {

    static final int PAGE = 100;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;
    private final String partnerToken;

    public DidoxClient(@Value("${app.didox.base-url:https://api-partners.didox.uz}") String baseUrl,
                       @Value("${app.didox.partner-token:}") String partnerToken,
                       @Qualifier("exelyRestClientBuilder") RestClient.Builder builder) {
        this.http = builder.clone().baseUrl(baseUrl).build();
        this.partnerToken = partnerToken == null ? "" : partnerToken.trim();
    }

    /** Hamkor tokeni sozlanganmi (aks holda Didox'ga ulanib bo'lmaydi). */
    public boolean configured() {
        return !partnerToken.isEmpty();
    }

    public String login(String inn, String password) {
        requireConfigured();
        String body = call(() -> http.post().uri("/v1/auth/{tin}/password/ru", inn)
                .header("Partner-Authorization", partnerToken)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(Map.of("password", password)).retrieve().body(String.class), true);
        JsonNode node = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        JsonNode token = FakturaParser.field(node, "token", "data.token");
        if (token == null || token.asString().isBlank()) {
            throw new FakturaException("Didox токен бермади — ИНН ва Didox паролини текширинг");
        }
        return token.asString();
    }

    /** @param inbox true — kiruvchi (owner=0), false — chiquvchi (owner=1); page — 1 dan */
    public FakturaClient.Page documents(String token, boolean inbox, int page, int limit) {
        requireConfigured();
        String body = call(() -> http.get().uri(b -> b.path("/v2/documents")
                        .queryParam("owner", inbox ? 0 : 1)
                        .queryParam("page", page)
                        .queryParam("limit", limit)
                        .build())
                .header("Partner-Authorization", partnerToken)
                .header("user-key", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class), false);
        return parsePage(body);
    }

    static FakturaClient.Page parsePage(String body) {
        JsonNode root = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        JsonNode docs = root.isArray() ? root : FakturaParser.field(root, "data", "documents", "items");
        List<JsonNode> list = new ArrayList<>();
        if (docs != null && docs.isArray()) {
            docs.forEach(list::add);
        }
        JsonNode total = FakturaParser.field(root, "meta.total", "total", "totalCount");
        return new FakturaClient.Page(list, total != null && total.canConvertToLong() ? total.asLong() : null);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new FakturaException("Didox ҳамкор токени созланмаган (DIDOX_PARTNER_TOKEN) — Didox билан шартнома керак");
        }
    }

    private static <T> T call(Supplier<T> request, boolean login) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String msg = switch (code) {
                case 401, 403 -> login ? "Didox: ҳамкор токени ёки рухсат нотўғри"
                        : "Didox: рухсат йўқ (токен эскирган ёки ҳамкор рухсати йўқ)";
                case 422 -> "Didox: ИНН ёки парол нотўғри";
                case 429 -> "Didox: сўровлар лимити ошди, кейинроқ давом эттирилади";
                default -> "Didox API хатоси: HTTP " + code;
            };
            throw new FakturaException(msg, e);
        } catch (ResourceAccessException e) {
            throw new FakturaException("Didox'га уланиб бўлмади (тармоқ ёки вақт тугади)", e);
        }
    }
}
