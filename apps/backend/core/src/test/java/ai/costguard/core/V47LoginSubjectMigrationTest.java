package ai.costguard.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V47 (the e-mail spoofing fix) on a database that already holds companies, not on the empty one every other test
 * migrates. A database of its own is migrated by Flyway up to V46, filled with fictitious rows as they were before
 * V47, and then the real V47 runs through Flyway, as the owner of the tables (the role Flyway connects as in
 * production, held by forced RLS). Nothing here runs pieces of the migration by hand.
 *
 * <p>Each scenario lives in a company of its own, so one cannot satisfy another. A subject or an employee link may
 * come only from what the core itself wrote or what the company stored, and never when it is ambiguous: an address
 * links an account to an employee only when exactly one account and exactly one working employee of the company
 * have it (ignoring case, as the application compares addresses; surrounding spaces are not trimmed, as the
 * application never trims stored addresses either).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V47LoginSubjectMigrationTest extends PostgresTestSupport {

    /** Every table V47 changes or reads past RLS. */
    private static final List<String> TABLES = List.of("user_account", "invitation", "registration", "employee",
            "signing_key", "document_signature");
    /** The tables V47 takes FORCE off for its updates. */
    private static final List<String> TOGGLED = List.of("user_account", "invitation", "registration", "employee");

    private String db;
    private JdbcClient su;
    private MigrateResult v47;
    private long t = 0;

    // snapshots right before and right after V47, before any test here writes
    private final Map<String, Long> rowsBefore = new LinkedHashMap<>();
    private final Map<String, Long> rowsAfter = new LinkedHashMap<>();
    private List<Map<String, Object>> policiesBefore;
    private List<Map<String, Object>> rlsBefore;
    private Map<UUID, Map<String, Object>> usersBefore;
    private Map<UUID, Map<String, Object>> usersAfter;
    private List<Map<String, Object>> keysBefore;
    private List<Map<String, Object>> keysAfter;
    private List<Map<String, Object>> signaturesBefore;
    private List<Map<String, Object>> signaturesAfter;
    private long keysWithAccountAfter;
    private long signaturesWithAccountAfter;

    // A
    private final UUID tA = UUID.randomUUID();
    private UUID aSent;
    private UUID aAccepted;
    private UUID aTwoInvitations;
    private UUID aTie;
    private UUID aTwinRows;
    private UUID aEmptyId;
    // B
    private final UUID tB = UUID.randomUUID();
    private UUID bOnlyRevoked;
    private UUID bRevokedNewest;
    // C
    private final UUID tC = UUID.randomUUID();
    private UUID cOwner;
    private final UUID tC2 = UUID.randomUUID();
    private UUID cNotOwner;
    private final UUID tC3 = UUID.randomUUID();
    private UUID cOwnerUpper;
    private UUID cOwnerLower;
    private final UUID tC4 = UUID.randomUUID();
    private UUID cInvitedOwner;
    private UUID cRegisteredOwner;
    // D (= F3, one account and two employees after lower())
    private final UUID tD = UUID.randomUUID();
    private UUID dUser;
    private UUID dUpper;
    private UUID dLower;
    // E
    private final UUID tE = UUID.randomUUID();
    private UUID eUser;
    private UUID eEmployee;
    private UUID eLinkedUser;
    private UUID eLinkedEmployee;
    private UUID eOtherEmployee;
    private UUID eDismissedUser;
    private UUID eWorking;
    // F1-F8
    private final UUID tF1 = UUID.randomUUID();
    private UUID f1A;
    private UUID f1B;
    private UUID f1C;
    private final UUID tF2 = UUID.randomUUID();
    private UUID f2Employee;
    private UUID f2Upper;
    private UUID f2Lower;
    private final UUID tF3 = UUID.randomUUID();
    private UUID f3User;
    private UUID f3Taken;
    private UUID f3Free;
    private final UUID tF4 = UUID.randomUUID();
    private UUID f4User;
    private UUID f4Linked;
    private UUID f4SameAddress;
    private final UUID tF5 = UUID.randomUUID();
    private UUID f5X;
    private UUID f5Y;
    private UUID f5Z;
    private UUID f5U1;
    private UUID f5U2;
    private UUID f5U3;
    private final UUID tF6a = UUID.randomUUID();
    private final UUID tF6b = UUID.randomUUID();
    private final UUID tF6c = UUID.randomUUID();
    private UUID f6UserA;
    private UUID f6EmployeeA;
    private UUID f6UserB;
    private UUID f6EmployeeB;
    private UUID f6Lonely;
    private final UUID tF7 = UUID.randomUUID();
    private UUID f7Padded;
    private UUID f7Case;
    private UUID f7CaseEmployee;
    private UUID f7PaddedOwner;
    private final UUID tF8 = UUID.randomUUID();
    private UUID f8Shared;
    private UUID f8D1;
    private UUID f8D2;
    // G, H, I
    private final UUID tG = UUID.randomUUID();
    private UUID gGoneUser;
    private final UUID tH = UUID.randomUUID();
    private final UUID tH2 = UUID.randomUUID();
    private UUID hUser;
    private UUID hForeignInvitationUser;
    private final UUID tI = UUID.randomUUID();
    private UUID iFirst;
    private UUID iSecond;
    // K: signing keys and signatures
    private final UUID tK = UUID.randomUUID();
    private UUID kOwner;
    private UUID kDocument;
    private final UUID tK2 = UUID.randomUUID();
    private UUID k2Signer;
    private UUID k2Document;

    private Timestamp at() {
        return Timestamp.from(Instant.parse("2026-11-01T09:00:00Z").plusSeconds(60 * ++t));
    }

    private void tenant(UUID id) {
        su.sql("insert into tenant (id, name) values (?, ?)").params(id, "ООО Тест " + id.toString().substring(0, 6))
                .update();
    }

    private UUID department(UUID tenant) {
        UUID id = UUID.randomUUID();
        su.sql("insert into department (id, tenant_id, name) values (?, ?, ?)").params(id, tenant, "Отдел " + id)
                .update();
        return id;
    }

    private UUID employee(UUID tenant, String email, String dismissedOn, boolean erased) {
        UUID id = UUID.randomUUID();
        su.sql("""
                insert into employee (id, tenant_id, department_id, name, email, position, dismissed_at, erased_at)
                values (?, ?, ?, 'Тестовый Сотрудник', ?, 'Инженер', cast(? as date), ?)
                """).params(id, tenant, department(tenant), email, dismissedOn, erased ? at() : null).update();
        return id;
    }

    private UUID employee(UUID tenant, String email) {
        return employee(tenant, email, null, false);
    }

    private UUID user(UUID tenant, String email, String role, UUID employeeId) {
        UUID id = UUID.randomUUID();
        su.sql("insert into user_account (id, tenant_id, name, email, role, employee_id) values (?, ?, ?, ?, ?, ?)")
                .params(id, tenant, "Учётка " + email, email, role, employeeId).update();
        return id;
    }

    private void invitation(UUID tenant, UUID userId, String email, String keycloakId, String status, Timestamp when) {
        su.sql("""
                insert into invitation (id, tenant_id, user_account_id, email, role, keycloak_user_id, status,
                                        created_at, accepted_at)
                values (?, ?, ?, ?, 'finance', ?, ?, ?, ?)
                """).params(UUID.randomUUID(), tenant, userId, email, keycloakId, status, when,
                        "accepted".equals(status) ? when : null).update();
    }

    private void invitation(UUID tenant, UUID userId, String email, String keycloakId, String status) {
        invitation(tenant, userId, email, keycloakId, status, at());
    }

    private void registration(UUID tenant, String keycloakId, String email) {
        su.sql("insert into registration (tenant_id, keycloak_user_id, email, created_at) values (?, ?, ?, ?)")
                .params(tenant, keycloakId, email, at()).update();
    }

    private UUID document(UUID tenant, UUID employeeId) {
        UUID id = UUID.randomUUID();
        su.sql("""
                insert into employee_document (id, tenant_id, employee_id, kind, title, file_key, file_name,
                                               content_type, size, uploaded_by, created_at, signers)
                values (?, ?, ?, 'agreement', 'Соглашение', ?, 'doc.pdf', 'application/pdf', 1, 'Кадры', ?,
                        '{employee,employer}')
                """).params(id, tenant, employeeId, "k/" + id, at()).update();
        return id;
    }

    private void signature(UUID tenant, UUID documentId, String party, String email) {
        su.sql("""
                insert into document_signature (id, tenant_id, document_id, party, signer_name, signer_email, method,
                                                document_sha256, signed_at)
                values (?, ?, ?, ?, 'Тестовый Подписант', ?, 'pin', 'ab12', ?)
                """).params(UUID.randomUUID(), tenant, documentId, party, email, at()).update();
    }

    private String subjectOf(UUID userId) {
        return su.sql("select keycloak_subject from user_account where id = ?").param(userId).query(String.class)
                .optional().orElse(null);
    }

    private UUID employeeOf(UUID userId) {
        return su.sql("select employee_id from user_account where id = ?").param(userId).query(UUID.class)
                .optional().orElse(null);
    }

    private long count(String table) {
        return su.sql("select count(*) from " + table).query(Long.class).single();
    }

    private List<Map<String, Object>> policies() {
        return su.sql("""
                select tablename, policyname, permissive, cmd, roles::text as roles, qual, with_check
                from pg_policies where schemaname = 'public' and tablename in (:tables)
                order by tablename, policyname""").param("tables", TABLES).query().listOfRows();
    }

    private List<Map<String, Object>> rls() {
        return su.sql("""
                select relname, relrowsecurity, relforcerowsecurity from pg_class
                where relnamespace = 'public'::regnamespace and relname in (:tables) order by relname""")
                .param("tables", TABLES).query().listOfRows();
    }

    private Map<UUID, Map<String, Object>> users() {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        su.sql("select id, tenant_id, name, email, role, employee_id from user_account").query().listOfRows()
                .forEach(r -> out.put((UUID) r.get("id"), r));
        return out;
    }

    private List<Map<String, Object>> keys() {
        return su.sql("""
                select tenant_id, email, method, encode(totp_secret, 'hex') as totp_secret,
                       encode(pending_secret, 'hex') as pending_secret, pin_hash, last_step, failed, locked_until,
                       updated_at
                from signing_key order by tenant_id, email, pin_hash""").query().listOfRows();
    }

    private List<Map<String, Object>> signatures() {
        return su.sql("""
                select id, tenant_id, document_id, party, signer_name, signer_email, method, document_sha256, signed_at
                from document_signature order by id""").query().listOfRows();
    }

    private static Flyway flyway(javax.sql.DataSource owner, String target) {
        return Flyway.configure().dataSource(owner).locations("classpath:db/migration").target(target).load();
    }

    @BeforeAll
    void companiesAsTheyWereBeforeV47ThenV47() {
        db = createDatabase("v47");
        flyway(database(db, "costguard"), "46").migrate();
        su = JdbcClient.create(database(db, "postgres"));
        assertThat(su.sql("select max(version::int) from flyway_schema_history where success").query(Integer.class)
                .single()).isEqualTo(46);
        assertThat(su.sql("""
                select count(*) from information_schema.columns
                where table_name = 'user_account' and column_name = 'keycloak_subject'""")
                .query(Long.class).single()).as("the database is before V47").isZero();

        // A: the core created the Keycloak account with the invitation
        tenant(tA);
        aSent = user(tA, "a-sent@a.example", "finance", null);
        invitation(tA, aSent, "a-sent@a.example", "kc-a-sent", "sent");
        aAccepted = user(tA, "a-accepted@a.example", "auditor", null);
        invitation(tA, aAccepted, "a-accepted@a.example", "kc-a-accepted", "accepted");
        aTwoInvitations = user(tA, "a-two@a.example", "finance", null);
        invitation(tA, aTwoInvitations, "a-two@a.example", "kc-a-two-old", "sent");
        invitation(tA, aTwoInvitations, "a-two@a.example", "kc-a-two-new", "accepted");
        Timestamp same = at();
        aTie = user(tA, "a-tie@a.example", "finance", null);
        invitation(tA, aTie, "a-tie@a.example", "kc-a-tie-1", "sent", same);
        invitation(tA, aTie, "a-tie@a.example", "kc-a-tie-2", "sent", same);
        Timestamp twin = at();
        aTwinRows = user(tA, "a-twin@a.example", "finance", null);
        invitation(tA, aTwinRows, "a-twin@a.example", "kc-a-twin", "sent", twin);
        invitation(tA, aTwinRows, "a-twin@a.example", "kc-a-twin", "accepted", twin);
        aEmptyId = user(tA, "a-empty@a.example", "finance", null);
        invitation(tA, aEmptyId, "a-empty@a.example", "kc-a-empty-old", "sent");
        invitation(tA, aEmptyId, "a-empty@a.example", "", "sent");

        // B: revoked invitations (revoking deletes the Keycloak account, so its id names no one)
        tenant(tB);
        bOnlyRevoked = user(tB, "b-revoked@b.example", "finance", null);
        invitation(tB, bOnlyRevoked, "b-revoked@b.example", "kc-b-revoked", "revoked");
        bRevokedNewest = user(tB, "b-revoked-newest@b.example", "finance", null);
        invitation(tB, bRevokedNewest, "b-revoked-newest@b.example", "kc-b-older-sent", "sent");
        invitation(tB, bRevokedNewest, "b-revoked-newest@b.example", "kc-b-newest-revoked", "revoked");

        // C: self-registration of the owner
        tenant(tC);
        cOwner = user(tC, "Owner@c.example", "owner", null);
        registration(tC, "kc-c-owner", "owner@c.example");
        tenant(tC2);
        cNotOwner = user(tC2, "boss@c2.example", "finance", null);
        registration(tC2, "kc-c2", "boss@c2.example");
        tenant(tC3);
        cOwnerUpper = user(tC3, "Twin@c3.example", "owner", null);
        cOwnerLower = user(tC3, "twin@c3.example", "owner", null);
        registration(tC3, "kc-c3", "twin@c3.example");
        tenant(tC4);
        cInvitedOwner = user(tC4, "inv@c4.example", "owner", null);
        invitation(tC4, cInvitedOwner, "inv@c4.example", "kc-c4", "accepted");
        cRegisteredOwner = user(tC4, "reg@c4.example", "owner", null);
        registration(tC4, "kc-c4", "reg@c4.example");

        // D / F3: one account, two working employees with its address after lower() (the exact same address twice is
        // refused by unique (tenant_id, email), checked below)
        tenant(tD);
        dUpper = employee(tD, "Dup@d.example");
        dLower = employee(tD, "dup@d.example");
        dUser = user(tD, "dup@d.example", "employee", null);

        // E: exactly one working employee; an account linked already keeps its link; a dismissed namesake is ignored
        tenant(tE);
        eEmployee = employee(tE, "solo@e.example");
        eUser = user(tE, "Solo@E.example", "employee", null);
        eLinkedEmployee = employee(tE, "linked@e.example");
        eOtherEmployee = employee(tE, "LINKED@e.example");
        eLinkedUser = user(tE, "linked@e.example", "employee", eOtherEmployee);
        employee(tE, "Back@e.example", "2026-01-31", false);
        eWorking = employee(tE, "back@e.example");
        eDismissedUser = user(tE, "BACK@e.example", "employee", null);

        // F1: employee B is linked to account A (another address); account C has B's address
        tenant(tF1);
        f1B = employee(tF1, "b@f1.example");
        f1A = user(tF1, "hr-a@f1.example", "employee", f1B);
        f1C = user(tF1, "b@f1.example", "employee", null);

        // F2: two unlinked accounts with one address (after lower()), one working employee with it
        tenant(tF2);
        f2Employee = employee(tF2, "pair@f2.example");
        f2Upper = user(tF2, "Pair@f2.example", "employee", null);
        f2Lower = user(tF2, "pair@f2.example", "employee", null);

        // F3: one account, two employees with its address, one of them linked elsewhere already
        tenant(tF3);
        f3Taken = employee(tF3, "Multi@f3.example");
        user(tF3, "keeper@f3.example", "employee", f3Taken);
        f3Free = employee(tF3, "multi@f3.example");
        f3User = user(tF3, "multi@f3.example", "employee", null);

        // F4: a link between different addresses already; an unlinked employee with the account's own address
        tenant(tF4);
        f4Linked = employee(tF4, "other-address@f4.example");
        f4SameAddress = employee(tF4, "user@f4.example");
        f4User = user(tF4, "user@f4.example", "employee", f4Linked);

        // F5: U1 is linked to X and has Y's address; U2 has Y's address (case) and no link; U3 and Z match alone
        tenant(tF5);
        f5X = employee(tF5, "x@f5.example");
        f5Y = employee(tF5, "y@f5.example");
        f5Z = employee(tF5, "z@f5.example");
        f5U1 = user(tF5, "y@f5.example", "employee", f5X);
        f5U2 = user(tF5, "Y@f5.example", "employee", null);
        f5U3 = user(tF5, "z@f5.example", "employee", null);

        // F6: the same address in two companies, and an address whose only employee is in another company
        tenant(tF6a);
        tenant(tF6b);
        tenant(tF6c);
        f6EmployeeA = employee(tF6a, "same@f6.example");
        f6UserA = user(tF6a, "same@f6.example", "employee", null);
        f6EmployeeB = employee(tF6b, "same@f6.example");
        f6UserB = user(tF6b, "same@f6.example", "employee", null);
        employee(tF6a, "lonely@f6.example");
        f6Lonely = user(tF6c, "lonely@f6.example", "employee", null);

        // F7: case only, and surrounding spaces (stored as is; the application never trims what is stored)
        tenant(tF7);
        f7CaseEmployee = employee(tF7, "case@f7.example");
        f7Case = user(tF7, "CASE@F7.EXAMPLE", "employee", null);
        employee(tF7, "pad@f7.example");
        f7Padded = user(tF7, " pad@f7.example ", "employee", null);
        f7PaddedOwner = user(tF7, "owner@f7.example", "owner", null);
        registration(tF7, "kc-f7", "  owner@f7.example ");

        // F8: two accounts linked to one employee already (the schema allows it): kept, nothing added
        tenant(tF8);
        f8Shared = employee(tF8, "shared@f8.example");
        f8D1 = user(tF8, "d1@f8.example", "employee", f8Shared);
        f8D2 = user(tF8, "d2@f8.example", "employee", f8Shared);
        user(tF8, "shared@f8.example", "employee", null);

        // G: only a dismissed or an erased employee has the address
        tenant(tG);
        employee(tG, "gone@g.example", "2026-06-30", false);
        employee(tG, "Gone@g.example", null, true);
        gGoneUser = user(tG, "gone@g.example", "employee", null);

        // H: another company's employee and another company's invitation
        tenant(tH);
        tenant(tH2);
        employee(tH2, "cross@h.example");
        hUser = user(tH, "cross@h.example", "employee", null);
        hForeignInvitationUser = user(tH, "foreign-inv@h.example", "finance", null);
        invitation(tH2, hForeignInvitationUser, "foreign-inv@h.example", "kc-h-foreign", "sent");

        // I: one Keycloak id in the invitations of two accounts
        tenant(tI);
        iFirst = user(tI, "i1@i.example", "finance", null);
        iSecond = user(tI, "i2@i.example", "finance", null);
        invitation(tI, iFirst, "i1@i.example", "kc-i-shared", "sent");
        invitation(tI, iSecond, "i2@i.example", "kc-i-shared", "sent");

        // K: a signing key and signatures made before V47 (ADR 0068)
        tenant(tK);
        UUID kEmployee = employee(tK, "signer@k.example");
        kOwner = user(tK, "signer@k.example", "owner", kEmployee);
        su.sql("""
                insert into signing_key (tenant_id, email, method, pin_hash, failed, updated_at)
                values (?, 'signer@k.example', 'pin', '$2a$10$fictitiousfictitiousfictitiousfictitiousfictitiou', 2, ?)
                """).params(tK, at()).update();
        su.sql("""
                insert into signing_key (tenant_id, email, method, totp_secret, last_step, updated_at)
                values (?, 'totp@k.example', 'totp', decode('00112233', 'hex'), 59000000, ?)
                """).params(tK, at()).update();
        kDocument = document(tK, kEmployee);
        signature(tK, kDocument, "employee", "signer@k.example");
        signature(tK, kDocument, "employer", "boss@k.example");
        tenant(tK2);
        UUID k2Employee = employee(tK2, "k2@k2.example");
        k2Signer = user(tK2, "k2@k2.example", "employee", k2Employee);
        k2Document = document(tK2, k2Employee);

        for (String table : TABLES) {
            rowsBefore.put(table, count(table));
        }
        policiesBefore = policies();
        rlsBefore = rls();
        usersBefore = users();
        keysBefore = keys();
        signaturesBefore = signatures();

        v47 = flyway(database(db, "costguard"), "47").migrate();

        for (String table : TABLES) {
            rowsAfter.put(table, count(table));
        }
        usersAfter = users();
        keysAfter = keys();
        signaturesAfter = signatures();
        keysWithAccountAfter = su.sql("select count(*) from signing_key where user_account_id is not null")
                .query(Long.class).single();
        signaturesWithAccountAfter = su.sql("select count(*) from document_signature where signer_account_id is not null")
                .query(Long.class).single();
    }

    // ---------------------------------------------------------------- the run

    @Test
    void v47RanAloneAsTheOwnerAndSucceeded() {
        assertThat(v47.migrationsExecuted).isEqualTo(1);
        assertThat(v47.targetSchemaVersion).isEqualTo("47");
        Map<String, Object> history = su.sql("""
                select success, installed_by from flyway_schema_history where version = '47'""").query().singleRow();
        assertThat(history).containsEntry("success", true).containsEntry("installed_by", "costguard");
        assertThat(su.sql("select rolbypassrls or rolsuper from pg_roles where rolname = 'costguard'")
                .query(Boolean.class).single()).as("the owner is held by forced RLS").isFalse();
    }

    @Test
    void f8_runningFlywayAgainChangesNothing() {
        Map<UUID, Map<String, Object>> before = users();
        MigrateResult again = flyway(database(db, "costguard"), "47").migrate();
        assertThat(again.migrationsExecuted).isZero();
        flyway(database(db, "costguard"), "47").validate();
        assertThat(users()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- A, B, C: the Keycloak subject

    @Test
    void a_anInvitationThatWasNotRevokedGivesItsKeycloakAccount() {
        assertThat(subjectOf(aSent)).isEqualTo("kc-a-sent");
        assertThat(subjectOf(aAccepted)).isEqualTo("kc-a-accepted");
        assertThat(subjectOf(aTwoInvitations)).as("the newest invitation decides").isEqualTo("kc-a-two-new");
        assertThat(subjectOf(aTwinRows)).as("two newest rows, one Keycloak account").isEqualTo("kc-a-twin");
    }

    @Test
    void a_newestInvitationsThatDisagreeOrNameNoAccountLinkNothing() {
        assertThat(subjectOf(aTie)).as("two newest invitations, two Keycloak accounts: no choice by chance").isNull();
        assertThat(subjectOf(aEmptyId)).as("the newest invitation names no Keycloak account").isNull();
    }

    @Test
    void b_aRevokedInvitationLinksNothing() {
        assertThat(subjectOf(bOnlyRevoked)).isNull();
        // the newest invitation was revoked: the company withdrew the access, an older one does not bring it back
        assertThat(subjectOf(bRevokedNewest)).isNull();
        assertThat(su.sql("select count(*) from user_account where keycloak_subject in (?, ?, ?)")
                .params("kc-b-revoked", "kc-b-older-sent", "kc-b-newest-revoked").query(Long.class).single())
                .isZero();
    }

    @Test
    void c_theRegistrationGivesTheOwnerOfThatCompanyItsKeycloakAccount() {
        assertThat(subjectOf(cOwner)).as("same company, the owner, the address ignoring case")
                .isEqualTo("kc-c-owner");
        assertThat(subjectOf(cNotOwner)).as("not an owner").isNull();
        assertThat(subjectOf(cOwnerUpper)).as("two owners match the address").isNull();
        assertThat(subjectOf(cOwnerLower)).as("two owners match the address").isNull();
        assertThat(subjectOf(cInvitedOwner)).as("the invitation took the id first").isEqualTo("kc-c4");
        assertThat(subjectOf(cRegisteredOwner)).as("one Keycloak account, one CostGuard account").isNull();
        assertThat(subjectOf(f7PaddedOwner)).as("the registration's address has spaces: not the same").isNull();
    }

    // ---------------------------------------------------------------- D, E, F: the employee link

    @Test
    void d_f3_anAddressOfTwoWorkingEmployeesLinksNone() {
        assertThat(employeeOf(dUser)).isNull();
        assertThat(su.sql("select count(*) from user_account where employee_id in (?, ?)").params(dUpper, dLower)
                .query(Long.class).single()).isZero();
        // even when one of the two is linked elsewhere: the address still names two people
        assertThat(employeeOf(f3User)).isNull();
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f3Free)
                .query(Long.class).single()).isZero();
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f3Taken)
                .query(Long.class).single()).isEqualTo(1);
        // the exact same address twice in one company is refused by the schema itself
        assertThatThrownBy(() -> employee(tD, "dup@d.example")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void e_theOnlyWorkingEmployeeWithTheAddressIsLinked() {
        assertThat(employeeOf(eUser)).as("one working employee, the address ignoring case").isEqualTo(eEmployee);
        assertThat(employeeOf(eLinkedUser)).as("an account linked already keeps its link").isEqualTo(eOtherEmployee);
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(eLinkedEmployee)
                .query(Long.class).single()).isZero();
        assertThat(employeeOf(eDismissedUser)).as("the dismissed namesake does not count").isEqualTo(eWorking);
        assertThat(subjectOf(eUser)).as("an employee link gives no Keycloak account").isNull();
    }

    @Test
    void f1_anEmployeeLinkedToAnotherAccountStaysTheirs() {
        assertThat(employeeOf(f1A)).isEqualTo(f1B);
        assertThat(employeeOf(f1C)).isNull();
    }

    @Test
    void f2_twoAccountsWithTheAddressOfOneEmployeeLinkNone() {
        assertThat(employeeOf(f2Upper)).isNull();
        assertThat(employeeOf(f2Lower)).isNull();
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f2Employee)
                .query(Long.class).single()).isZero();
    }

    @Test
    void f4_anExistingLinkIsNotReplacedByAnAddress() {
        assertThat(employeeOf(f4User)).isEqualTo(f4Linked);
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f4SameAddress)
                .query(Long.class).single()).isZero();
    }

    @Test
    void f5_existingLinksComeFirstAndASharedAddressLinksNoOneElse() {
        assertThat(employeeOf(f5U1)).isEqualTo(f5X);
        // Y's address is on U1 and on U2: two accounts, so U2 does not get Y by it
        assertThat(employeeOf(f5U2)).isNull();
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f5Y)
                .query(Long.class).single()).isZero();
        // an unambiguous pair in the same company is still linked
        assertThat(employeeOf(f5U3)).isEqualTo(f5Z);
    }

    @Test
    void f6_theSameAddressInTwoCompaniesLinksEachToItsOwn() {
        assertThat(employeeOf(f6UserA)).isEqualTo(f6EmployeeA);
        assertThat(employeeOf(f6UserB)).isEqualTo(f6EmployeeB);
        assertThat(employeeOf(f6Lonely)).as("the only employee with the address is of another company").isNull();
    }

    @Test
    void f7_caseIsIgnoredSpacesAreNot() {
        assertThat(employeeOf(f7Case)).isEqualTo(f7CaseEmployee);
        assertThat(employeeOf(f7Padded)).as("stored with spaces: the application would not match it either").isNull();
    }

    @Test
    void f8_noEmployeeGainsASecondAccountAndExistingSharesStay() {
        assertThat(employeeOf(f8D1)).isEqualTo(f8Shared);
        assertThat(employeeOf(f8D2)).isEqualTo(f8Shared);
        // the account with the employee's own address does not become a third
        assertThat(su.sql("select count(*) from user_account where employee_id = ?").param(f8Shared)
                .query(Long.class).single()).isEqualTo(2);
        // no employee has more accounts after V47 than before it
        Map<Object, Long> before = new HashMap<>();
        usersBefore.values().stream().filter(u -> u.get("employee_id") != null)
                .forEach(u -> before.merge(u.get("employee_id"), 1L, Long::sum));
        Map<Object, Long> after = new HashMap<>();
        usersAfter.values().stream().filter(u -> u.get("employee_id") != null)
                .forEach(u -> after.merge(u.get("employee_id"), 1L, Long::sum));
        after.forEach((employee, n) -> assertThat(n).as("accounts of employee %s", employee)
                .isEqualTo(before.getOrDefault(employee, 0L) == 0 ? 1L : before.get(employee)));
    }

    // ---------------------------------------------------------------- G, H, I

    @Test
    void g_aDismissedOrErasedEmployeeIsNotLinked() {
        assertThat(employeeOf(gGoneUser)).isNull();
    }

    @Test
    void h_nothingCrossesCompanies() {
        assertThat(employeeOf(hUser)).as("an employee of another company").isNull();
        assertThat(subjectOf(hForeignInvitationUser)).as("an invitation of another company").isNull();
    }

    @Test
    void i_aKeycloakIdClaimedTwiceLinksNoneAndTheIndexKeepsItUnique() {
        assertThat(subjectOf(iFirst)).isNull();
        assertThat(subjectOf(iSecond)).isNull();
        su.sql("update user_account set keycloak_subject = 'kc-i-unique' where id = ?").param(iFirst).update();
        assertThatThrownBy(() -> su.sql("update user_account set keycloak_subject = 'kc-i-unique' where id = ?")
                .param(iSecond).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(subjectOf(iSecond)).isNull();
        assertThat(su.sql("select count(*) from user_account where keycloak_subject is null").query(Long.class)
                .single()).as("many accounts may still have none").isGreaterThan(1);
    }

    // ---------------------------------------------------------------- history of user_account

    @Test
    void noAccountChangesExceptAnEmptyEmployeeLinkFilledAndNoneIsLost() {
        assertThat(usersAfter.keySet()).isEqualTo(usersBefore.keySet());
        usersBefore.forEach((id, before) -> {
            Map<String, Object> after = usersAfter.get(id);
            for (String column : List.of("tenant_id", "name", "email", "role")) {
                assertThat(after.get(column)).as("%s of %s", column, id).isEqualTo(before.get(column));
            }
            if (before.get("employee_id") != null) {
                assertThat(after.get("employee_id")).as("the link of %s was replaced", id)
                        .isEqualTo(before.get("employee_id"));
            }
        });
        // every subject V47 wrote is one the core had written for that company
        List<Map<String, Object>> subjects = su.sql("""
                select u.tenant_id, u.keycloak_subject from user_account u where u.keycloak_subject is not null""")
                .query().listOfRows();
        for (Map<String, Object> s : subjects) {
            if ("kc-i-unique".equals(s.get("keycloak_subject"))) {
                continue; // written by scenario I after V47
            }
            long known = su.sql("""
                    select (select count(*) from invitation where tenant_id = ? and keycloak_user_id = ?)
                         + (select count(*) from registration where tenant_id = ? and keycloak_user_id = ?)""")
                    .params(s.get("tenant_id"), s.get("keycloak_subject"), s.get("tenant_id"), s.get("keycloak_subject"))
                    .query(Long.class).single();
            assertThat(known).as("subject %s", s.get("keycloak_subject")).isPositive();
        }
    }

    // ---------------------------------------------------------------- K: signing keys

    @Test
    void k_oldSigningKeysStayUnchangedAndBelongToNoAccount() {
        assertThat(keysAfter).isEqualTo(keysBefore);
        assertThat(keysWithAccountAfter).as("not moved to an account: who set them cannot be proven").isZero();
    }

    @Test
    void k_aKeyOfAnAccountCanBeMadeOnlyOnceAndOnlyForAnAccountThatExists() {
        // the old key does not stand in the way, nor does a second orphan
        su.sql("""
                insert into signing_key (tenant_id, user_account_id, email, method, pin_hash, updated_at)
                values (?, ?, 'signer@k.example', 'pin', 'x', now())""").params(tK, kOwner).update();
        su.sql("""
                insert into signing_key (tenant_id, email, method, pin_hash, updated_at)
                values (?, 'signer@k.example', 'pin', 'y', now())""").param(tK).update();
        assertThatThrownBy(() -> su.sql("""
                insert into signing_key (tenant_id, user_account_id, email, method, pin_hash, updated_at)
                values (?, ?, 'other@k.example', 'pin', 'z', now())""").params(tK, kOwner).update())
                .as("one key per account").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> su.sql("""
                insert into signing_key (tenant_id, user_account_id, email, method, pin_hash, updated_at)
                values (?, ?, 'ghost@k.example', 'pin', 'z', now())""").params(tK, UUID.randomUUID()).update())
                .as("no key of an account that does not exist").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- K: signatures

    @Test
    void k_oldSignaturesStayUnchangedAndNameNoAccount() {
        assertThat(signaturesAfter).isEqualTo(signaturesBefore);
        assertThat(signaturesWithAccountAfter).isZero();
    }

    @Test
    void k_aNewSignatureNamesAnExistingAccountAndOutlivesIt() {
        UUID signature = UUID.randomUUID();
        su.sql("""
                insert into document_signature (id, tenant_id, document_id, party, signer_name, signer_email, method,
                                                document_sha256, signed_at, signer_account_id)
                values (?, ?, ?, 'employee', 'Подписант', 'k2@k2.example', 'pin', 'cd34', now(), ?)
                """).params(signature, tK2, k2Document, k2Signer).update();
        assertThat(su.sql("select signer_account_id from document_signature where id = ?").param(signature)
                .query(UUID.class).single()).isEqualTo(k2Signer);
        assertThatThrownBy(() -> su.sql("""
                insert into document_signature (id, tenant_id, document_id, party, signer_name, signer_email, method,
                                                document_sha256, signed_at, signer_account_id)
                values (?, ?, ?, 'employer', 'Призрак', 'ghost@k2.example', 'pin', 'cd34', now(), ?)
                """).params(UUID.randomUUID(), tK2, k2Document, UUID.randomUUID()).update())
                .as("a signature names an account that exists").isInstanceOf(DataIntegrityViolationException.class);
        // the account goes (an erasure): the signature stays, a legal record, without the account
        su.sql("delete from user_account where id = ?").param(k2Signer).update();
        Map<String, Object> kept = su.sql("""
                select signer_account_id, signer_email from document_signature where id = ?""").param(signature)
                .query().singleRow();
        assertThat(kept.get("signer_account_id")).isNull();
        assertThat(kept).containsEntry("signer_email", "k2@k2.example");
    }

    // ---------------------------------------------------------------- J: RLS

    @Test
    void j_rowsFlagsAndPoliciesOfEveryTouchedTableAreAsBefore() {
        assertThat(rowsAfter).isEqualTo(rowsBefore);
        assertThat(rowsBefore.get("user_account")).isGreaterThan(40);
        assertThat(rls()).isEqualTo(rlsBefore);
        assertThat(rls()).allSatisfy(r -> assertThat(r).containsEntry("relrowsecurity", true)
                .containsEntry("relforcerowsecurity", true));
        assertThat(policies()).isEqualTo(policiesBefore);
        assertThat(policiesBefore).extracting(p -> p.get("tablename") + "." + p.get("policyname"))
                .contains("user_account.tenant_isolation", "signing_key.tenant_isolation",
                        "document_signature.tenant_isolation", "invitation.tenant_isolation",
                        "registration.tenant_isolation", "employee.tenant_isolation");
    }

    @Test
    void j_theApplicationRoleSeesNothingUnboundAndOnlyItsCompanyBound() {
        var connection = new SingleConnectionDataSource(jdbcUrl().replaceAll("/[^/]+$", "/" + db), "costguard", "",
                true);
        try {
            JdbcClient app = JdbcClient.create(connection);
            for (String table : TABLES) {
                app.sql("select set_config('app.tenant_id', '', false)").query(String.class).single();
                assertThat(app.sql("select count(*) from " + table).query(Long.class).single())
                        .as("%s without a tenant", table).isZero();
                List<Map<String, Object>> perTenant = su.sql("select tenant_id, count(*) as n from " + table
                        + " group by tenant_id").query().listOfRows();
                assertThat(perTenant).as(table).isNotEmpty();
                for (Map<String, Object> row : perTenant) {
                    app.sql("select set_config('app.tenant_id', ?, false)").param(row.get("tenant_id").toString())
                            .query(String.class).single();
                    List<UUID> seen = app.sql("select tenant_id from " + table).query(UUID.class).list();
                    assertThat(seen).as("%s bound to %s", table, row.get("tenant_id")).hasSize(
                            ((Number) row.get("n")).intValue()).containsOnly((UUID) row.get("tenant_id"));
                }
            }
        } finally {
            connection.destroy();
        }
    }

    // ---------------------------------------------------------------- an error in the middle of V47

    /**
     * V47 takes FORCE off four tables, updates them and puts it back. If it fails in between, FORCE must not stay off:
     * PostgreSQL runs DDL in the transaction, and Flyway runs each PostgreSQL migration in one. A trigger makes the
     * first update of V47 fail, after the FORCE is off; afterwards the database is exactly as before V47.
     */
    @Test
    void anErrorInTheMiddleOfV47LeavesTheDatabaseAsBeforeWithForcedRls() {
        String broken = createDatabase("v47err");
        flyway(database(broken, "costguard"), "46").migrate();
        JdbcClient admin = JdbcClient.create(database(broken, "postgres"));
        UUID tenant = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        admin.sql("insert into tenant (id, name) values (?, 'ООО Сбой')").param(tenant).update();
        admin.sql("insert into user_account (id, tenant_id, name, email, role) values (?, ?, 'Сбой', ?, 'finance')")
                .params(userId, tenant, "err@err.example").update();
        admin.sql("""
                insert into invitation (id, tenant_id, user_account_id, email, role, keycloak_user_id, status,
                                        created_at)
                values (?, ?, ?, 'err@err.example', 'finance', 'kc-err', 'sent', now())""")
                .params(UUID.randomUUID(), tenant, userId).update();
        admin.sql("""
                create function v47_test_boom() returns trigger language plpgsql as $$
                begin raise exception 'simulated failure inside V47'; end $$""").update();
        admin.sql("create trigger v47_test_boom before update on user_account for each row "
                + "execute function v47_test_boom()").update();
        List<Map<String, Object>> policiesBeforeError = admin.sql("""
                select tablename, policyname, permissive, cmd, roles::text as roles, qual, with_check
                from pg_policies where schemaname = 'public' order by tablename, policyname""").query().listOfRows();

        assertThatThrownBy(() -> flyway(database(broken, "costguard"), "47").migrate())
                .isInstanceOf(FlywayException.class).hasStackTraceContaining("simulated failure inside V47");

        for (String table : TOGGLED) {
            assertThat(admin.sql("select relforcerowsecurity from pg_class where relname = ? "
                            + "and relnamespace = 'public'::regnamespace").param(table).query(Boolean.class).single())
                    .as("FORCE of %s after the failure", table).isTrue();
        }
        assertThat(admin.sql("""
                select count(*) from information_schema.columns
                where table_name in ('user_account', 'signing_key', 'document_signature')
                  and column_name in ('keycloak_subject', 'user_account_id', 'signer_account_id')""")
                .query(Long.class).single()).as("no column of V47 is left").isZero();
        assertThat(admin.sql("select count(*) from pg_constraint where conname = 'signing_key_pkey'")
                .query(Long.class).single()).as("the old key of signing_key is still there").isEqualTo(1);
        assertThat(admin.sql("select count(*) from flyway_schema_history where version = '47' and success")
                .query(Long.class).single()).isZero();
        assertThat(admin.sql("select max(version::int) from flyway_schema_history where success").query(Integer.class)
                .single()).isEqualTo(46);
        assertThat(admin.sql("""
                select tablename, policyname, permissive, cmd, roles::text as roles, qual, with_check
                from pg_policies where schemaname = 'public' order by tablename, policyname""").query().listOfRows())
                .isEqualTo(policiesBeforeError);

        // the cause removed, V47 runs on the same database
        admin.sql("drop trigger v47_test_boom on user_account").update();
        MigrateResult retried = flyway(database(broken, "costguard"), "47").migrate();
        assertThat(retried.migrationsExecuted).isEqualTo(1);
        assertThat(admin.sql("select keycloak_subject from user_account where id = ?").param(userId)
                .query(String.class).single()).isEqualTo("kc-err");
    }
}
