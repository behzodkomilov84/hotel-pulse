package behzoddev.hotelpulse.exely;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;

/**
 * Exely PMS Universal API klienti. Autentifikatsiya — mehmonxona kaliti
 * "X-API-KEY" sarlavhasida (token almashinuvi yo'q). Kalit hech qachon logga yozilmaydi.
 */
@Component
public class ExelyPmsClient {

    /** Bronlar qidiruvi sanalari formati. */
    static final DateTimeFormatter BOOKING_QUERY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    /** Analitika (to'lovlar) sanalari formati. */
    static final DateTimeFormatter ANALYTICS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** Xizmatlar hisoboti sanalari formati. */
    static final DateTimeFormatter SERVICE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient rest;

    public ExelyPmsClient(ExelyProperties props,
                          @Qualifier("exelyRestClientBuilder") RestClient.Builder builder) {
        this.rest = builder.clone().baseUrl(props.pmsBaseUrl()).build();
    }

    /** Kalitni tekshirish — faqat o'qiydigan yengil so'rov (xonalar ro'yxati). */
    public void testKey(String key) {
        call(() -> rest.get().uri("/rooms")
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().toBodilessEntity());
    }

    /** Mehmonxonadagi xonalar ro'yxati (xonalar sonini aniqlash uchun). */
    public List<ExelyPmsApi.Room> rooms(String key) {
        ExelyPmsApi.Room[] r = call(() -> rest.get().uri("/rooms")
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(ExelyPmsApi.Room[].class));
        return r == null ? List.of() : List.of(r);
    }

    /** [from, to] oralig'ida o'zgargan bronlar raqamlari (state: Active yoki Cancelled; oraliq ≤ 365 kun). */
    public List<String> modifiedBookings(String key, String state, LocalDateTime from, LocalDateTime to) {
        ExelyPmsApi.BookingNumbers r = call(() -> rest.get()
                .uri(b -> b.path("/bookings")
                        .queryParam("state", state)
                        .queryParam("modifiedFrom", from.format(BOOKING_QUERY))
                        .queryParam("modifiedTo", to.format(BOOKING_QUERY))
                        .build())
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(ExelyPmsApi.BookingNumbers.class));
        return r == null || r.bookingNumbers() == null ? List.of() : r.bookingNumbers();
    }

    public ExelyPmsApi.Booking booking(String key, String number) {
        ExelyPmsApi.Booking b = call(() -> rest.get()
                .uri("/bookings/{number}", number)
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(ExelyPmsApi.Booking.class));
        if (b == null || b.number() == null) {
            throw new ExelyException("Exely PMS: " + number + " bron tafsilotlari bo'sh qaytdi");
        }
        return b;
    }

    /** [from, to] kunlaridagi xizmatlar, yashash kuni bo'yicha (oraliq ≤ 31 kun). */
    public ExelyPmsApi.ServicesData services(String key, LocalDate from, LocalDate to) {
        ExelyPmsApi.ServicesResponse r = call(() -> rest.get()
                .uri(b -> b.path("/analytics/services")
                        .queryParam("startDate", from.format(SERVICE_DAY))
                        .queryParam("endDate", to.format(SERVICE_DAY))
                        .queryParam("dateKind", 1)
                        .build())
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(ExelyPmsApi.ServicesResponse.class));
        if (r == null || r.data() == null) {
            return new ExelyPmsApi.ServicesData(List.of(), List.of());
        }
        return new ExelyPmsApi.ServicesData(
                r.data().services() == null ? List.of() : r.data().services(),
                r.data().reservations() == null ? List.of() : r.data().reservations());
    }

    /** [from, to) oralig'idagi to'lovlar (oraliq ≤ 31 kun, kelajak sanalar mumkin emas). */
    public List<ExelyPmsApi.Payment> payments(String key, LocalDateTime from, LocalDateTime to) {
        ExelyPmsApi.PaymentsResponse r = call(() -> rest.get()
                .uri(b -> b.path("/analytics/payments")
                        .queryParam("startDateTime", from.format(ANALYTICS))
                        .queryParam("endDateTime", to.format(ANALYTICS))
                        .queryParam("includeExternalPayments", true)
                        .build())
                .header("X-API-KEY", key).accept(MediaType.APPLICATION_JSON)
                .retrieve().body(ExelyPmsApi.PaymentsResponse.class));
        if (r == null || r.data() == null || r.data().payments() == null) {
            return List.of();
        }
        return r.data().payments();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String msg = switch (code) {
                case 401 -> "Exely PMS: kalit noto'g'ri yoki o'chirilgan (\"Ключ интеграции\"ni tekshiring)";
                case 403 -> "Exely PMS: ruxsat yo'q — Exely PMS obunasi (STANDART yoki yuqori) talab qilinadi";
                case 404 -> "Exely PMS: bron yoki manzil topilmadi";
                case 429 -> "Exely PMS: so'rovlar limiti oshdi, keyinroq davom ettiriladi";
                case 400 -> "Exely PMS so'rovni rad etdi: " + firstError(e);
                default -> "Exely PMS API xatosi: HTTP " + code;
            };
            throw new ExelyException(msg, e, code == 429);
        } catch (ResourceAccessException e) {
            throw new ExelyException("Exely PMS serveriga ulanib bo'lmadi: " + e.getMostSpecificCause().getMessage(), e);
        }
    }

    private static String firstError(RestClientResponseException e) {
        try {
            ExelyPmsApi.ErrorResponse er = e.getResponseBodyAs(ExelyPmsApi.ErrorResponse.class);
            if (er != null && er.errors() != null && !er.errors().isEmpty()) {
                return er.errors().get(0).message();
            }
        } catch (RuntimeException ignored) {
            // javob JSON emas
        }
        String body = e.getResponseBodyAsString();
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}
