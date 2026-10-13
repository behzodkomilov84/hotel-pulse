package behzoddev.hotelpulse.faktura;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.UserRepository;
import behzoddev.hotelpulse.report.UsaliExpenseService;
import behzoddev.hotelpulse.report.UsaliLine;
import behzoddev.hotelpulse.security.CustomUserDetails;
import behzoddev.hotelpulse.service.KpiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Faktura.uz: sinxronlash (API — soxta), USALI moddalari va qoidalar, chiquvchini bronga bog'lash, sahifalar. */
@Tag("db")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/hotel_pulse_test?createDatabaseIfNotExist=true",
        "app.owner.password=test-owner-password",
        "app.exely.scheduler-enabled=false",
        "app.telegram.bot-token="
})
class FakturaDbTest {

    private static final String INN = "309999999";
    private static final String SUPPLIER = "301234567";
    private static final String GUEST_COMPANY = "305555555";

    @MockitoBean private FakturaClient client;
    @MockitoBean private DidoxClient didox;
    @Autowired private FakturaService faktura;
    @Autowired private UsaliExpenseService expenses;
    @Autowired private KpiService kpiService;
    @Autowired private HotelRepository hotelRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;

    private Hotel hotel;
    private CustomUserDetails admin;
    private CustomUserDetails owner;
    private CustomUserDetails staff;
    private MockMvc mvc;
    private LocalDate today;
    private YearMonth lastMonth;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        hotelRepository.deleteAll();
        today = kpiService.today();
        lastMonth = YearMonth.from(today).minusMonths(1);
        Hotel h = new Hotel();
        h.setName("Faktura Hotel");
        h.setRoomsCount(10);
        hotel = hotelRepository.save(h);

        Booking b = new Booking();
        b.setHotelId(hotel.getId());
        b.setOrigin(DataOrigin.EXELY_PMS);
        b.setExternalId("pms:B-9#1");
        b.setSource("Direct");
        b.setStatus(BookingStatus.CHECKED_OUT);
        b.setGuestName("Rahimov Sardor");
        b.setArrivalDate(today.minusDays(6));
        b.setDepartureDate(today.minusDays(3));
        b.setTotalAmount(new BigDecimal("2000000"));
        b.setBalanceDue(new BigDecimal("2000000"));
        b.setBookedAt(LocalDateTime.now().minusDays(20));
        bookingRepository.save(b);
        jdbc.update("insert into exely_raw (hotel_id, kind, external_id, booking_number, payload, fetched_at) values (?, 'invoices', 'B-9', 'B-9', ?, now())",
                hotel.getId(), "[{\"number\":\"B-9-01\",\"roomStayId\":\"1\",\"payer\":{\"name\":\"Silk Road Tours\"},\"items\":[{\"total\":2000000}]}]");
        jdbc.update("insert into exely_raw (hotel_id, kind, external_id, payload, fetched_at) values (?, 'customer', 'C1', ?, now())",
                hotel.getId(), "{\"name\":\"Silk Road Tours\",\"inn\":\"" + GUEST_COMPANY + "\",\"customerKind\":0}");

        admin = new CustomUserDetails(saveUser("fk-admin", Role.OWNER));
        owner = new CustomUserDetails(saveUser("fk-owner", Role.HOTEL_OWNER));
        staff = new CustomUserDetails(saveUser("fk-staff", Role.HOTEL_STAFF));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // Faktura.uz Swagger shakli: ro'yxat (DocumentInfo) + tarkib (GetDocumentsContent, deserializatsiya qilingan).
        String signedDate = lastMonth.atDay(10).toString();
        long createdSec = lastMonth.atDay(10).atStartOfDay(java.time.ZoneId.of("Asia/Tashkent")).toEpochSecond();
        when(client.token(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FakturaClient.Token("tok-1", 1_209_600));
        FakturaClient.Page empty = FakturaClient.parsePage("{\"documents\":[],\"totalCount\":0}");
        when(client.documents(anyString(), anyString(), anyBoolean(), anyInt(), anyInt(), any())).thenReturn(empty);
        when(client.documents(anyString(), anyString(), anyBoolean(), anyInt(), anyInt())).thenReturn(empty);
        when(client.documents(eq("tok-1"), eq(INN), eq(true), eq(0), anyInt(), eq(2))).thenReturn(FakturaClient.parsePage("""
                {"documents":[
                  {"uniqueId":"in-1","title":"Счет-фактура","totalPrice":"1120000","status":24,"createdDateTime":%d,"updatedDateTime":1,
                   "ownerMember":{"inn":"%s","name":"Toshkent Elektr MChJ"},"contractorMember":{"inn":"%s","name":"Hotel"}},
                  {"uniqueId":"in-2","title":"Счет-фактура","totalPrice":"500000","status":22,"createdDateTime":%d,"updatedDateTime":1,
                   "ownerMember":{"inn":"%s","name":"Toshkent Elektr MChJ"},"contractorMember":{"inn":"%s","name":"Hotel"}}
                ],"totalPrice":0,"totalCount":2}""".formatted(createdSec, SUPPLIER, INN, createdSec, SUPPLIER, INN)));
        when(client.documents(eq("tok-1"), eq(INN), eq(false), eq(0), anyInt(), eq(2))).thenReturn(FakturaClient.parsePage("""
                {"documents":[
                  {"uniqueId":"out-1","title":"Счет-фактура","totalPrice":"2000000","status":24,"createdDateTime":%d,"updatedDateTime":1,
                   "ownerMember":{"inn":"%s","name":"Hotel"},"contractorMember":{"inn":"%s","name":"\\"SILK ROAD TOURS\\" MChJ"}}
                ],"totalCount":1}""".formatted(createdSec, INN, GUEST_COMPANY)));
        when(client.content(eq("tok-1"), eq(INN), anyList())).thenReturn(FakturaClient.parseContent("""
                {"Success":true,"Documents":[
                  {"UniqueId":"in-1","Content":"{\\"head\\":{\\"sender\\":{\\"sender_info\\":{\\"INN\\":\\"%s\\",\\"company_name\\":\\"Toshkent Elektr MChJ\\"}}},\\"document\\":{\\"document_number\\":\\"77\\",\\"document_date\\":\\"%s\\",\\"column_summary_values\\":{\\"column_subtotal\\":1000000,\\"column_vat_value\\":120000,\\"column_subtotal_with_taxes\\":1120000}}}"},
                  {"UniqueId":"out-1","Content":{"head":{"receiver":{"receiver_info":{"INN":"%s","company_name":"\\"SILK ROAD TOURS\\" MChJ"}}},
                    "document":{"document_number":"5","document_date":"%s",
                      "column_summary_values":{"column_subtotal":1785714.29,"column_vat_value":214285.71,"column_subtotal_with_taxes":2000000}}}}
                ]}""".formatted(SUPPLIER, signedDate, GUEST_COMPANY, today.minusDays(2))));
    }

