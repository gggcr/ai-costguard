package ai.costguard.core.cabinet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.costguard.core.PostgresTestSupport;
import ai.costguard.core.shared.security.RealmRoles;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The e-mail spoofing finding of 2026-11-12, as a regression test. Employee A puts B's address on A's own Keycloak
 * account: the token keeps A's {@code sub} and says {@code email_verified = false}. Before the fix the cabinet took
 * the person from the token's e-mail, so A read B's pay and documents, signed B's document and locked B out of B's
 * own signing key. The person is now the account linked to the token's subject.
 *
 * <p>Every test checks who the returned data belongs to, not only the status. All data is fictitious.
 */
@SpringBootTest(properties = "costguard.connections.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
class EmailSpoofingIntegrationTest extends PostgresTestSupport {

    private static final String PAY_A = "11111.11";
    private static final String PAY_B = "77777.77";

    @Autowired MockMvc mvc;
    @Autowired RealmRoles realmRoles;

    private final UUID tenant = UUID.randomUUID();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String mailA = "a-" + run + "@corp.example";
    private final String mailB = "b-" + run + "@corp.example";
    private String employeeA;
    private String employeeB;

    private RequestPostProcessor token(UUID tenantId, String sub, String email, boolean verified, String name,
            String... roles) {
        return jwt().jwt(j -> j.subject(sub).claim("tenant_id", tenantId.toString()).claim("email", email)
                        .claim("email_verified", verified).claim("name", name)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(realmRoles);
    }

    /** A, signed in as themselves. */
    private RequestPostProcessor a() {
        return token(tenant, subject(tenant, mailA), mailA, true, "Сотрудник А", "employee");
    }

    /** A after putting B's address on A's Keycloak account: A's sub, B's e-mail, not verified, a new name. */
    private RequestPostProcessor aAsB() {
        return token(tenant, subject(tenant, mailA), mailB, false, "Сотрудник Тестовый Б", "employee");
    }

    /** Someone whose Keycloak account no CostGuard account is linked to, with B's address on it. */
    private RequestPostProcessor strangerAsB(boolean verified) {
        return token(tenant, "kc-stranger-" + run, mailB, verified, "Чужой", "employee");
    }

    /** B, signed in as themselves. */
    private RequestPostProcessor b() {
        return token(tenant, subject(tenant, mailB), mailB, true, "Сотрудник Б", "employee");
    }

    private RequestPostProcessor owner() {
        String mail = "owner-" + run + "@corp.example";
        return token(tenant, subject(tenant, mail), mail, true, "Владелец", "owner");
    }

    private RequestPostProcessor finance() {
        String mail = "fin-" + run + "@corp.example";
        return token(tenant, subject(tenant, mail), mail, true, "Бухгалтер", "finance");
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

    private String create(String path, String json) throws Exception {
        MockHttpServletResponse r = call(json(post(path), json), owner());
        assertThat(r.getStatus()).as("setup %s: %s", path, body(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.id");
    }

    private String employee(String name, String email) throws Exception {
        String department = create("/api/v1/departments", "{\"name\":\"Отдел %s\"}".formatted(UUID.randomUUID()));
        return create("/api/v1/employees", "{\"name\":\"%s\",\"email\":\"%s\",\"departmentId\":\"%s\",\"position\":\"Инженер\"}"
                .formatted(name, email, department));
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private String documentOf(String employeeId, String title) throws Exception {
        var request = multipart("/api/v1/employees/{id}/documents", employeeId)
                .file(new MockMultipartFile("file", "dogovor.pdf", "application/pdf", pdf()))
                .param("kind", "agreement").param("title", title).param("signers", "employee");
        MockHttpServletResponse r = call(request, owner());
        assertThat(r.getStatus()).as("setup document: %s", body(r)).isEqualTo(201);
        return JsonPath.read(body(r), "$.id");
    }

    private List<Map<String, Object>> signaturesOf(String employeeId, String documentId) throws Exception {
        MockHttpServletResponse r = call(get("/api/v1/employees/{id}/documents", employeeId), owner());
        assertThat(r.getStatus()).isEqualTo(200);
        return JsonPath.read(body(r), "$[?(@.id == '%s')].signatures[*]".formatted(documentId));
    }

    @BeforeEach
    void twoEmployeesWithTheirAccountsAndPay() throws Exception {
        employeeA = employee("Сотрудник Тестовый А", mailA);
        employeeB = employee("Сотрудник Тестовый Б", mailB);
        login(tenant, mailA, "employee", UUID.fromString(employeeA));
        login(tenant, mailB, "employee", UUID.fromString(employeeB));
        login(tenant, "owner-" + run + "@corp.example", "owner", null);
        login(tenant, "fin-" + run + "@corp.example", "finance", null);
        String csv = """
                Сотрудник;Вид выплаты;Период;Дата выплаты;Сумма
                %s;Аванс;2026-10;24.10.2026;11111,11
                %s;Аванс;2026-10;24.10.2026;77777,77
                """.formatted(mailA, mailB);
        MockHttpServletResponse r = call(multipart("/api/v1/payroll/imports").file(new MockMultipartFile("file",
                "vedomost.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))), finance());
        assertThat(r.getStatus()).as("setup payroll: %s", body(r)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(r), "$.stored")).as("setup payroll stored: %s", body(r)).isEqualTo(2);
    }

    // 1, 4: each person with their own token sees exactly their own data

    @Test
    void eachPersonWithTheirOwnTokenSeesOnlyTheirOwnPayAndCard() throws Exception {
        MockHttpServletResponse payA = call(get("/api/v1/me/payroll"), a());
        assertThat(payA.getStatus()).isEqualTo(200);
        assertThat(body(payA)).contains(PAY_A).doesNotContain(PAY_B);
        assertThat(JsonPath.<List<String>>read(body(payA), "$.payments[*].employeeId")).containsOnly(employeeA);

        MockHttpServletResponse payB = call(get("/api/v1/me/payroll"), b());
        assertThat(payB.getStatus()).isEqualTo(200);
        assertThat(body(payB)).contains(PAY_B).doesNotContain(PAY_A);
        assertThat(JsonPath.<List<String>>read(body(payB), "$.payments[*].employeeId")).containsOnly(employeeB);

        MockHttpServletResponse meB = call(get("/api/v1/me"), b());
        assertThat((String) JsonPath.read(body(meB), "$.employee.id")).isEqualTo(employeeB);
    }

    // 2, 3: A with B's (unverified) e-mail is still A

    @Test
    void aWithBsEmailIsStillAAndNeverSeesBsCard() throws Exception {
        MockHttpServletResponse me = call(get("/api/v1/me"), aAsB());
        assertThat(me.getStatus()).as(body(me)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).as(body(me)).isEqualTo(employeeA);
        assertThat((String) JsonPath.read(body(me), "$.email")).as("the stored address, not the token's")
                .isEqualTo(mailA);
    }

    @Test
    void aWithBsEmailGetsOnlyAsOwnPay() throws Exception {
        MockHttpServletResponse r = call(get("/api/v1/me/payroll"), aAsB());
        assertThat(r.getStatus()).as(body(r)).isEqualTo(200);
        assertThat(body(r)).as("A must not read B's pay").doesNotContain(PAY_B).contains(PAY_A);
        assertThat(JsonPath.<List<String>>read(body(r), "$.payments[*].employeeId")).containsOnly(employeeA);
    }

    @Test
    void aSignInWithoutAnAccountAndWithBsEmailFindsNoEmployee() throws Exception {
        for (boolean verified : new boolean[] {false, true}) {
            // B's account is linked to B's sub already, so even a confirmed address links nothing
            MockHttpServletResponse me = call(get("/api/v1/me"), strangerAsB(verified));
            assertThat(me.getStatus()).isEqualTo(200);
            assertThat((Object) JsonPath.read(body(me), "$.employee")).as("verified=%s: %s", verified, body(me))
                    .isNull();
            MockHttpServletResponse pay = call(get("/api/v1/me/payroll"), strangerAsB(verified));
            assertThat(pay.getStatus()).as(body(pay)).isEqualTo(404);
            assertThat(body(pay)).doesNotContain(PAY_B).contains("not_an_employee");
            MockHttpServletResponse docs = call(get("/api/v1/me/documents"), strangerAsB(verified));
            assertThat(docs.getStatus()).isEqualTo(404);
        }
    }

    // 3: an unconfirmed e-mail never links a Keycloak account to an account without one; a confirmed one does

    @Test
    void onlyAConfirmedEmailLinksAnAccountAddedByHand() throws Exception {
        String mailC = "c-" + run + "@corp.example";
        String employeeC = employee("Сотрудник Тестовый В", mailC);
        // the company adds C's account by hand: no Keycloak account is linked to it yet
        MockHttpServletResponse added = call(json(post("/api/v1/users"),
                "{\"name\":\"В\",\"email\":\"%s\",\"role\":\"auditor\",\"employeeId\":\"%s\"}".formatted(mailC, employeeC)),
                owner());
        assertThat(added.getStatus()).as(body(added)).isEqualTo(201);
        String intruder = "kc-intruder-" + run;
        RequestPostProcessor unverified = token(tenant, intruder, mailC, false, "Чужой", "employee");
        assertThat(call(get("/api/v1/me/documents"), unverified).getStatus()).isEqualTo(404);
        assertThat(call(get("/api/v1/me/documents"), unverified).getStatus()).as("still not linked").isEqualTo(404);
        String linked = JdbcClient.create(superuserAppDataSource())
                .sql("select keycloak_subject from user_account where tenant_id = ? and email = ?")
                .params(tenant, mailC).query(String.class).optional().orElse(null);
        assertThat(linked).as("an unconfirmed address linked a Keycloak account").isNull();

        String realC = "kc-real-c-" + run;
        RequestPostProcessor confirmed = token(tenant, realC, mailC, true, "В", "auditor");
        MockHttpServletResponse me = call(get("/api/v1/me"), confirmed);
        assertThat((String) JsonPath.read(body(me), "$.employee.id")).as(body(me)).isEqualTo(employeeC);
        // once linked, the address of the token no longer matters
        MockHttpServletResponse after = call(get("/api/v1/me"),
                token(tenant, realC, "other-" + run + "@corp.example", false, "В", "auditor"));
        assertThat((String) JsonPath.read(body(after), "$.employee.id")).isEqualTo(employeeC);
        // and the intruder, now with a confirmed address, is not C either: the account is taken
        MockHttpServletResponse late = call(get("/api/v1/me"), token(tenant, intruder, mailC, true, "Чужой", "employee"));
        assertThat((Object) JsonPath.read(body(late), "$.employee")).isNull();
    }

    /** At a first sign-in the same rule as V47: an address links only when it names one person both ways. */
    @Test
    void aConfirmedFirstSignInLinksNothingAmbiguous() throws Exception {
        // two accounts whose addresses differ in case only, one already linked to its Keycloak account (as F5): the
        // confirmed address still names two accounts, so the other one is not linked by it
        String twinUpper = "Twin-" + run + "@corp.example";
        String twinLower = twinUpper.toLowerCase(java.util.Locale.ROOT);
        for (String mail : List.of(twinUpper, twinLower)) {
            MockHttpServletResponse added = call(json(post("/api/v1/users"),
                    "{\"name\":\"Двойник\",\"email\":\"%s\",\"role\":\"auditor\"}".formatted(mail)), owner());
            assertThat(added.getStatus()).as(body(added)).isEqualTo(201);
        }
        login(tenant, twinUpper, "auditor", null);
        String twinSub = "kc-twin-" + run;
        MockHttpServletResponse twin = call(get("/api/v1/me"), token(tenant, twinSub, twinLower, true, "Д", "auditor"));
        assertThat((Object) JsonPath.read(body(twin), "$.userId")).as(body(twin)).isNull();
        assertThat(JdbcClient.create(superuserAppDataSource())
                .sql("select count(*) from user_account where keycloak_subject = ?").param(twinSub)
                .query(Long.class).single()).isZero();

        // one account, two working employees whose addresses differ in case only, one of them linked to another account
        // already (as F3): the account links, its employee does not, though only one of the two is free
        String multi = "multi-" + run + "@corp.example";
        String upper = employee("Сотрудник Мульти 1", "Multi-" + run + "@corp.example");
        String lower = employee("Сотрудник Мульти 2", multi);
        login(tenant, "keeper-" + run + "@corp.example", "employee", UUID.fromString(upper));
        MockHttpServletResponse added = call(json(post("/api/v1/users"),
                "{\"name\":\"Мульти\",\"email\":\"%s\",\"role\":\"auditor\"}".formatted(multi)), owner());
        assertThat(added.getStatus()).as(body(added)).isEqualTo(201);
        String multiSub = "kc-multi-" + run;
        MockHttpServletResponse me = call(get("/api/v1/me"), token(tenant, multiSub, multi, true, "М", "auditor"));
        assertThat((String) JsonPath.read(body(me), "$.userId")).as("the account is the only one with the address")
                .isNotNull();
        assertThat((Object) JsonPath.read(body(me), "$.employee")).as("two employees have the address: %s", body(me))
                .isNull();
        assertThat(JdbcClient.create(superuserAppDataSource())
                .sql("select count(*) from user_account where employee_id in (?, ?)")
                .params(UUID.fromString(upper), UUID.fromString(lower)).query(Long.class).single())
                .as("only the keeper's link to the first one").isEqualTo(1);
        assertThat(JdbcClient.create(superuserAppDataSource())
                .sql("select count(*) from user_account where employee_id = ?").param(UUID.fromString(lower))
                .query(Long.class).single()).isZero();
    }

    // 5: documents

    @Test
    void aWithBsEmailCannotListOrDownloadBsDocument() throws Exception {
        String docB = documentOf(employeeB, "Соглашение Б");
        String docA = documentOf(employeeA, "Соглашение А");

        MockHttpServletResponse list = call(get("/api/v1/me/documents"), aAsB());
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(body(list), "$[*].id")).as(body(list)).contains(docA)
                .doesNotContain(docB);
        MockHttpServletResponse file = call(get("/api/v1/me/documents/{id}/file", docB), aAsB());
        assertThat(file.getStatus()).as("A downloaded B's document").isEqualTo(404);
        MockHttpServletResponse stranger = call(get("/api/v1/me/documents/{id}/file", docB), strangerAsB(false));
        assertThat(stranger.getStatus()).isEqualTo(404);

        MockHttpServletResponse own = call(get("/api/v1/me/documents/{id}/file", docB), b());
        assertThat(own.getStatus()).isEqualTo(200);
        assertThat(own.getContentType()).isEqualTo("application/pdf");
    }

    // 6, 7, 8: the signing key and the signature

    @Test
    void aWithBsEmailCannotTakeBsKeyNorSignBsDocumentAndBSetsTheirOwnKey() throws Exception {
        String docB = documentOf(employeeB, "Соглашение Б на подпись");

        // A sets a PIN with B's address on the token: it is A's key, A's account
        MockHttpServletResponse pin = call(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"493817\"}"), aAsB());
        assertThat(pin.getStatus()).as(body(pin)).isEqualTo(200);
        UUID accountA = JdbcClient.create(superuserAppDataSource())
                .sql("select id from user_account where keycloak_subject = ?").param(subject(tenant, mailA))
                .query(UUID.class).single();
        List<UUID> owners = JdbcClient.create(superuserAppDataSource())
                .sql("select user_account_id from signing_key where tenant_id = ?").param(tenant)
                .query(UUID.class).list();
        assertThat(owners).as("the key went to A's account, not to B's").containsExactly(accountA);

        // A cannot sign B's document with it
        MockHttpServletResponse sign = call(json(post("/api/v1/me/documents/{id}/signature", docB),
                "{\"code\":\"493817\"}"), aAsB());
        assertThat(sign.getStatus()).as("A signed B's document: %s", body(sign)).isEqualTo(404);
        assertThat(signaturesOf(employeeB, docB)).as("B's document has a signature").isEmpty();

        // nor can someone without an account set a key under B's address
        MockHttpServletResponse strangerPin = call(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"493817\"}"),
                strangerAsB(false));
        assertThat(strangerPin.getStatus()).isEqualTo(403);
        assertThat(body(strangerPin)).contains("signing_no_account");

        // B sets their own key without any code: A's key is not in the way, then B signs
        MockHttpServletResponse bPin = call(json(put("/api/v1/me/signing-key/pin"), "{\"pin\":\"604827\"}"), b());
        assertThat(bPin.getStatus()).as("B could not set their own key: %s", body(bPin)).isEqualTo(200);
        MockHttpServletResponse bSign = call(json(post("/api/v1/me/documents/{id}/signature", docB),
                "{\"code\":\"604827\"}"), b());
        assertThat(bSign.getStatus()).as(body(bSign)).isEqualTo(200);
        List<Map<String, Object>> signatures = signaturesOf(employeeB, docB);
        assertThat(signatures).hasSize(1);
        // the stamp names B's account as the company stored it, not as a token says (b() says "Сотрудник Б")
        assertThat(signatures.get(0)).containsEntry("party", "employee").containsEntry("signerEmail", mailB)
                .containsEntry("signerName", "Сотрудник Тестовый Б");
        // A's PIN is not B's
        MockHttpServletResponse bWithAsPin = call(json(put("/api/v1/me/signing-key/pin"),
                "{\"pin\":\"715938\",\"current\":\"493817\"}"), b());
        assertThat(bWithAsPin.getStatus()).isEqualTo(403);
    }

    // notification settings (ChannelSettings) and personal notifications

    @Test
    void aWithBsEmailReachesOnlyAsOwnNotificationSettings() throws Exception {
        MockHttpServletResponse saved = call(json(put("/api/v1/notifications/settings"),
                "{\"emailLevel\":\"off\",\"telegramLevel\":\"off\"}"), aAsB());
        assertThat(saved.getStatus()).as(body(saved)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(saved), "$.email")).as("A changed B's settings").isEqualTo(mailA);

        MockHttpServletResponse ofB = call(get("/api/v1/notifications/settings"), b());
        assertThat((String) JsonPath.read(body(ofB), "$.email")).isEqualTo(mailB);
        assertThat((String) JsonPath.read(body(ofB), "$.emailLevel")).as("B's levels were changed").isNotEqualTo("off");

        MockHttpServletResponse stranger = call(get("/api/v1/notifications/settings"), strangerAsB(false));
        assertThat(stranger.getStatus()).isEqualTo(404);
        MockHttpServletResponse notices = call(get("/api/v1/me/notifications"), strangerAsB(false));
        assertThat(notices.getStatus()).isEqualTo(404);
    }

    // 9: another company

    @Test
    void bsOwnSubjectInAnotherCompanyFindsNothing() throws Exception {
        RequestPostProcessor otherTenant = token(UUID.randomUUID(), subject(tenant, mailB), mailB, true, "Б",
                "employee");
        MockHttpServletResponse r = call(get("/api/v1/me/payroll"), otherTenant);
        assertThat(r.getStatus()).as(body(r)).isEqualTo(404);
        assertThat(body(r)).doesNotContain(PAY_B);
        assertThat(call(get("/api/v1/me/documents"), otherTenant).getStatus()).isEqualTo(404);
    }
}
