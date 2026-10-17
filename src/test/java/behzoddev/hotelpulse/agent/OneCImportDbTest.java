package behzoddev.hotelpulse.agent;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 1С:Отель jadvallari (XDTO JSON) → bronlar, kunlik daromad, to'lovlar, xonalar; 1C ma'lumotlari sahifalari. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class OneCImportDbTest {

    private static final String ACC = "11111111-0000-0000-0000-000000000001";
    private static final String ACC_OLD = "11111111-0000-0000-0000-000000000002";
    private static final String ACC_EXTRA = "11111111-0000-0000-0000-000000000003";
    private static final String RES = "22222222-0000-0000-0000-000000000001";
    private static final String RES_CONVERTED = "22222222-0000-0000-0000-000000000002";
    private static final String ST_IN = "33333333-0000-0000-0000-000000000001";
    private static final String ST_OUT = "33333333-0000-0000-0000-000000000002";
    private static final String RT = "44444444-0000-0000-0000-000000000001";
    private static final String ROOM1 = "55555555-0000-0000-0000-000000000001";
    private static final String ROOM2 = "55555555-0000-0000-0000-000000000002";
    private static final String ROOM_VIRTUAL = "55555555-0000-0000-0000-000000000003";
    private static final String SVC_ROOM = "66666666-0000-0000-0000-000000000001";
    private static final String SVC_BREAKFAST = "66666666-0000-0000-0000-000000000002";
    private static final String CUST = "77777777-0000-0000-0000-000000000001";
    private static final String CASH = "88888888-0000-0000-0000-000000000001";
    private static final String EMPTY = OneCImportService.EMPTY_REF;

    @Autowired private OneCImportService importer;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;

    private Hotel hotel;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from agents");
        jdbc.update("delete from agent_tables");
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        Hotel h = new Hotel();
        h.setName("Florence Test");
        h.setRoomsCount(5);
        hotel = hotelRepository.save(h);
        today = LocalDate.now();
    }

    @Test
    void rebuildsBookingsRevenueAndPayments() throws Exception {
        long id = hotel.getId();
        put("Catalog.AccommodationStatuses", List.of("Ref", "Description", "IsActive", "IsInHouse", "IsCheckIn", "IsCheckOut", "IsRoomChange"),
                List.of(List.of(ref(ST_IN), s("Проживает"), b(true), b(true), b(false), b(false), b(false)),
                        List.of(ref(ST_OUT), s("Выезд"), b(true), b(false), b(false), b(true), b(false))));
        put("Catalog.ReservationStatuses", List.of("Ref", "Description", "IsActive", "IsCheckIn", "IsNoShow", "IsAnnulation", "IsInWaitingList"),
                List.of());
        put("Catalog.RoomTypes", List.of("Ref", "Description"), List.of(List.of(ref(RT), s("Standard"))));
        put("Catalog.Rooms", List.of("Ref", "Description", "DeletionMark", "IsFolder", "IsVirtual", "RoomType", "OperationEndDate"),
                List.of(List.of(ref(ROOM1), s("101"), b(false), b(false), b(false), ref(RT), d("0001-01-01T00:00:00")),
                        List.of(ref(ROOM2), s("102"), b(false), b(false), b(false), ref(RT), d("0001-01-01T00:00:00")),
                        List.of(ref(ROOM_VIRTUAL), s("Виртуальный"), b(false), b(false), b(true), ref(RT), d("0001-01-01T00:00:00"))));
        put("Catalog.Services", List.of("Ref", "Description", "IsRoomRevenue"),
                List.of(List.of(ref(SVC_ROOM), s("Проживание"), b(true)), List.of(ref(SVC_BREAKFAST), s("Завтрак"), b(false))));
        put("Catalog.Customers", List.of("Ref", "Description", "DeletionMark"), List.of(List.of(ref(CUST), s("Booking.com"), b(false))));
        put("Catalog.PaymentMethods", List.of("Ref", "Description", "DeletionMark"), List.of(List.of(ref(CASH), s("Наличные"), b(false))));

        List<String> stayCols = List.of("Ref", "DeletionMark", "Number", "Date", "Posted", "Customer", "Agent", "CheckInDate",
                "CheckOutDate", "Room", "RoomType", "NumberOfPersons", "GuestFullName", "AccommodationStatus", "Reservation", "NumberOfRooms");
        put("Document.Accommodation", stayCols, List.of(
                List.of(ref(ACC), b(false), s("0000000123"), dt(today.minusDays(2)), b(true), ref(EMPTY), ref(CUST),
                        dt(today.minusDays(2)), dt(today.plusDays(1)), ref(ROOM1), ref(RT), n(2), s("Ivanov Ivan"), ref(ST_IN), ref(RES_CONVERTED), n(1)),
                // Qo'shimcha mehmon (o'sha raqam, xona, sanalar; NumberOfRooms = 0) — o'sha bronga qo'shiladi.
                List.of(ref(ACC_EXTRA), b(false), s("0000000123"), dt(today.minusDays(2)), b(true), ref(EMPTY), ref(CUST),
                        dt(today.minusDays(2)), dt(today.plusDays(1)), ref(ROOM1), ref(RT), n(1), s("Ivanova Maria"), ref(ST_IN), ref(EMPTY), n(0)),
                List.of(ref(ACC_OLD), b(false), s("0000000100"), dt(today.minusDays(10)), b(true), ref(EMPTY), ref(EMPTY),
                        dt(today.minusDays(10)), dt(today.minusDays(8)), ref(ROOM2), ref(RT), n(1), s("Petrov"), ref(ST_OUT), ref(EMPTY), n(1))));
        List<String> resCols = List.of("Ref", "DeletionMark", "Number", "Date", "Posted", "Customer", "Agent", "CheckInDate",
                "CheckOutDate", "Room", "RoomType", "NumberOfPersons", "GuestFullName", "ReservationStatus");
        put("Document.Reservation", resCols, List.of(
                List.of(ref(RES), b(false), s("0000000555"), dt(today.minusDays(1)), b(true), ref(EMPTY), ref(EMPTY),
                        dt(today.plusDays(5)), dt(today.plusDays(7)), ref(EMPTY), ref(RT), n(2), s("Future Guest"), ref(EMPTY)),
                List.of(ref(RES_CONVERTED), b(false), s("0000000122"), dt(today.minusDays(5)), b(true), ref(EMPTY), ref(EMPTY),
                        dt(today.minusDays(2)), dt(today.plusDays(1)), ref(ROOM1), ref(RT), n(2), s("Ivanov Ivan"), ref(EMPTY))));

        List<String> salesCols = List.of("Period", "Recorder", "LineNumber", "Active", "Service", "ParentDoc", "Sales", "RoomRevenue");
        List<List<Map<String, Object>>> sales = new ArrayList<>();
        for (int i = 0; i < 3; i++) {   // 3 kecha × 500 000 yashash + nonushta 50 000
            sales.add(List.of(dt(today.minusDays(2 - i)), ref("99999999-0000-0000-0000-00000000000" + i), n(1), b(true),
                    ref(SVC_ROOM), ref(ACC), n(500000), n(500000)));
            sales.add(List.of(dt(today.minusDays(2 - i)), ref("99999999-0000-0000-0000-00000000000" + i), n(2), b(true),
                    ref(SVC_BREAKFAST), ref(ACC), n(50000), n(0)));
        }
        sales.add(List.of(dt(today.minusDays(10)), ref("99999999-0000-0000-0000-000000000009"), n(1), b(true),
                ref(SVC_ROOM), ref(ACC_OLD), n(400000), n(400000)));
        put("AccumulationRegister.Sales", salesCols, sales);
        put("AccumulationRegister.Payments", List.of("Period", "Recorder", "LineNumber", "Active", "PaymentMethod", "ParentDoc", "Sum"),
                List.of(List.of(dt(today.minusDays(2)), ref("aaaaaaaa-0000-0000-0000-000000000001"), n(1), b(true), ref(CASH), ref(ACC), n(1000000)),
                        List.of(dt(today.minusDays(9)), ref("aaaaaaaa-0000-0000-0000-000000000002"), n(1), b(true), ref(CASH), ref(ACC_OLD), n(400000))));

        String message = importer.importNow(id);
        assertTrue(message.startsWith("1С:Отель: 3 та брон/яшаш"), message);

        assertEquals(2, jdbc.queryForObject("select rooms_count from hotels where id = ?", Integer.class, id));
        Map<String, Object> stay = jdbc.queryForMap("select * from bookings where hotel_id = ? and guest_name = 'Ivanov Ivan'", id);
        assertEquals("ONEC", stay.get("origin"));
        assertEquals("CHECKED_IN", stay.get("status"));
        assertEquals("Booking.com", stay.get("source"));
        assertEquals(0, new BigDecimal("1650000").compareTo((BigDecimal) stay.get("total_amount")));
        assertEquals(0, new BigDecimal("650000").compareTo((BigDecimal) stay.get("balance_due")));
        assertEquals(1, stay.get("rooms"));
        assertEquals(3, stay.get("guests"));
        assertEquals(ROOM1.replace("-", ""), stay.get("room_id"));
        assertTrue(((String) stay.get("external_id")).startsWith("oc:123#a"), (String) stay.get("external_id"));

        assertEquals("CHECKED_OUT", jdbc.queryForObject("select status from bookings where hotel_id = ? and guest_name = 'Petrov'", String.class, id));
        assertNull(jdbc.queryForObject("select balance_due from bookings where hotel_id = ? and guest_name = 'Petrov'", BigDecimal.class, id));
        assertEquals("CONFIRMED", jdbc.queryForObject("select status from bookings where hotel_id = ? and guest_name = 'Future Guest'", String.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from bookings where hotel_id = ? and external_id like 'oc:122#%'", Integer.class, id),
                "yashashga aylangan bron alohida sanalmaydi");

        assertEquals(0, new BigDecimal("1900000").compareTo(jdbc.queryForObject(
                "select sum(amount) from service_revenue where hotel_id = ? and kind = 0", BigDecimal.class, id)));
        assertEquals(0, new BigDecimal("150000").compareTo(jdbc.queryForObject(
                "select sum(amount) from service_revenue where hotel_id = ? and kind = 1 and category = 'Meals'", BigDecimal.class, id)));
        assertEquals(2, jdbc.queryForObject("select count(*) from payments where hotel_id = ? and origin = 'ONEC' and booking_id is not null", Integer.class, id));

        // Qayta import — takrorlanmaydi.
        importer.importNow(id);
        assertEquals(3, jdbc.queryForObject("select count(*) from bookings where hotel_id = ?", Integer.class, id));

        // Sahifalar: umumiy "PMS маълумотлари" va 1C jadvali (havola — nomi bilan).
        User u = new User();
        u.setUsername("onec-owner");
        u.setPassword("{noop}x");
        u.setRole(Role.OWNER);
        CustomUserDetails owner = new CustomUserDetails(userRepository.save(u));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String hub = mvc.perform(get("/admin/hotels/" + id + "/data").with(user(owner))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(hub.contains("1С:Отель") && hub.contains("Яшашлар (жойлаштириш)"), hub);
        String table = mvc.perform(get("/admin/hotels/" + id + "/onec/data/Document.Accommodation").with(user(owner)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(table.contains("Ivanov Ivan") && table.contains("Booking.com") && table.contains("101"), table);
        String search = mvc.perform(get("/admin/hotels/" + id + "/onec/data/Document.Accommodation").param("q", "petrov").with(user(owner)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(search.contains("1 та ёзув"), search);
    }

    // ---------------------------------------------------------------- XDTO yordamchilari

    private void put(String table, List<String> columns, List<List<Map<String, Object>>> rows) throws Exception {
        StringBuilder sb = new StringBuilder("{\"#type\":\"jv8:ValueTable\",\"#value\":{\"column\":[");
        for (int i = 0; i < columns.size(); i++) {
            sb.append(i > 0 ? "," : "").append("{\"Name\":{\"#type\":\"jxs:NCName\",\"#value\":\"").append(columns.get(i)).append("\"}}");
        }
        sb.append("],\"row\":[");
        for (int r = 0; r < rows.size(); r++) {
            sb.append(r > 0 ? "," : "").append('[');
            List<Map<String, Object>> row = rows.get(r);
            for (int i = 0; i < row.size(); i++) {
                Map<String, Object> c = row.get(i);
                Object v = c.get("v");
                sb.append(i > 0 ? "," : "").append("{\"#type\":\"").append(c.get("t")).append("\",\"#value\":")
                        .append(v instanceof String str ? "\"" + str + "\"" : String.valueOf(v)).append('}');
            }
            sb.append(']');
        }
        sb.append("]}}\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        jdbc.update("""
                insert into agent_tables (hotel_id, table_name, part, parts, row_count, sync_id, payload, uploaded_at)
                values (?, ?, 0, 1, ?, 't', ?, ?)""", hotel.getId(), table, rows.size(), bytes.toByteArray(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private static Map<String, Object> ref(String v) {
        return Map.of("t", "jcfg:CatalogRef.X", "v", v);
    }

    private static Map<String, Object> s(String v) {
        return Map.of("t", "jxs:string", "v", v);
    }

    private static Map<String, Object> b(boolean v) {
        return Map.of("t", "jxs:boolean", "v", v);
    }

    private static Map<String, Object> n(long v) {
        return Map.of("t", "jxs:decimal", "v", v);
    }

    private static Map<String, Object> d(String v) {
        return Map.of("t", "jxs:dateTime", "v", v);
    }

    private static Map<String, Object> dt(LocalDate day) {
        return d(day + "T00:00:00");
    }
}
