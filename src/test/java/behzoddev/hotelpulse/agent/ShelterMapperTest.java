package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.entity.BookingStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** UCS Shelter jadvallari → bronlar, kunlik daromad, to'lovlar. */
class ShelterMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private static JsonNode j(String s) {
        return JSON.readTree(s);
    }

    /** Delphi TDateTime: 1899-12-30 dan beri kunlar. */
    private static long dd(LocalDate d) {
        return ChronoUnit.DAYS.between(ShelterMapper.EPOCH, d);
    }

    @Test
    void stayPairsStatusesRevenueAndPayments() {
        long a = dd(LocalDate.of(2026, 8, 1)), b = dd(LocalDate.of(2026, 8, 3));
        List<JsonNode> schedule = List.of(
                // Chiqib ketgan yashash va uning "sarlavha" yozuvi (70 — olinmaydi).
                j("{\"ID\":1,\"RESID\":10,\"STATE\":400,\"ENABLED\":1,\"ARRIVAL\":" + (a + 0.58) + ",\"DEPARTURE\":" + (b + 0.5)
                        + ",\"GUEST\":5,\"Q_GUESTS\":2,\"FOLIO\":100,\"ROOMKIND\":1,\"IDROOM\":7,\"TRAVELAGENT\":3,\"CREATIONDATE\":" + (a - 10.5) + "}"),
                j("{\"ID\":2,\"RESID\":10,\"STATE\":70,\"ENABLED\":1,\"ARRIVAL\":" + a + ",\"DEPARTURE\":" + b + ",\"FOLIO\":100}"),
                // Bekor qilingan va kelmagan bronlar, xonasiz so'rov (51) va o'chirilgan yozuv.
                j("{\"ID\":3,\"RESID\":11,\"STATE\":50,\"QUANTCANCEL\":1,\"ENABLED\":1,\"ARRIVAL\":" + a + ",\"DEPARTURE\":" + b + ",\"UPDATEDATE\":" + (a - 1) + "}"),
                j("{\"ID\":4,\"RESID\":12,\"STATE\":52,\"ENABLED\":1,\"ARRIVAL\":" + a + ",\"DEPARTURE\":" + b + "}"),
                j("{\"ID\":5,\"RESID\":13,\"STATE\":51,\"ENABLED\":1,\"ARRIVAL\":" + a + ",\"DEPARTURE\":" + b + "}"),
                j("{\"ID\":6,\"RESID\":14,\"STATE\":400,\"ENABLED\":0,\"ARRIVAL\":" + a + ",\"DEPARTURE\":" + b + "}"),
                // Kelajakdagi tasdiqlangan bron — narx COST × kechalar.
                j("{\"ID\":7,\"RESID\":15,\"STATE\":50,\"ENABLED\":1,\"ARRIVAL\":" + dd(TODAY.plusDays(5)) + ",\"DEPARTURE\":"
                        + dd(TODAY.plusDays(7)) + ",\"COST\":500000,\"ROOMKIND\":2}"));
        List<JsonNode> ops = List.of(
                j("{\"ID\":70,\"CODE\":\"100\",\"NAME\":\"Проживание\",\"CATEGORY\":101,\"ISSERVICE\":1}"),
                j("{\"ID\":78,\"CODE\":\"102\",\"NAME\":\"Ранний заезд\",\"CATEGORY\":101,\"ISSERVICE\":1}"),
                j("{\"ID\":76,\"CODE\":\"33010\",\"NAME\":\"Обед\",\"CATEGORY\":102,\"ISSERVICE\":1}"),
                j("{\"ID\":82,\"CODE\":\"401\",\"NAME\":\"Tourist Tax\",\"CATEGORY\":1004,\"ISSERVICE\":1}"),
                j("{\"ID\":85,\"CODE\":\"02\",\"NAME\":\"Uzcard\",\"CATEGORY\":902,\"ISSERVICE\":0}"));
        List<JsonNode> fc = List.of(
                j("{\"ID\":1,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":1000000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":2,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":1000000,\"SHIFTDATE\":" + (a + 1) + "}"),
                // Bekor qilingan qator va uning teskarisi — nolga tushadi.
                j("{\"ID\":3,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"CANCELED\":1,\"COST\":9000000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":4,\"FOLIO\":100,\"OPERATION\":70,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":-9000000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":5,\"FOLIO\":100,\"OPERATION\":78,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":200000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":6,\"FOLIO\":100,\"OPERATION\":76,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":150000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":7,\"FOLIO\":100,\"OPERATION\":82,\"ISSERVICE\":1,\"ENABLED\":1,\"COST\":30000,\"SHIFTDATE\":" + a + "}"),
                j("{\"ID\":8,\"FOLIO\":100,\"OPERATION\":85,\"ISSERVICE\":0,\"ENABLED\":1,\"COST\":2000000,\"CREATIONDATE\":" + (b + 0.4) + "}"),
                j("{\"ID\":9,\"FOLIO\":100,\"OPERATION\":1000000101,\"ISSERVICE\":0,\"ENABLED\":1,\"COST\":777,\"CREATIONDATE\":" + b + "}"));
        List<JsonNode> guests = List.of(
                j("{\"ID\":5,\"NAME\":\"Karimov\",\"NAME1\":\"Aziz\"}"),
                j("{\"ID\":900,\"FULLNAME\":\"Silk Road Tours\"}"));
        List<JsonNode> debtors = List.of(j("{\"ID\":3,\"OWNER\":900}"));
        List<JsonNode> rooms = List.of(
                j("{\"ID\":7,\"ROOMKIND\":1,\"ENABLED\":1}"), j("{\"ID\":8,\"ROOMKIND\":1,\"ENABLED\":1}"),
                j("{\"ID\":9,\"ROOMKIND\":11,\"ENABLED\":1}"));   // kabinet — sotilmaydi

        ShelterMapper.Result r = ShelterMapper.map(schedule, fc, ops, guests, debtors, rooms, TODAY);

        assertEquals(4, r.stays().size());
        ShelterMapper.Stay out = r.stays().stream().filter(s -> s.id() == 1).findFirst().orElseThrow();
        assertEquals(BookingStatus.CHECKED_OUT, out.status());
        assertEquals("sh:10#1", out.externalId());
        assertEquals("10", out.bookingNumber());
        assertEquals("Karimov Aziz", out.guestName());
        assertEquals("Silk Road Tours", out.source());
        assertEquals(LocalDate.of(2026, 8, 1), out.arrival());
        assertEquals(LocalDate.of(2026, 8, 3), out.departure());
        assertEquals(0, new BigDecimal("2350000").compareTo(out.total()), "soliq va o'tkazmasiz, bekor qilingan nolga tushgan");
        assertEquals(0, new BigDecimal("350000").compareTo(out.balance()));
        assertEquals(BookingStatus.CANCELLED, status(r, 3));
        assertEquals(BookingStatus.NO_SHOW, status(r, 4));
        ShelterMapper.Stay future = r.stays().stream().filter(s -> s.id() == 7).findFirst().orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, future.status());
        assertEquals(0, new BigDecimal("1000000").compareTo(future.total()));
        assertEquals("Тўғридан-тўғри", future.source());

        // Kunlik xizmatlar: yashash (kind 0), erta kirish (3), ovqat (Meals); soliq olinmaydi.
        assertEquals(6, r.services().size());
        assertTrue(r.services().stream().anyMatch(s -> s.kind() == 3));
        assertTrue(r.services().stream().anyMatch(s -> "Meals".equals(s.category())));
        assertTrue(r.services().stream().allMatch(s -> Long.valueOf(1).equals(s.reservationId())));
        assertEquals(LocalDate.of(2026, 8, 1), r.servicesFrom());
        assertEquals(LocalDate.of(2026, 8, 2), r.servicesUntil());

        assertEquals(1, r.payments().size());
        assertEquals("sh:10#1", r.payments().get(0).bookingExternalId());
        assertEquals("Uzcard", r.payments().get(0).method());
        assertEquals(2, r.roomsCount());
    }

    @Test
    void delphiDates() {
        assertEquals(LocalDate.of(2026, 10, 13), ShelterMapper.date(j("{\"D\":46308.75}"), "D"));
        assertEquals("2026-10-13T18:00", ShelterMapper.dateTime(j("{\"D\":46308.75}"), "D").toString());
        assertNull(ShelterMapper.date(j("{\"D\":0}"), "D"));
    }

    private static BookingStatus status(ShelterMapper.Result r, long id) {
        return r.stays().stream().filter(s -> s.id() == id).findFirst().orElseThrow().status();
    }
}
