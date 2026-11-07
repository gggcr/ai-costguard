package ai.costguard.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class JournalApiIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(UUID tenantId, String... roles) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "u@x.ru")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Autowired Receipts receipts;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final Instant at = Instant.parse("2026-11-07T10:00:00Z");

    private UUID issue(UUID operation) {
        return receipts.issue(tenant, operation, UUID.randomUUID(), new BigDecimal("12.50"), "RUB", at);
    }

    private void sql(String statement) {
        tx.executeWithoutResult(s -> {
            jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenant.toString()).query(String.class).single();
            jdbc.sql(statement).update();
        });
    }

    @Test
    void receiptsChainAndVerify() throws Exception {
        UUID first = UUID.randomUUID();
        issue(first);
        issue(UUID.randomUUID());
        issue(UUID.randomUUID());

        String body = mvc.perform(get("/api/v1/operations/" + first + "/receipt").with(as(tenant, "auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.previousHash")).isEqualTo("0".repeat(64));
        mvc.perform(get("/api/v1/journal/verification").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.receipts").value(3));
    }

    @Test
    void aRepeatedOperationGetsTheSameReceipt() {
        UUID operation = UUID.randomUUID();

        assertThat(issue(operation)).isEqualTo(issue(operation));
    }

    @Test
    void chainsOfTenantsAreSeparate() throws Exception {
        issue(UUID.randomUUID());
        UUID other = UUID.randomUUID();
        receipts.issue(other, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "RUB", at);

        mvc.perform(get("/api/v1/journal/verification").with(as(other, "auditor")))
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.receipts").value(1));
    }

    @Test
    void receiptsCannotBeEditedOrDeleted() {
        issue(UUID.randomUUID());

        assertThatThrownBy(() -> sql("update receipt set payload = 'forged'")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> sql("delete from receipt")).hasMessageContaining("immutable");
    }

    @Test
    void anchoringGivesAVerifiablePath() throws Exception {
        UUID op1 = UUID.randomUUID();
        UUID op2 = UUID.randomUUID();
        UUID op3 = UUID.randomUUID();
        List<UUID> ids = List.of(issue(op1), issue(op2), issue(op3));
        List<String> hashes = tx.execute(s -> {
            jdbc.sql("select set_config('app.tenant_id', ?, true)").param(tenant.toString()).query(String.class).single();
            return ids.stream().map(id -> jdbc.sql("select hash from receipt where id = ?").param(id)
                    .query(String.class).single()).toList();
        });
        String root = ai.costguard.core.journal.internal.MerkleAccess.root(hashes);

        UUID batch = UUID.randomUUID();
        receipts.anchored(tenant, batch, root, ids, "tx-1", at);
        receipts.anchored(tenant, batch, root, ids, "tx-1", at);

        mvc.perform(get("/api/v1/operations/" + op2 + "/receipt").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.status").value("anchored")).andExpect(jsonPath("$.merkleRoot").value(root))
                .andExpect(jsonPath("$.txHash").value("tx-1")).andExpect(jsonPath("$.merklePath", hasSize(2)));
    }

    @Test
    void aWrongRootIsRefused() {
        List<UUID> ids = List.of(issue(UUID.randomUUID()), issue(UUID.randomUUID()));

        assertThatThrownBy(() -> receipts.anchored(tenant, UUID.randomUUID(), "f".repeat(64), ids, "tx", at))
                .hasMessageContaining("Merkle root");
    }

    @Test
    void anotherTenantSeesNoReceipt() throws Exception {
        UUID operation = UUID.randomUUID();
        issue(operation);

        mvc.perform(get("/api/v1/operations/" + operation + "/receipt").with(as(UUID.randomUUID(), "auditor")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("receipt_not_found"));
    }

    @Test
    void thePublicKeyIsPublished() throws Exception {
        mvc.perform(get("/api/v1/journal/signing-key").with(as(tenant, "auditor")))
                .andExpect(jsonPath("$.algorithm").value("Ed25519"));
    }
}
