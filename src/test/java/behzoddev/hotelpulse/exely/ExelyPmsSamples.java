package behzoddev.hotelpulse.exely;

/** Exely PMS Universal API (WebPMS v1.4) hujjatidagi tuzilishga mos namunalar. */
final class ExelyPmsSamples {

    private ExelyPmsSamples() {
    }

    static final String NUMBERS_ACTIVE = """
            {"bookingNumbers": ["20261001-508098-1001", "20261002-508098-1002"]}
            """;

    static final String NUMBERS_CANCELLED = """
            {"bookingNumbers": ["20261003-508098-1003"]}
            """;

    static final String NUMBERS_EMPTY = """
            {"bookingNumbers": []}
            """;

    /** Yashab ketgan, to'liq to'lanmagan (qarz 300 000). */
    static final String BOOKING_1001 = """
            {
              "id": "b1", "number": "20261001-508098-1001", "customerLanguage": "uz",
              "lastModified": "2026-09-30T08:00:00Z", "currencyId": "UZS",
              "customer": {"id": "c1", "lastName": "Karimov", "firstName": "Aziz"},
              "roomStays": [{
                "id": "rs1", "bookingId": "b1", "roomId": "101", "roomTypeId": "std",
                "checkInDateTime": "2026-10-01T14:00", "checkOutDateTime": "2026-10-03T12:00",
                "actualCheckInDateTime": "2026-10-01T15:10", "actualCheckOutDateTime": "2026-10-03T11:40",
                "status": "CheckedOut", "bookingStatus": "Confirmed",
                "guestCountInfo": {"adults": 2, "children": 1}, "guestsIds": ["c1"],
                "totalPrice": {"amount": 1200000.0, "toPayAmount": 300000.0, "toRefundAmount": 0.0},
                "amenities": []
              }],
              "source": {"key": "ota", "value": "OTA"},
              "sourceChannelName": "Booking.com"
            }
            """;

    /** Hozir yashayapti, to'liq to'langan; ikki xona. */
    static final String BOOKING_1002 = """
            {
              "id": "b2", "number": "20261002-508098-1002",
              "lastModified": "2026-10-02T09:30:00Z", "currencyId": "UZS",
              "customer": {"lastName": "Smith", "firstName": "John"},
              "roomStays": [
                {"id": "rs2", "checkInDateTime": "2026-10-03T14:00", "checkOutDateTime": "2026-10-06T12:00",
                 "status": "CheckedIn", "bookingStatus": "Confirmed",
                 "guestCountInfo": {"adults": 1, "children": 0},
                 "totalPrice": {"amount": 900000.0, "toPayAmount": 0.0, "toRefundAmount": 0.0}},
                {"id": "rs3", "checkInDateTime": "2026-10-03T14:00", "checkOutDateTime": "2026-10-06T12:00",
                 "status": "CheckedIn", "bookingStatus": "Confirmed",
                 "guestCountInfo": {"adults": 2, "children": 0},
                 "totalPrice": {"amount": 1500000.0, "toPayAmount": 0.0, "toRefundAmount": 0.0}}
              ],
              "source": {"key": "frontdesk", "value": "Стойка"},
              "sourceChannelName": null
            }
            """;

    static final String BOOKING_1003_CANCELLED = """
            {
              "id": "b3", "number": "20261003-508098-1003",
              "lastModified": "2026-10-02T12:00:00Z", "currencyId": "UZS",
              "roomStays": [{"id": "rs4", "checkInDateTime": "2026-10-10T14:00", "checkOutDateTime": "2026-10-12T12:00",
                "status": "Cancelled", "bookingStatus": "Cancelled",
                "totalPrice": {"amount": 800000.0, "toPayAmount": 0.0, "toRefundAmount": 0.0}}],
              "sourceChannelName": "Сайт"
            }
            """;

    /**
     * To'lovlar (real javob tuzilishi bo'yicha, summa doim musbat):
     * 501, 502 — to'lov (+); 503 — bekor qilingan (cancellationDateTime) va 505 — uning bekor qilish
     * yozuvi (actionKind=2) — ikkalasi ham hisobga olinmaydi; 504 — qaytarish (actionKind=1, −).
     */
    static final String PAYMENTS = """
            {"data": {"payments": [
              {"id": 501, "bookingNumber": "20261001-508098-1001", "actionKind": 0, "kind": 0, "amount": 900000.0,
               "dateTime": "202610011510", "paymentDateTime": "202610011512", "paymentMethod": 0,
               "paymentSystem": null, "currency": "UZS", "cancellationDateTime": null},
              {"id": 502, "bookingNumber": "20261002-508098-1002", "actionKind": 0, "kind": 4, "amount": 2400000.0,
               "dateTime": "202610031420", "paymentDateTime": "202610031421", "paymentMethod": 1,
               "paymentSystem": "Uzcard", "currency": "UZS", "cancellationDateTime": null},
              {"id": 503, "bookingNumber": "20261002-508098-1002", "actionKind": 0, "kind": 0, "amount": 100000.0,
               "dateTime": "202610031430", "paymentDateTime": "202610031430", "paymentMethod": 0,
               "currency": "UZS", "cancellationDateTime": "202610031445"},
              {"id": 504, "bookingNumber": "20261002-508098-1002", "actionKind": 1, "kind": 0, "amount": 150000.0,
               "dateTime": "202610031500", "paymentDateTime": "202610031500", "paymentMethod": 0,
               "currency": "UZS", "cancellationDateTime": null},
              {"id": 505, "bookingNumber": "20261002-508098-1002", "actionKind": 2, "kind": 0, "amount": 100000.0,
               "dateTime": "202610031445", "paymentDateTime": "202610031445", "paymentMethod": 0,
               "currency": "UZS", "cancellationDateTime": null}
            ], "customers": [], "roomTypes": [], "services": [], "agents": [], "reservations": []}}
            """;

    static final String PAYMENTS_EMPTY = """
            {"data": {"payments": []}}
            """;
}
