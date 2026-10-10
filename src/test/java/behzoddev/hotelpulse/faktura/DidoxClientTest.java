package behzoddev.hotelpulse.faktura;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DidoxClientTest {

    private static final String BASE = "https://didox.test";

    private MockRestServiceServer server;
    private DidoxClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DidoxClient(BASE, "partner-1", builder);
    }

    @Test
    void loginSendsPartnerTokenAndPassword() {
        server.expect(requestTo(BASE + "/v1/auth/309999999/password/ru"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Partner-Authorization", "partner-1"))
                .andExpect(content().json("{\"password\":\"pw\"}"))
                .andRespond(withSuccess("{\"token\":\"user-tok\",\"related_companies\":null}", MediaType.APPLICATION_JSON));

        assertEquals("user-tok", client.login("309999999", "pw"));
        server.verify();
    }

    @Test
    void wrongPasswordIsReadableWithoutSecrets() {
        server.expect(requestTo(BASE + "/v1/auth/309999999/password/ru"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"error\":\"bad\"}"));

        FakturaException e = assertThrows(FakturaException.class, () -> client.login("309999999", "secret-pw"));
        assertTrue(e.getMessage().contains("парол нотўғри"));
        assertFalse(e.getMessage().contains("secret-pw") || e.getMessage().contains("partner-1"));
    }

    @Test
    void documentsUseOwnerAndUserKey() {
        server.expect(requestTo(BASE + "/v2/documents?owner=0&page=2&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Partner-Authorization", "partner-1"))
                .andExpect(header("user-key", "user-tok"))
                .andRespond(withSuccess("{\"data\":[{\"doc_id\":\"a\"},{\"doc_id\":\"b\"}],\"meta\":{\"page\":2,\"totalPages\":3,\"total\":250}}",
                        MediaType.APPLICATION_JSON));

        FakturaClient.Page p = client.documents("user-tok", true, 2, 100);
        assertEquals(2, p.documents().size());
        assertEquals(250L, p.totalCount());
        server.verify();
    }

    @Test
    void notConfiguredWithoutPartnerToken() {
        DidoxClient off = new DidoxClient(BASE, " ", RestClient.builder());
        assertFalse(off.configured());
        FakturaException e = assertThrows(FakturaException.class, () -> off.login("309999999", "pw"));
        assertTrue(e.getMessage().contains("DIDOX_PARTNER_TOKEN"));
    }

    @Test
    void didoxDocumentIsNormalized() {
        var doc = JsonMapper.builder().build().readTree("""
                {"doc_id":"64f0c1","doctype":"002","doc_status":2,"name":"15","doc_date":"2026-09-30",
                 "partnerTin":"301234567","partnerCompany":"\\"TOZA SUV\\" MChJ",
                 "total_delivery_sum":1000000,"total_vat_sum":120000,"total_sum":1120000}""");
        FakturaParser.Parsed d = FakturaParser.parseDidox(doc, true);
        assertEquals("64f0c1", d.uniqueId());
        assertEquals(2, d.type());
        assertEquals(FakturaService.STATUS_SIGNED, d.status());
        assertEquals("15", d.number());
        assertEquals(LocalDate.of(2026, 9, 30), d.date());
        assertEquals("301234567", d.counterpartyInn());
        assertEquals(0, new BigDecimal("1000000").compareTo(d.net()));

        var act = JsonMapper.builder().build().readTree("{\"doc_id\":\"x\",\"doctype\":\"005\",\"doc_status\":3}");
        FakturaParser.Parsed a = FakturaParser.parseDidox(act, false);
        assertEquals(FakturaParser.DIDOX_TYPE_BASE + 5, a.type());
        assertEquals(23, a.status());
    }
}
