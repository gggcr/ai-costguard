package ai.costguard.core.org.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Finding H1 of the audit of 2026-11-12: an it_admin, who may edit employees and add users but must never see pay
 * (ADR 0054), takes the cabinet of an employee who has no login yet.
 *
 * <ul>
 *   <li>by the address: the it_admin puts their own address on employee V, and their next sign-in linked their
 *       account to V by the stored addresses (FirstLogin);</li>
 *   <li>by a hand-made account: the it_admin onboards someone (a Keycloak account whose password they are shown),
 *       removes the CostGuard account of it (the Keycloak account stays), adds a user with that address and V as its
 *       employee, and signs in as that Keycloak account.</li>
 * </ul>
 *
 * <p>Each attack test asserts the secure behaviour and that V's pay, documents and signature stay out of reach. All
 * data is fictitious.
 */
@SpringBootTest(properties = "costguard.connections.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Import(RegistrationIntegrationTest.Fakes.class)
class EmployeeLinkHijackIntegrationTest extends PostgresTestSupport {

    static final FakeIdentityProvider ACCOUNTS = RegistrationIntegrationTest.ACCOUNTS;
    private static final String PAY_V = "55555.55";

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String ownerMail = "owner-" + run + "@hijack.example";
    private final String itMail = "it-" + run + "@hijack.example";
    private final String vMail = "v-" + run + "@hijack.example";
    private String department;
    private String employeeV;
    private String documentV;
    private final JdbcClient su = JdbcClient.create(superuserAppDataSource());

    private RequestPostProcessor token(String sub, String email, boolean verified, String... roles) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenant.toString()).claim("email", email)
                        .claim("email_verified", verified).claim("name", email)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    private RequestPostProcessor owner() {
        return token(subject(tenant, ownerMail), ownerMail, false, "owner");
    }

    private RequestPostProcessor finance() {
        return token("kc-fin-" + run, "fin-" + run + "@hijack.example", false, "finance");
    }

    /** The it_admin at work: requests that do not pass the first sign-in (the token was issued before). */
    private RequestPostProcessor itAdmin() {
        return token(subject(tenant, itMail), itMail, false, "it_admin");
    }

    /** Someone with no employee of their own gets no pay sheet and no documents at all. */
    private void hasNoCabinet(RequestPostProcessor who, String as) throws Exception {
        assertThat(call(get("/api/v1/me/payroll"), who).getStatus()).as("%s has a pay sheet", as).isEqualTo(404);
        assertThat(call(get("/api/v1/me/documents"), who).getStatus()).as("%s has documents", as).isEqualTo(404);
    }

    /** The it_admin's next sign-in: a confirmed address, so FirstLogin looks at them. */
    private RequestPostProcessor itAdminSignsIn() {
        return token(subject(tenant, itMail), itMail, true, "it_admin");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> request, RequestPostProcessor who)
            throws Exception {
        return mvc.perform(request.with(who)).andReturn().getResponse();
    }

    private static String body(MockHttpServletResponse r) throws Exception {
        return r.getContentAsString(StandardCharsets.UTF_8);
    }

    private String created(MockHttpServletResponse r, String what) throws Exception {
        assertThat(r.getStatus()).as("setup %s: %s", what, body(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.id");
    }

    private String employee(String name, String email) throws Exception {
        return created(call(json(post("/api/v1/employees"),
                "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(name, email, department)), owner()), "employee");
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private UUID accountOf(String email) {
        return su.sql("select id from user_account where tenant_id = ? and email = ?").params(tenant, email)
                .query(UUID.class).optional().orElse(null);
    }

    private UUID employeeOfAccount(String email) {
        return su.sql("select employee_id from user_account where tenant_id = ? and email = ?").params(tenant, email)
                .query(UUID.class).optional().orElse(null);
    }

    private long accountsOfEmployee(String employeeId) {
        return su.sql("select count(*) from user_account where employee_id = ?").param(UUID.fromString(employeeId))
                .query(Long.class).single();
    }

    /** Whoever {@code who} is, V's pay, documents and signature stay out of reach. */
    private void cannotReachV(RequestPostProcessor who, String as) throws Exception {
        MockHttpServletResponse me = call(get("/api/v1/me"), who);
        Object employee = me.getStatus() == 200 ? JsonPath.read(body(me), "$.employee") : null;
        String shown = employee == null ? null : JsonPath.read(body(me), "$.employee.id");
        assertThat(shown).as("%s sees V's card: %s", as, body(me)).isNotEqualTo(employeeV);

        MockHttpServletResponse pay = call(get("/api/v1/me/payroll"), who);
        assertThat(body(pay)).as("%s reads V's pay: HTTP %s %s", as, pay.getStatus(), body(pay)).doesNotContain(PAY_V);
        if (pay.getStatus() == 200) {
            // their own pay sheet, if they are an employee: none of its payments is V's
            assertThat(JsonPath.<List<String>>read(body(pay), "$.payments[*].employeeId")).as(as)
                    .doesNotContain(employeeV);
        } else {
            assertThat(pay.getStatus()).as("%s: %s", as, body(pay)).isEqualTo(404);
        }

        MockHttpServletResponse docs = call(get("/api/v1/me/documents"), who);
        assertThat(body(docs)).as("%s lists V's document", as).doesNotContain(documentV);
        MockHttpServletResponse file = call(get("/api/v1/me/documents/{id}/file", documentV), who);
        assertThat(file.getStatus()).as("%s downloads V's document", as).isEqualTo(404);

        call(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"482915\"}"), who);
        MockHttpServletResponse sign = call(json(post("/api/v1/me/documents/{id}/signature", documentV),
                "{\"code\":\"482915\"}"), who);
        assertThat(sign.getStatus()).as("%s signs V's document: %s", as, body(sign)).isNotEqualTo(200);
        assertThat(su.sql("select count(*) from document_signature where document_id = ?")
                .param(UUID.fromString(documentV)).query(Long.class).single()).as("V's document has a signature")
                .isZero();
    }

    @BeforeEach
    void anOwnerAnItAdminAndEmployeeVWithPayAndADocument() throws Exception {
        login(tenant, ownerMail, "owner", null);
        login(tenant, itMail, "it_admin", null);
        department = created(call(json(post("/api/v1/departments"),
                "{\"name\":\"Отдел %s\"}".formatted(UUID.randomUUID())), owner()), "department");
        employeeV = employee("Сотрудник Тестовый V", vMail);
        String csv = """
                Сотрудник;Вид выплаты;Период;Дата выплаты;Сумма
                %s;Аванс;2026-10;24.10.2026;55555,55
                """.formatted(vMail);
        MockHttpServletResponse pay = call(multipart("/api/v1/payroll/imports").file(new MockMultipartFile("file",
                "vedomost.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))), finance());
        assertThat(pay.getStatus()).as("setup pay: %s", body(pay)).isEqualTo(200);
        documentV = created(call(multipart("/api/v1/employees/{id}/documents", employeeV)
                .file(new MockMultipartFile("file", "dogovor-v.pdf", "application/pdf", pdf()))
                .param("kind", "agreement").param("title", "Соглашение V").param("signers", "employee"), owner()),
                "document");
    }

    // 1-3: the address of V changed to the it_admin's own

    @Test
    void anItAdminWhoPutsTheirAddressOnVDoesNotGetVsCabinet() throws Exception {
        MockHttpServletResponse changed = call(json(put("/api/v1/employees/{id}", employeeV),
                "{\"name\":\"Сотрудник Тестовый V\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(itMail, department)), itAdmin());
        assertThat(changed.getStatus()).as("the it_admin may still edit employees: %s", body(changed)).isEqualTo(200);

        // the next sign-in, and a few more
        for (int i = 0; i < 3; i++) {
            assertThat(call(get("/api/v1/me"), itAdminSignsIn()).getStatus()).isEqualTo(200);
        }
        assertThat(employeeOfAccount(itMail)).as("the it_admin's account was linked to V").isNull();
        assertThat(accountsOfEmployee(employeeV)).isZero();
        cannotReachV(itAdminSignsIn(), "the it_admin");
        hasNoCabinet(itAdminSignsIn(), "the it_admin");
    }

    // 1-3: an account made by hand for a Keycloak account the it_admin holds

    @Test
    void anItAdminCannotBindVToAnAccountTheyCanSignInto() throws Exception {
        String kMail = "k-" + run + "@hijack.example";
        // onboarding gives a Keycloak account with a confirmed address, and shows its password
        MockHttpServletResponse onboarded = call(json(post("/api/v1/employees/onboarding"), """
                {"firstName":"Подставной","lastName":"Сотрудник","departmentId":"%s","position":"x",
                 "email":"%s","createMailbox":false}""".formatted(department, kMail)), itAdmin());
        assertThat(onboarded.getStatus()).as(body(onboarded)).isEqualTo(201);
        String k = ACCOUNTS.stored(kMail).orElseThrow().id();
        // the CostGuard account of it goes; the Keycloak account stays
        UUID made = accountOf(kMail);
        assertThat(call(delete("/api/v1/users/{id}", made), itAdmin()).getStatus()).isEqualTo(204);

        MockHttpServletResponse bound = call(json(post("/api/v1/users"),
                "{\"name\":\"Подставной\",\"email\":\"%s\",\"role\":\"employee\",\"employeeId\":\"%s\"}"
                        .formatted(kMail, employeeV)), itAdmin());
        // the it_admin signs in as that Keycloak account: first what they could reach, then why
        RequestPostProcessor asK = token(k, kMail, true, "employee");
        cannotReachV(asK, "the Keycloak account of the it_admin");
        hasNoCabinet(asK, "the Keycloak account of the it_admin");
        assertThat(bound.getStatus()).as("an it_admin bound V to an account: %s", body(bound)).isEqualTo(403);
        assertThat(accountsOfEmployee(employeeV)).isZero();
        // a user without an employee is still allowed to the it_admin: it reaches nobody's cabinet
        MockHttpServletResponse plain = call(json(post("/api/v1/users"),
                "{\"name\":\"Подставной\",\"email\":\"%s\",\"role\":\"employee\"}".formatted(kMail)), itAdmin());
        assertThat(plain.getStatus()).as(body(plain)).isEqualTo(201);
        assertThat(call(get("/api/v1/me"), asK).getStatus()).isEqualTo(200);
        assertThat(employeeOfAccount(kMail)).isNull();
        cannotReachV(asK, "the Keycloak account of the it_admin, linked to a plain account");
        hasNoCabinet(asK, "the Keycloak account of the it_admin, linked to a plain account");
    }

    // 4, 5: the owner's way, and V keeps their own cabinet whatever the it_admin does to V's address

    @Test
    void theOwnerInvitesVWithTheirEmployeeAndVKeepsTheirCabinet() throws Exception {
        MockHttpServletResponse invited = call(json(post("/api/v1/invitations"), """
                {"email":"%s","firstName":"Тестовый","lastName":"V","role":"auditor","employeeId":"%s"}
                """.formatted(vMail, employeeV)), owner());
        assertThat(invited.getStatus()).as(body(invited)).isEqualTo(201);
        ACCOUNTS.confirm(vMail);
        RequestPostProcessor v = token(ACCOUNTS.stored(vMail).orElseThrow().id(), vMail, true, "employee");

        MockHttpServletResponse me = call(get("/api/v1/me"), v);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).as(body(me)).isEqualTo(employeeV);
        assertThat(body(call(get("/api/v1/me/payroll"), v))).contains(PAY_V);

        // the it_admin puts their own address on V: V is still V, the it_admin gets nothing
        call(json(put("/api/v1/employees/{id}", employeeV),
                "{\"name\":\"Сотрудник Тестовый V\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(itMail, department)), itAdmin());
        call(get("/api/v1/me"), itAdminSignsIn());
        cannotReachV(itAdminSignsIn(), "the it_admin");
        assertThat(body(call(get("/api/v1/me/payroll"), v))).contains(PAY_V);
        assertThat(call(get("/api/v1/me/documents/{id}/file", documentV), v).getStatus()).isEqualTo(200);
    }

    @Test
    void theOwnerMayAddVsUserWithTheEmployeeAndVSignsInWithTheConfirmedAddress() throws Exception {
        MockHttpServletResponse added = call(json(post("/api/v1/users"),
                "{\"name\":\"V\",\"email\":\"%s\",\"role\":\"employee\",\"employeeId\":\"%s\"}"
                        .formatted(vMail, employeeV)), owner());
        assertThat(added.getStatus()).as(body(added)).isEqualTo(201);
        RequestPostProcessor v = token("kc-v-" + run, vMail, true, "employee");
        MockHttpServletResponse me = call(get("/api/v1/me"), v);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).as(body(me)).isEqualTo(employeeV);
        assertThat(body(call(get("/api/v1/me/payroll"), v))).contains(PAY_V);
    }

    // 6: onboarding by the it_admin still works, for the new person only

    @Test
    void onboardingStillGivesTheNewPersonTheirOwnCabinet() throws Exception {
        String nMail = "n-" + run + "@hijack.example";
        MockHttpServletResponse onboarded = call(json(post("/api/v1/employees/onboarding"), """
                {"firstName":"Новый","lastName":"Сотрудник","departmentId":"%s","position":"x",
                 "email":"%s","createMailbox":false}""".formatted(department, nMail)), itAdmin());
        assertThat(onboarded.getStatus()).as(body(onboarded)).isEqualTo(201);
        String n = JsonPath.read(body(onboarded), "$.employee.id");
        RequestPostProcessor asN = token(ACCOUNTS.stored(nMail).orElseThrow().id(), nMail, true, "employee");
        MockHttpServletResponse me = call(get("/api/v1/me"), asN);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).isEqualTo(n);
        cannotReachV(asN, "the new person");
    }

    // 7: many sign-ins at once link nothing wrong

    @Test
    void parallelSignInsLinkOnlyWhatIsTheirsAndNeverV() throws Exception {
        call(json(put("/api/v1/employees/{id}", employeeV),
                "{\"name\":\"Сотрудник Тестовый V\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(itMail, department)), itAdmin());
        String pMail = "p-" + run + "@hijack.example";
        assertThat(call(json(post("/api/v1/users"),
                "{\"name\":\"P\",\"email\":\"%s\",\"role\":\"auditor\"}".formatted(pMail)), owner()).getStatus())
                .isEqualTo(201);
        String pSub = "kc-p-" + run;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> call(get("/api/v1/me"), itAdminSignsIn()).getStatus());
                calls.add(() -> call(get("/api/v1/me"), token(pSub, pMail, true, "auditor")).getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(su.sql("select count(*) from user_account where keycloak_subject = ?").param(pSub)
                .query(Long.class).single()).as("one account for the Keycloak account").isEqualTo(1);
        assertThat(accountOf(pMail)).isEqualTo(su.sql("select id from user_account where keycloak_subject = ?")
                .param(pSub).query(UUID.class).single());
        assertThat(accountsOfEmployee(employeeV)).isZero();
        assertThat(employeeOfAccount(itMail)).isNull();
        cannotReachV(itAdminSignsIn(), "the it_admin after parallel sign-ins");
        hasNoCabinet(itAdminSignsIn(), "the it_admin after parallel sign-ins");
    }

    // 8: an existing link is never replaced

    @Test
    void anExistingLinkStaysWhenAnotherEmployeeTakesTheAccountsAddress() throws Exception {
        String lMail = "l-" + run + "@hijack.example";
        String a = employee("Сотрудник Тестовый A", "a-" + run + "@hijack.example");
        login(tenant, lMail, "employee", UUID.fromString(a));
        // employee V now carries the address of L's account
        call(json(put("/api/v1/employees/{id}", employeeV),
                "{\"name\":\"Сотрудник Тестовый V\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                        .formatted(lMail, department)), itAdmin());
        RequestPostProcessor l = token(subject(tenant, lMail), lMail, true, "employee");
        MockHttpServletResponse me = call(get("/api/v1/me"), l);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).isEqualTo(a);
        assertThat(employeeOfAccount(lMail)).isEqualTo(UUID.fromString(a));
        cannotReachV(l, "L");
    }
}
