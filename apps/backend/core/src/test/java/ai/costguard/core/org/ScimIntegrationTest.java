package ai.costguard.core.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The SCIM 2.0 server an identity provider provisions employees through (ADR 0043). */
@SpringBootTest
@AutoConfigureMockMvc
class ScimIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    final UUID tenant = UUID.randomUUID();

    RequestPostProcessor as(UUID tenantId, String role) {
        return jwt().jwt(j -> j.claim("tenant_id", tenantId.toString()).claim("email", "u@x.ru")
                .claim("realm_access", Map.of("roles", List.of(role)))).authorities(realmRoles);
    }

    String send(MockHttpServletRequestBuilder request, String body, UUID tenantId) throws Exception {
        String response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(as(tenantId, "owner")))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : JsonPath.read(response, "$.id");
    }

    String token(UUID tenantId, boolean dismissOnDeactivate) throws Exception {
        String response = mvc.perform(post("/api/v1/scim/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Okta\",\"dismissOnDeactivate\":%s}".formatted(dismissOnDeactivate))
                        .with(as(tenantId, "owner")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.token");
    }

    MockHttpServletRequestBuilder scim(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token).accept("application/scim+json");
    }

    String employee(UUID tenantId, String department, String email) throws Exception {
        return send(post("/api/v1/employees"), """
                {"name":"Иванов Иван","email":"%s","departmentId":"%s","position":"Инженер"}"""
                .formatted(email, department), tenantId);
    }

    @Test
    void aProviderWithItsTokenSeesTheEmployeesOfItsCompanyOnly() throws Exception {
        String department = send(post("/api/v1/departments"), "{\"name\":\"ИТ\"}", tenant);
        String email = "ivanov-" + UUID.randomUUID() + "@corp.ru";
        String ivanov = employee(tenant, department, email);
        UUID other = UUID.randomUUID();
        employee(other, send(post("/api/v1/departments"), "{\"name\":\"Чужой\"}", other),
                "stranger-" + UUID.randomUUID() + "@corp.ru");
        String token = token(tenant, false);
        assertThat(token).startsWith("cgscim_");

        // the token is shown once
        mvc.perform(get("/api/v1/scim/tokens").with(as(tenant, "owner"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Okta")).andExpect(jsonPath("$[0].token").doesNotExist());
        mvc.perform(get("/api/v1/scim/tokens").with(as(tenant, "finance"))).andExpect(status().isForbidden());

        mvc.perform(get("/scim/v2/Users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"));
        mvc.perform(scim(get("/scim/v2/Users"), "cgscim_wrong")).andExpect(status().isUnauthorized());

        String list = mvc.perform(scim(get("/scim/v2/Users"), token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(list, "$.totalResults")).isEqualTo(1);
        assertThat(JsonPath.<String>read(list, "$.Resources[0].id")).isEqualTo(ivanov);
        assertThat(JsonPath.<String>read(list, "$.Resources[0].userName")).isEqualTo(email);
        assertThat(JsonPath.<String>read(list,
                "$.Resources[0]['urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'].department"))
                .isEqualTo("ИТ");

        mvc.perform(scim(get("/scim/v2/Users"), token).param("filter", "userName eq \"" + email.toUpperCase() + "\""))
                .andExpect(jsonPath("$.totalResults").value(1));
        mvc.perform(scim(get("/scim/v2/Users"), token).param("filter", "userName eq \"nobody@corp.ru\""))
                .andExpect(jsonPath("$.totalResults").value(0));
        mvc.perform(scim(get("/scim/v2/Users"), token).param("filter", "name.familyName co \"Ив\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.scimType").value("invalidFilter"));
        mvc.perform(scim(get("/scim/v2/Users/" + ivanov), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.meta.location")
                        .value("http://localhost/scim/v2/Users/" + ivanov));
        mvc.perform(scim(get("/scim/v2/Users/" + UUID.randomUUID()), token)).andExpect(status().isNotFound());
        mvc.perform(scim(get("/scim/v2/ServiceProviderConfig"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.patch.supported").value(true));

        // a revoked token stops at once
        String id = JsonPath.read(mvc.perform(get("/api/v1/scim/tokens").with(as(tenant, "owner")))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(delete("/api/v1/scim/tokens/" + id).with(as(tenant, "owner"))).andExpect(status().isNoContent());
        mvc.perform(scim(get("/scim/v2/Users"), token)).andExpect(status().isUnauthorized());
    }
}
