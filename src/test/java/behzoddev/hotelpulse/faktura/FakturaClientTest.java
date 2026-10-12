package behzoddev.hotelpulse.faktura;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FakturaClientTest {

    private static final String API = "https://api.faktura.test";
    private static final String ACCOUNT = "https://account.faktura.test";

    private MockRestServiceServer server;
    private FakturaClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FakturaClient(API, ACCOUNT, builder);
    }

    @Test
    void tokenUsesPasswordGrant() {
        server.expect(requestTo(ACCOUNT + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of("grant_type", "password", "username", "user1",
                        "client_id", "cid")))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":1209599,\"token_type\":\"bearer\"}",
                        MediaType.APPLICATION_JSON));

        FakturaClient.Token t = client.token("user1", "secret-pass", "cid", "csecret");

        assertEquals("tok-1", t.accessToken());
        assertEquals(1209599, t.expiresIn());
        server.verify();
    }

    @Test
    void badCredentialsGiveReadableErrorWithoutSecrets() {
        server.expect(requestTo(ACCOUNT + "/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}"));

        FakturaException e = assertThrows(FakturaException.class,
                () -> client.token("user1", "secret-pass", "cid", "csecret"));

        assertTrue(e.getMessage().contains("нотўғри"));
        assertFalse(e.getMessage().contains("secret-pass"));
        assertFalse(e.getMessage().contains("csecret"));
    }

    @Test
    void documentsSendBearerAndDirection() {
        server.expect(requestTo(API + "/Api/Document/GetDocuments?CompanyInn=301234567&IsInbox=true&Skip=100&Limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"documents\":[{\"UniqueId\":\"a\"},{\"UniqueId\":\"b\"}],\"totalPrice\":10,\"totalCount\":102}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/Api/Document/GetDocuments?CompanyInn=301234567&IsSent=true&Skip=0&Limit=100"))
                .andRespond(withSuccess("{\"Data\":{\"Documents\":[{\"UniqueId\":\"c\"}],\"TotalCount\":1}}",
                        MediaType.APPLICATION_JSON));

        FakturaClient.Page in = client.documents("tok-1", "301234567", true, 100, 100);
        FakturaClient.Page out = client.documents("tok-1", "301234567", false, 0, 100);

        assertEquals(2, in.documents().size());
        assertEquals(102L, in.totalCount());
        assertEquals(1, out.documents().size());
        assertEquals(1L, out.totalCount());
        server.verify();
    }

    @Test
    void typeFilterAndContent() {
        server.expect(requestTo(API + "/Api/Document/GetDocuments?CompanyInn=301234567&IsInbox=true&Skip=0&Limit=100&Types=2"))
                .andRespond(withSuccess("{\"documents\":[{\"uniqueId\":\"u1\"}],\"totalCount\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/Api/Document/GetDocumentsContent?companyInn=301234567&isDeserialized=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(content().json("{\"DocumentUniqueIds\":[\"u1\"]}"))
                .andRespond(withSuccess("{\"Success\":true,\"Documents\":[{\"UniqueId\":\"u1\",\"Content\":\"{\\\"document\\\":{\\\"document_number\\\":\\\"7\\\"}}\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(1, client.documents("tok-1", "301234567", true, 0, 100, 2).documents().size());
        var content = client.content("tok-1", "301234567", java.util.List.of("u1"));
        assertEquals("7", content.get("u1").path("document").path("document_number").asString());
        server.verify();
    }

    @Test
    void expiredTokenIsReported() {
        server.expect(requestTo(API + "/Api/Document/GetDocuments?CompanyInn=301234567&IsInbox=true&Skip=0&Limit=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        FakturaException e = assertThrows(FakturaException.class, () -> client.documents("old", "301234567", true, 0, 1));
        assertTrue(e.getMessage().contains("токен"));
    }
}
