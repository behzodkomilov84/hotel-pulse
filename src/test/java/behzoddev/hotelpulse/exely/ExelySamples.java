package behzoddev.hotelpulse.exely;

/** Exely dev-portal hujjatidagi haqiqiy javob namunalari (Read Reservation API v1). */
final class ExelySamples {

    private ExelySamples() {
    }

    static final String TOKEN = """
            {"access_token":"jwt-token-1","expires_in":900,"token_type":"Bearer"}
            """;

    static final String SUMMARIES_PAGE_1 = """
            {
              "continueToken": "TOKEN-1",
              "hasMoreData": true,
              "bookingSummaries": [
                {"number": "20230622-500821-12025196", "propertyId": 500821, "status": "Active",
                 "createdDateTime": "2023-06-21T05:17:16Z", "modifiedDateTime": "2023-06-21T05:17:16Z"}
              ]
            }
            """;

    static final String SUMMARIES_PAGE_2 = """
            {
              "continueToken": "TOKEN-2",
              "hasMoreData": false,
              "bookingSummaries": [
                {"number": "20210325-500360-6987835", "propertyId": 500821, "status": "Cancelled",
                 "createdDateTime": "2021-03-19T15:18:23Z", "modifiedDateTime": "2021-03-19T15:18:53Z"}
              ]
            }
            """;

    /** Faol, ikki xonali bron (2 ta roomStay), oldindan to'lov bilan. */
    static final String BOOKING_ACTIVE = """
            {
              "booking": {
                "propertyId": "500821",
                "number": "20230622-500821-12025196",
                "status": "Active",
                "createdDateTime": "2023-06-21T05:17:16Z",
                "modifiedDateTime": "2023-06-21T05:17:16Z",
                "guaranteeInfo": {"loyalty": null, "totalPrepaid": 300000.0},
                "currencyCode": "UZS",
                "roomStays": [
                  {
                    "stayDates": {"arrivalDateTime": "2023-07-01T14:00", "departureDateTime": "2023-07-04T12:00"},
                    "ratePlans": [{"id": "307225", "name": "Online"}],
                    "roomType": {"id": "340935", "name": "Standard"},
                    "guestCount": {"adultCount": 2, "childAges": [5]},
                    "guests": [],
                    "dailyRates": [],
                    "total": {"priceBeforeTax": 1500000.0, "priceAfterTax": 1650000.0, "taxAmount": 150000.0, "taxes": [], "discounts": []},
                    "services": [],
                    "extraStayCharges": {"earlyArrival": null, "lateDeparture": null}
                  },
                  {
                    "stayDates": {"arrivalDateTime": "2023-07-01T14:00", "departureDateTime": "2023-07-02T12:00"},
                    "guestCount": {"adultCount": 1, "childAges": []},
                    "total": {"priceBeforeTax": 400000.0, "priceAfterTax": null}
                  }
                ],
                "services": [],
                "total": {"priceBeforeTax": 1900000.0, "priceAfterTax": 2050000.0},
                "taxes": [],
                "cancellation": null,
                "source": {"type": "Channel", "code": "PA2"},
                "customer": {"firstName": "Ali", "lastName": "Valiyev"}
              }
            }
            """;

    /** Hujjatdagi namunaning o'zi — bekor qilingan bron. */
    static final String BOOKING_CANCELLED = """
            {
              "booking": {
                "propertyId": "500360",
                "number": "20210325-500360-6987835",
                "status": "Cancelled",
                "createdDateTime": "2021-03-19T15:18:23Z",
                "modifiedDateTime": "2021-03-19T15:18:53Z",
                "guaranteeInfo": {"loyalty": null, "totalPrepaid": 0.0},
                "currencyCode": "EUR",
                "roomStays": [
                  {
                    "stayDates": {"arrivalDateTime": "2021-03-25T14:00", "departureDateTime": "2021-03-26T12:00"},
                    "ratePlans": [{"id": "307225", "name": "Online"}],
                    "roomType": {"id": "340935", "name": "Standard"},
                    "guestCount": {"adultCount": 1, "childAges": []},
                    "guests": [],
                    "dailyRates": [{"ratePlanId": "307225", "priceBeforeTax": 28.000, "date": "2021-03-25T00:00:00"}],
                    "total": {"priceBeforeTax": 28.0000, "priceAfterTax": 28.0000, "taxAmount": 0.0000, "taxes": [], "discounts": []},
                    "services": [],
                    "extraStayCharges": {"earlyArrival": null, "lateDeparture": null}
                  }
                ],
                "services": [],
                "total": {"priceBeforeTax": 28.0000, "priceAfterTax": 28.0000, "taxAmount": 0.0000, "taxes": [], "discounts": []},
                "taxes": [],
                "cancellation": {"penaltyAmount": 0.0, "cancelledDateTime": "2021-03-19T15:18:53Z"},
                "source": {"type": "Channel", "code": "PA2"},
                "customer": null
              }
            }
            """;
}
