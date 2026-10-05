package behzoddev.hotelpulse.exely;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Exely PMS Universal API (TravelLine WebPMS v1.4) javob modellari — faqat kerakli maydonlar.
 * Manba: travelline.ru → "Описание универсального API WebPMS (версия 1.4.0)".
 */
public final class ExelyPmsApi {

    private ExelyPmsApi() {
    }

    /** GET /bookings — faqat bron raqamlari. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingNumbers(List<String> bookingNumbers) {
    }

    /** GET /bookings/{number} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Booking(
            String id,
            String number,
            /** "yyyy-MM-ddTHH:mm:ssZ" */
            String lastModified,
            String currencyId,
            Customer customer,
            List<RoomStay> roomStays,
            KeyValue source,
            String sourceChannelName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String lastName, String firstName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyValue(String key, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomStay(
            String id,
            /** "yyyy-MM-ddTHH:mm" (mehmonxona vaqti) */
            String checkInDateTime,
            String checkOutDateTime,
            /** CheckedIn | CheckedOut | Cancelled | New */
            String status,
            /** Confirmed | Cancelled | Pending */
            String bookingStatus,
            GuestCount guestCountInfo,
            TotalPrice totalPrice) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuestCount(Integer adults, Integer children) {
    }

    /** amount — yashash narxi, toPayAmount — to'lanmagan qoldiq, toRefundAmount — qaytariladigan. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TotalPrice(BigDecimal amount, BigDecimal toPayAmount, BigDecimal toRefundAmount) {
    }

    /** GET /rooms — mehmonxonadagi xonalar (massiv). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(String id, String name, String roomTypeId) {
    }

    /** GET /analytics/services (dateKind=1 — yashash kuni bo'yicha). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServicesResponse(ServicesData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServicesData(List<Service> services, List<ServiceReservation> reservations) {
    }

    /**
     * Bitta kunlik xizmat. kind: 0 — yashash, 1 — qo'shimcha xizmat, 2 — transfer, 3 — erta kirish, 4 — kech chiqish.
     * amount — chegirma bilan; date — "yyyyMMdd"; reservationId — yashash (roomStay) identifikatori.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Service(String id, Integer kind, String name, BigDecimal amount, String date,
                          Long reservationId, Boolean isIncluded) {
    }

    /** total — yashashning to'liq narxi; currency/currencyRate — summalar valyutasi va Exely kursi (mehmonxona valyutasiga). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceReservation(Long id, String bookingNumber, BigDecimal total, String currency,
                                     BigDecimal currencyRate) {
    }

    /** GET /analytics/payments */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentsResponse(PaymentsData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentsData(List<Payment> payments) {
    }

    /**
     * actionKind (Exely hujjati): 0 — to'lov, 1 — qaytarish, 2 — to'lovni bekor qilish,
     * 3 — qaytarishni bekor qilish, 4 — oldindan to'lov. Summa doim musbat keladi.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
            Long id,
            String bookingNumber,
            Integer actionKind,
            BigDecimal amount,
            /** "yyyyMMddHHmm" */
            String paymentDateTime,
            String dateTime,
            Integer paymentMethod,
            String paymentSystem,
            String currency,
            /** bekor qilingan to'lov — hisobga olinmaydi */
            String cancellationDateTime) {
    }

    /** 400 javobidagi xatolar ro'yxati. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(List<Error> errors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code, String message) {
    }
}