    @Test
    void syncAssignAndMatch() throws Exception {
        faktura.saveAccount(hotel.getId(), INN, "hotel-user", "hotel-pass", "cid", "csecret");
        // Shifrlangan holda saqlanadi.
        String storedPass = jdbc.queryForObject("select password from faktura_accounts where hotel_id = ?", String.class, hotel.getId());
        assertNotEquals("hotel-pass", storedPass);

        String message = faktura.syncNow(hotel.getId());
        assertTrue(message.contains("Кирувчи: 2"), message);
        assertTrue(message.contains("1 таси бронга боғланди"), message);
        assertEquals(3, jdbc.queryForObject("select count(*) from faktura_documents where hotel_id = ?", Integer.class, hotel.getId()));
        // Token keyingi sinxronlashda qayta ishlatiladi; takroriy sinxronlash dublikat qilmaydi.
        faktura.syncNow(hotel.getId());
        assertEquals(3, jdbc.queryForObject("select count(*) from faktura_documents where hotel_id = ?", Integer.class, hotel.getId()));

        // Chiquvchi — bronga avtomatik (INN → Exely to'lovchisi, summa, sana).
        Map<String, List<FakturaService.Doc>> official = faktura.outgoingByBooking(hotel.getId());
        assertEquals("5", official.get("B-9").get(0).number());

        // Kiruvchi: modda belgilanmaguncha USALI'ga kirmaydi.
        assertTrue(expenses.sum(hotel.getId(), lastMonth, lastMonth).isEmpty());
        assertEquals(1, faktura.unassigned(hotel.getId(), lastMonth, lastMonth).count());
        long signedId = jdbc.queryForObject("select id from faktura_documents where unique_id = 'in-1'", Long.class);
        faktura.assignLine(hotel.getId(), signedId, UsaliLine.UTILITIES, true);
        // Imzolanmagani qoida bo'yicha moddaga tushadi, lekin xarajatga kirmaydi; summa — QQSsiz.
        assertEquals("RULE", jdbc.queryForObject("select line_source from faktura_documents where unique_id = 'in-2'", String.class));
        Map<UsaliLine, BigDecimal> sum = expenses.sum(hotel.getId(), lastMonth, lastMonth);
        assertEquals(0, new BigDecimal("1000000").compareTo(sum.get(UsaliLine.UTILITIES)));
        assertEquals(1, expenses.monthsWithData(hotel.getId(), lastMonth, lastMonth));
        assertTrue(expenses.month(hotel.getId(), lastMonth).isEmpty(), "qo'lda kiritilganlar alohida");

        // Qo'lda: bog'lanishni olib tashlash — avtomatik qayta bog'lanmaydi.
        long outId = jdbc.queryForObject("select id from faktura_documents where unique_id = 'out-1'", Long.class);
        faktura.linkBooking(hotel.getId(), outId, "");
        faktura.matchOutgoing(hotel.getId());
        assertTrue(faktura.outgoingByBooking(hotel.getId()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> faktura.linkBooking(hotel.getId(), outId, "NO-SUCH"));
        faktura.linkBooking(hotel.getId(), outId, "B-9");
        assertEquals(1, faktura.outgoingByBooking(hotel.getId()).size());

        // Sahifalar.
        String in = html("/faktura?hotel=" + hotel.getId(), owner);
        assertTrue(in.contains("Toshkent Elektr MChJ") && in.contains("Имзоланган"));
        String out = html("/faktura?hotel=" + hotel.getId() + "&dir=OUT", owner);
        assertTrue(out.contains("SILK ROAD TOURS"));
        String invoices = html("/services/invoices?hotel=" + hotel.getId(), owner);
        assertTrue(invoices.contains("Расмий ҳисоб-фактура") && invoices.contains("№ 5"));
        String exp = html("/reports/usali/expenses?hotel=" + hotel.getId() + "&month=" + lastMonth, owner);
        assertTrue(exp.contains("+ ЭСФ: 1"), "Faktura summasi modda yonida");
        String form = html("/admin/integrations/" + hotel.getId(), admin);
        assertTrue(form.contains(INN) && form.contains("Сақланган"));
        assertFalse(form.contains("hotel-pass") || form.contains("csecret"), "maxfiy ma'lumot sahifaga chiqmaydi");

        // Xodimga — yo'q.
        assertEquals(403, mvc.perform(get("/faktura").with(user(staff))).andReturn().getResponse().getStatus());

        // Formadan modda belgilash.
        var res = mvc.perform(post("/faktura/" + signedId + "/line").with(user(owner)).with(csrf())
                .param("hotel", hotel.getId().toString()).param("line", "POM")
                .param("back", "/faktura?hotel=" + hotel.getId())).andReturn().getResponse();
        assertEquals(302, res.getStatus());
        assertEquals("POM", jdbc.queryForObject("select usali_line from faktura_documents where id = ?", String.class, signedId));
    }

    @Test
    void didoxProviderSyncs() throws Exception {
        faktura.saveAccount(hotel.getId(), FakturaAccount.DIDOX, INN, null, "didox-pass", null, null);
        FakturaAccount a = faktura.account(hotel.getId()).orElseThrow();
        assertTrue(a.isDidox() && a.isComplete());
        assertNull(a.getUsername());

        when(didox.login(INN, "didox-pass")).thenReturn("user-tok");
        String date = lastMonth.atDay(5).toString();
        when(didox.documents(eq("user-tok"), eq(true), eq(1), anyInt())).thenReturn(DidoxClient.parsePage("""
                {"data":[{"doc_id":"d-1","doctype":"002","doc_status":3,"name":"9","doc_date":"%s",
                          "partnerTin":"%s","partnerCompany":"Toshkent Elektr","total_delivery_sum":700000,"total_sum":784000}],
                 "meta":{"page":1,"totalPages":1,"total":1}}""".formatted(date, SUPPLIER)));
        when(didox.documents(eq("user-tok"), eq(false), eq(1), anyInt())).thenReturn(DidoxClient.parsePage("""
                {"data":[{"doc_id":"d-2","doctype":"002","doc_status":3,"name":"3","doc_date":"%s",
                          "partnerTin":"%s","partnerCompany":"Silk Road Tours","total_sum":2000000}],
                 "meta":{"page":1,"totalPages":1,"total":1}}""".formatted(today.minusDays(2), GUEST_COMPANY)));

        String message = faktura.syncNow(hotel.getId());
        assertTrue(message.contains("Кирувчи: 1") && message.contains("1 таси бронга боғланди"), message);
        assertEquals(2, jdbc.queryForObject("select count(*) from faktura_documents where hotel_id = ? and unique_id like 'dx:%'",
                Integer.class, hotel.getId()));
        assertTrue(faktura.test(hotel.getId()).startsWith("Didox"));

        long id = jdbc.queryForObject("select id from faktura_documents where unique_id = 'dx:d-1'", Long.class);
        faktura.assignLine(hotel.getId(), id, UsaliLine.UTILITIES, false);
        assertEquals(0, new BigDecimal("700000").compareTo(expenses.sum(hotel.getId(), lastMonth, lastMonth).get(UsaliLine.UTILITIES)));

        String form = html("/admin/integrations/" + hotel.getId(), admin);
        assertTrue(form.contains("Didox · ишлаяпти") || form.contains("Didox"));
        assertFalse(form.contains("didox-pass"));
    }

    @Test
    void blankSecretsKeepSavedOnes() {
        faktura.saveAccount(hotel.getId(), INN, "u", "p1", "cid", "s1");
        faktura.saveAccount(hotel.getId(), "309 999 999", "u", "", "cid", " ");
        FakturaAccount a = faktura.account(hotel.getId()).orElseThrow();
        assertEquals("p1", a.getPassword());
        assertEquals("s1", a.getClientSecret());
        assertThrows(IllegalArgumentException.class, () -> faktura.saveAccount(hotel.getId(), "12345", "u", "", "cid", ""));
    }

    private String html(String url, CustomUserDetails u) throws Exception {
        var res = mvc.perform(get(url).with(user(u))).andReturn().getResponse();
        assertEquals(200, res.getStatus(), url);
        return res.getContentAsString(StandardCharsets.UTF_8);
    }

    private User saveUser(String username, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("{noop}x");
        u.setRole(role);
        u.getHotels().addAll(Set.of(hotel));
        return userRepository.save(u);
    }
}
