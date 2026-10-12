package behzoddev.hotelpulse.faktura;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Faktura.uz API (api.faktura.uz/help): token — account.faktura.uz/token (grant_type=password, ~14 kun),
 * hujjatlar — GET Api/Document/GetDocuments (IsInbox — kiruvchi, IsSent — chiquvchi; Limit/Skip sahifalash).
 * Parol, client secret va token hech qachon logga yoki xato xabariga yozilmaydi.
 */
@Component
public class FakturaClient {

    static final int PAGE = 100;

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestClient api;
    private final RestClient account;

    public FakturaClient(@Value("${app.faktura.api-url:https://api.faktura.uz}") String apiUrl,
                         @Value("${app.faktura.token-url:https://account.faktura.uz}") String tokenUrl,
                         @Qualifier("exelyRestClientBuilder") RestClient.Builder builder) {
        this.api = builder.clone().baseUrl(apiUrl).build();
        this.account = builder.clone().baseUrl(tokenUrl).build();
    }

    /** @param expiresIn soniyalarda */
    public record Token(String accessToken, long expiresIn) {
    }

    /** Bir sahifa hujjatlar: xom JSON'lar va jami soni (bo'lsa). */
    public record Page(List<JsonNode> documents, Long totalCount) {
    }

    public Token token(String username, String password, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        String body = call(() -> account.post().uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                .body(form).retrieve().body(String.class), true);
        JsonNode node = JSON.readTree(body);
        String token = node.path("access_token").asString(null);
        if (token == null || token.isBlank()) {
            throw new FakturaException("Faktura.uz token бермади — логин, парол, Client ID ва Client Secret'ни текширинг");
        }
        return new Token(token, node.path("expires_in").asLong(0));
    }

    /**
     * @param inbox true — kiruvchi (bizga kelgan), false — chiquvchi (biz yuborgan)
     */
    public Page documents(String token, String companyInn, boolean inbox, int skip, int limit) {
        return documents(token, companyInn, inbox, skip, limit, null);
    }

    /**
     * Ro'yxat elementida tur yo'q (Swagger: uniqueId, title, totalPrice, status, created/updatedDateTime,
     * contractor, ownerMember, contractorMember) — shuning uchun tur bo'yicha alohida so'raladi (types).
     */
    public Page documents(String token, String companyInn, boolean inbox, int skip, int limit, Integer type) {
        String body = call(() -> api.get().uri(b -> {
                    b.path("/Api/Document/GetDocuments")
                            .queryParam("CompanyInn", companyInn)
                            .queryParam(inbox ? "IsInbox" : "IsSent", true)
                            .queryParam("Skip", skip)
                            .queryParam("Limit", limit);
                    if (type != null) {
                        b.queryParam("Types", type);
                    }
                    return b.build();
                })
                .header("Authorization", "Bearer " + token).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class), false);
        return parsePage(body);
    }

    /**
     * Hujjatlar tarkibi (POST Api/Document/GetDocumentsContent, isDeserialized=true): uniqueId → hujjat modeli
     * ({head: {sender/receiver}, document: {document_number, document_date, column_summary_values}}).
     */
    public java.util.Map<String, JsonNode> content(String token, String companyInn, List<String> uniqueIds) {
        if (uniqueIds.isEmpty()) {
            return java.util.Map.of();
        }
        String body = call(() -> api.post().uri(b -> b.path("/Api/Document/GetDocumentsContent")
                        .queryParam("companyInn", companyInn)
                        .queryParam("isDeserialized", true)
                        .build())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("DocumentUniqueIds", uniqueIds))
                .retrieve().body(String.class), false);
        return parseContent(body);
    }

    static java.util.Map<String, JsonNode> parseContent(String body) {
        JsonNode root = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        JsonNode docs = FakturaParser.field(root, "Documents", "documents");
        java.util.Map<String, JsonNode> result = new java.util.HashMap<>();
        if (docs != null && docs.isArray()) {
            for (JsonNode d : docs) {
                String id = FakturaParser.text(d, "UniqueId");
                JsonNode c = FakturaParser.field(d, "Content");
                if (id == null || c == null) {
                    continue;
                }
                if (c.isString()) {
                    try {
                        c = JSON.readTree(c.asString());
                    } catch (RuntimeException e) {
                        continue;   // JSON emas (masalan, XML) — tarkibsiz qoladi
                    }
                }
                result.put(id, c);
            }
        }
        return result;
    }

    /** Javob {documents, totalCount} yoki {Data: {...}} ko'rinishida bo'lishi mumkin — ikkalasini ham qabul qiladi. */
    static Page parsePage(String body) {
        JsonNode root = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        JsonNode data = FakturaParser.field(root, "Data", "data");
        JsonNode container = data != null && data.isObject() ? data : root;
        JsonNode docs = container.isArray() ? container : FakturaParser.field(container, "documents", "Documents", "Items", "items");
        List<JsonNode> list = new ArrayList<>();
        if (docs != null && docs.isArray()) {
            docs.forEach(list::add);
        }
        JsonNode total = FakturaParser.field(container, "totalCount", "TotalCount");
        return new Page(list, total != null && total.canConvertToLong() ? total.asLong() : null);
    }

    private static <T> T call(Supplier<T> request, boolean tokenRequest) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String msg = switch (code) {
                case 400 -> tokenRequest ? "Faktura.uz: логин, парол, Client ID ёки Client Secret нотўғри"
                        : "Faktura.uz сўровни рад этди (HTTP 400) — ИНН ва параметрларни текширинг";
                case 401 -> "Faktura.uz: рухсат йўқ (токен эскирган ёки нотўғри)";
                case 403 -> "Faktura.uz: бу ташкилот ҳужжатларига рухсат йўқ (ИНН ва фойдаланувчи ҳуқуқларини текширинг)";
                case 429 -> "Faktura.uz: сўровлар лимити ошди, кейинроқ давом эттирилади";
                default -> "Faktura.uz API хатоси: HTTP " + code;
            };
            throw new FakturaException(msg, e);
        } catch (ResourceAccessException e) {
            throw new FakturaException("Faktura.uz'га уланиб бўлмади (тармоқ ёки вақт тугади)", e);
        }
    }
}
