package behzoddev.hotelpulse.exely;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Exely Connect API klienti.
 * Avtorizatsiya: OAuth2 client credentials → JWT (15 daqiqa yashaydi, refresh yo'q).
 * Token client_id bo'yicha keshlanadi — auth limitlari qattiq (3/s, 15/daqiqa, 300/soat).
 */
@Slf4j
@Component
public class ExelyClient {

    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofSeconds(60);

    private final RestClient rest;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public ExelyClient(ExelyProperties props,
                       @Qualifier("exelyRestClientBuilder") RestClient.Builder builder,
                       Clock clock) {
        this.rest = builder.baseUrl(props.baseUrl()).build();
        this.clock = clock;
    }

    /** Kirish ma'lumotlarini tekshirish uchun: token olinadimi va mulk (property) ochiladimi. */
    public void testConnection(Credentials c) {
        tokens.remove(c.clientId());
        listBookings(c, Instant.now(clock).minus(Duration.ofDays(1)), null, 1);
    }

    /**
     * Bronlar ro'yxati (qisqa). Birinchi so'rovda lastModification, keyingilarida continueToken.
     */
    public ExelyApi.BookingSummaries listBookings(Credentials c, Instant lastModification, String continueToken, int count) {
        return withToken(c, token -> rest.get()
                .uri(b -> {
                    b.path("/api/read-reservation/v1/properties/{propertyId}/bookings").queryParam("count", count);
                    if (continueToken != null) {
                        b.queryParam("continueToken", continueToken);
                    } else {
                        b.queryParam("lastModification", lastModification.toString());
                    }
                    return b.build(c.propertyId());
                })
                .headers(h -> h.setBearerAuth(token))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ExelyApi.BookingSummaries.class));
    }

    public ExelyApi.Booking getBooking(Credentials c, String number) {
        ExelyApi.BookingDetails details = withToken(c, token -> rest.get()
                .uri("/api/read-reservation/v1/properties/{propertyId}/bookings/{number}", c.propertyId(), number)
                .headers(h -> h.setBearerAuth(token))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ExelyApi.BookingDetails.class));
        if (details == null || details.booking() == null) {
            throw new ExelyException("Exely: " + number + " bron tafsilotlari bo'sh qaytdi");
        }
        return details.booking();
    }

    /** So'rovni token bilan bajaradi; 401 bo'lsa tokenni yangilab bir marta qayta urinadi. */
    private <T> T withToken(Credentials c, Function<String, T> call) {
        try {
            return call.apply(token(c));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                tokens.remove(c.clientId());
                try {
                    return call.apply(token(c));
                } catch (RestClientResponseException retry) {
                    throw translate(retry);
                }
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ExelyException("Exely serveriga ulanib bo'lmadi: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    private String token(Credentials c) {
        CachedToken cached = tokens.get(c.clientId());
        Instant now = Instant.now(clock);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", c.clientId());
        form.add("client_secret", c.clientSecret());
        ExelyApi.TokenResponse response;
        try {
            response = rest.post()
                    .uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(ExelyApi.TokenResponse.class);
        } catch (RestClientResponseException e) {
            HttpStatusCode s = e.getStatusCode();
            if (s.value() == 400 || s.value() == 401) {
                throw new ExelyException("Exely: client_id yoki client_secret noto'g'ri (yoki API ulanish o'chirilgan)", e);
            }
            if (s.value() == 429) {
                throw new ExelyException("Exely: avtorizatsiya so'rovlari limiti oshdi, birozdan keyin qayta urinib ko'ring", e, true);
            }
            throw new ExelyException("Exely avtorizatsiya xatosi: HTTP " + s.value(), e);
        } catch (ResourceAccessException e) {
            throw new ExelyException("Exely serveriga ulanib bo'lmadi: " + e.getMostSpecificCause().getMessage(), e);
        }
        if (response == null || response.accessToken() == null) {
            throw new ExelyException("Exely: token javobi bo'sh");
        }
        long ttl = response.expiresIn() != null ? response.expiresIn() : 900;
        Instant expiresAt = now.plusSeconds(ttl).minus(TOKEN_SAFETY_MARGIN);
        tokens.put(c.clientId(), new CachedToken(response.accessToken(), expiresAt));
        return response.accessToken();
    }

    private static ExelyException translate(RestClientResponseException e) {
        int code = e.getStatusCode().value();
        String msg = switch (code) {
            case 401 -> "Exely: avtorizatsiya rad etildi (client_id/client_secret'ni tekshiring)";
            case 403 -> "Exely: ruxsat yo'q — API ulanishda \"Read Reservation API\" tanlanganini va mehmonxona ID to'g'riligini tekshiring";
            case 404 -> "Exely: mehmonxona (property ID) yoki bron topilmadi";
            case 429 -> "Exely: so'rovlar limiti oshdi, keyinroq davom ettiriladi";
            default -> "Exely API xatosi: HTTP " + code;
        };
        return new ExelyException(msg, e, code == 429);
    }

    /** Bitta mehmonxonaning Exely kirish ma'lumotlari. */
    public record Credentials(String propertyId, String clientId, String clientSecret) {
        @Override
        public String toString() {
            // client_secret hech qachon logga tushmasligi uchun.
            return "Credentials[propertyId=" + propertyId + ", clientId=" + clientId + "]";
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
