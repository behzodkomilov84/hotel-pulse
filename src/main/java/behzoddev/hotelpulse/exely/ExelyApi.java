package behzoddev.hotelpulse.exely;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Exely Connect API javob modellari (faqat bizga kerakli maydonlar).
 * Manba: exely.com/dev-portal — Read Reservation API v1 hujjatidagi namunalar.
 */
public final class ExelyApi {

    private ExelyApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Integer expiresIn) {
    }

    /** GET /api/read-reservation/v1/properties/{propertyId}/bookings */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingSummaries(
            String continueToken,
            boolean hasMoreData,
            List<BookingSummary> bookingSummaries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingSummary(
            String number,
            String status,
            String createdDateTime,
            String modifiedDateTime) {
    }

    /** GET /api/read-reservation/v1/properties/{propertyId}/bookings/{number} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingDetails(Booking booking) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Booking(
            String propertyId,
            String number,
            /** "Active" yoki "Cancelled" */
            String status,
            String createdDateTime,
            String modifiedDateTime,
            GuaranteeInfo guaranteeInfo,
            String currencyCode,
            List<RoomStay> roomStays,
            Total total,
            Cancellation cancellation,
            Source source,
            Customer customer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuaranteeInfo(BigDecimal totalPrepaid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomStay(
            StayDates stayDates,
            GuestCount guestCount,
            Total total) {
    }

    /** Sanalar mehmonxona mahalliy vaqtida, zonasiz: "2021-03-25T14:00". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StayDates(String arrivalDateTime, String departureDateTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuestCount(Integer adultCount, List<Integer> childAges) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Total(BigDecimal priceBeforeTax, BigDecimal priceAfterTax) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancellation(BigDecimal penaltyAmount, String cancelledDateTime) {
    }

    /** type: masalan "Channel" (code — kanal kodi), "BookingEngine" va h.k. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String type, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String firstName, String lastName) {
    }
}
