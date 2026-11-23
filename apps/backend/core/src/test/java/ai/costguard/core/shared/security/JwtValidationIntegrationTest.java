package ai.costguard.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Real bearer tokens through the real decoder (ADR 0076), not the test shortcut the other tests use: a key set
 * served over HTTP as Keycloak serves it, and tokens signed with its key or with something else. Only an RS256
 * token of the realm's issuer, for the audience costguard-api, in its lifetime, gets past authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtValidationIntegrationTest extends PostgresTestSupport {

    private static final String ISSUER = "https://keycloak.test/realms/costguard";
    private static final RSAKey KEY = rsa("realm-key");
    private static final RSAKey STRANGER = rsa("realm-key");
    private static final HttpServer KEYS = keys();

    @Autowired MockMvc mvc;

    private final String tenant = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + KEYS.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stop() {
        KEYS.stop(0);
    }

    @Test
    void aTokenForTheCoreIsLetIn() throws Exception {
        int status = call(signed(KEY, claims(c -> { }))).andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void aTokenWithTheCoreAmongSeveralAudiencesIsLetIn() throws Exception {
        int status = call(signed(KEY, claims(c -> c.audience(List.of("account", "costguard-api")))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void aTokenForAnotherClientIsRefused() throws Exception {
        refused(signed(KEY, claims(c -> c.audience("account"))));
    }

    @Test
    void aTokenWithoutAudienceIsRefused() throws Exception {
        refused(signed(KEY, claims(c -> c.audience((String) null))));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        refused(signed(KEY, claims(c -> c.issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))))));
    }

    @Test
    void aTokenNotValidYetIsRefused() throws Exception {
        refused(signed(KEY, claims(c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(3600))))));
    }

    @Test
    void aTokenOfAnotherIssuerIsRefused() throws Exception {
        refused(signed(KEY, claims(c -> c.issuer("https://keycloak.test/realms/other"))));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        refused(signed(STRANGER, claims(c -> { })));
    }

    @Test
    void anUnsignedTokenIsRefused() throws Exception {
        refused(new PlainJWT(claims(c -> { })).serialize());
    }

    @Test
    void aTokenSignedWithThePublicKeyAsAnHmacSecretIsRefused() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY.getKeyID()).build(),
                claims(c -> { }));
        jwt.sign(new MACSigner(KEY.toPublicJWK().toJSONString().repeat(2).getBytes(StandardCharsets.UTF_8)));
        refused(jwt.serialize());
    }

    @Test
    void aMalformedTokenIsRefused() throws Exception {
        refused("not.a.token");
        String good = signed(KEY, claims(c -> { }));
        refused(good.substring(0, good.lastIndexOf('.') + 1) + "AAAA");
    }

    @Test
    void noTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/company")).andExpect(status().isUnauthorized());
    }

    @Test
    void anEmployeeTokenDoesNotOpenCompanyData() throws Exception {
        call(signed(KEY, claims(c -> c.claim("realm_access", Map.of("roles", List.of("employee"))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rolesOutsideTheRealmClaimGiveNothing() throws Exception {
        call(signed(KEY, claims(c -> c.claim("realm_access", Map.of("roles", List.of("employee")))
                .claim("roles", List.of("owner")).claim("scope", "owner admin"))))
                .andExpect(status().isForbidden());
    }

    private ResultActions call(String token) throws Exception {
        return mvc.perform(get("/api/v1/company").header("Authorization", "Bearer " + token));
    }

    private void refused(String token) throws Exception {
        call(token).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
    }

    private JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> change) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("kc-" + UUID.randomUUID())
                .audience("costguard-api")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant_id", tenant)
                .claim("email", "owner@brightline.example")
                .claim("realm_access", Map.of("roles", List.of("owner")));
        change.accept(c);
        return c.build();
    }

    private static String signed(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey rsa(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer keys() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
