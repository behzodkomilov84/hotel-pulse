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

    /** GET /analytics/payments */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentsResponse(PaymentsData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentsData(List<Payment> payments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
            Long id,
            String bookingNumber,
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
