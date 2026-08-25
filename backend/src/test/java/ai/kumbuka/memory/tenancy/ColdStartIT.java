package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cold start: an empty database, and afterwards a service that runs.
 *
 * <p>This is the first acceptance criterion and it is also the one that is
 * easiest to fake. A suite that stages the schema and then asserts the schema
 * is there proves that its own setup ran. So nothing here is staged: the
 * container arrives with a migrating role, the provider's roles, the other
 * services' roles and the tenancy anchor, and everything asserted below was
 * created by the migration set during boot, in the order the service will do
 * it in production.
 *
 * <p>The role attributes are asserted, not assumed. Superuser or BYPASSRLS on
 * the service role would evaporate every policy in this schema silently — no
 * error, rows returned, and the rest of the suite green. They are two boolean
 * columns in the catalogue and there is no reason to learn about them from an
 * incident instead. The migrator's attributes are asserted for the same
 * reason and a second one: the chain is required to run UNDER AN UNPRIVILEGED
 * MIGRATOR, and a suite that migrated as a superuser would prove nothing
 * about the deployment that does not.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ColdStartIT {

    @Inject EntityManager em;

    /**
     * Every object the first acceptance criterion names: the schema, both
     * tables, both policies, the trigger and the function behind it.
     */
    @Test
    void flyway_creates_the_schema_its_two_tables_both_policies_and_the_guard()
            throws SQLException, IOException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM information_schema.schemata "
                + "WHERE schema_name = 'memory'"))
                .as("the service's named schema must exist after boot")
                .isEqualTo("1");

            assertThat(list(c, "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'memory' AND table_type = 'BASE TABLE' "
                + "AND table_name <> 'flyway_schema_history' ORDER BY table_name"))
                .as("V1 must have created both tables of the model")
                .containsExactly("content_relation", "memory");

            assertThat(list(c, "SELECT polname FROM pg_policy p "
                + "JOIN pg_class cl ON cl.oid = p.polrelid "
                + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                + "WHERE n.nspname = 'memory' ORDER BY polname"))
                .as("V3 must have created one tenant policy per table — a table with "
                    + "row-level security enabled and no policy is closed rather than "
                    + "isolated, which is a service that cannot run")
                .containsExactly("content_relation_tenant_isolation", "memory_tenant_isolation");

            assertThat(scalar(c, "SELECT count(*) FROM pg_trigger t "
                + "JOIN pg_class cl ON cl.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                + "WHERE n.nspname = 'memory' AND t.tgname = 'content_relation_acyclicity' "
                + "AND NOT t.tgisinternal"))
                .as("the acyclicity trigger must be attached")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT count(*) FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'memory' "
                + "AND p.proname = 'content_relation_acyclic_supersedes'"))
                .as("and the function behind it must live in this schema rather than "
                    + "being left behind in another")
                .isEqualTo("1");

            // The expectation is counted from the migration directory rather
            // than written here as a number. A literal would have to be
            // maintained alongside every new migration, and the failure when
            // somebody forgets reads as "a migration did not apply" — which
            // sends the next reader looking at the database instead of at
            // this line. The two sources are genuinely different: one is the
            // files on disk, the other is what the database recorded running.
            long expected = countVersionedMigrationFiles();
            assertThat(expected)
                .as("the migration directory must have been found at all")
                .isPositive();

            // Only the versioned rows are counted. Flyway records its own
            // schema creation as an unversioned entry, and counting that as a
            // migration would make the assertion drift with the tool rather
            // than with the migration set.
            assertThat(scalar(c, "SELECT count(*) FROM memory.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("every versioned migration on disk must have applied successfully")
                .isEqualTo(String.valueOf(expected));

            assertThat(scalar(c, "SELECT max(version::int) FROM memory.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("and the schema must stand at the highest of them")
                .isEqualTo(String.valueOf(expected));
        }
    }

    /**
     * The other half of the first criterion: the application is up, and its
     * schema validation passed.
     *
     * <p>Hibernate runs {@code validate} at boot and refuses to start when a
     * mapped column is missing or carries another type. That this test class
     * is running at all is therefore already the evidence — but "already the
     * evidence" is the kind of claim that disappears the moment somebody
     * switches a setting. So the mapping is exercised: a query built by
     * Hibernate over each entity, which can only be built against the mapping
     * the validator accepted.
     */
    @Test
    void the_application_started_and_its_mapping_matches_the_schema() {
        assertThat(em.createQuery("SELECT count(m) FROM Memory m", Long.class).getSingleResult())
            .as("a query over the entry entity must be answerable — with the ORM's own "
                + "mapping, against the migrated schema")
            .isNotNegative();
        assertThat(em.createQuery("SELECT count(r) FROM ContentRelation r", Long.class)
                .getSingleResult())
            .as("and over the relation entity, which is the second half of the model")
            .isNotNegative();
    }

    /**
     * The chain ran under the unprivileged migrator.
     *
     * <p>Read from the history table, which records the role each migration
     * was installed by, rather than from the test's own configuration. The
     * configuration says which role the suite INTENDED to migrate as; this
     * says which one the database saw.
     */
    @Test
    void the_chain_ran_under_the_unprivileged_migrator() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, """
                SELECT coalesce(string_agg(DISTINCT installed_by, ', '), '')
                FROM memory.flyway_schema_history WHERE version IS NOT NULL
                """))
                .as("every versioned migration must have been installed by the migrating "
                    + "role and by nothing else")
                .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);

            assertThat(scalar(c, "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("a superuser migrator would additionally carry BYPASSRLS, and its own "
                    + "DML could then never be observed failing when it forgets the "
                    + "tenant binding")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolbypassrls FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("and BYPASSRLS directly, which is the attribute that actually does it")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolcreaterole FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("CREATEROLE is the one privileged thing the migration set needs, and "
                    + "asserting it is what makes the two absences above a deliberate "
                    + "shape rather than an unprivileged role that happens to work")
                .isEqualTo("t");
        }
    }

    /** The second acceptance criterion, against {@code pg_roles}. */
    @Test
    void the_migration_creates_the_service_role_with_the_attributes_that_matter()
            throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("V2 must create the service role against an empty database, so that a "
                    + "cold start needs no manual step")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("a superuser bypasses row-level security unconditionally: the policies "
                    + "in V3 would exist and do nothing")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolbypassrls FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("BYPASSRLS is the same evaporation by a different attribute")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolcreaterole FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("and the runtime role creates no roles — the one privileged act in "
                    + "this repository belongs to the migrator alone")
                .isEqualTo("f");
        }
    }

    /**
     * The third acceptance criterion: ownership, of everything.
     *
     * <p>An owner can drop a policy and disable row-level security, and
     * {@code FORCE} does not stop it — it only subjects the owner to the
     * policies while they exist. So the question of who owns these two tables
     * is the question of whether the runtime role can switch off its own
     * isolation, and the answer has to be read from the catalogue rather than
     * inferred from the absence of a callback.
     *
     * <p>Indexes and the function are asked about separately because they are
     * different catalogue objects: an index is a relation and shows up in
     * {@code pg_class}, a function does not.
     */
    @Test
    void the_migrator_owns_the_schema_every_relation_in_it_and_the_function()
            throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, """
                SELECT coalesce(string_agg(c.relname || ':' || pg_get_userbyid(c.relowner)
                                           || ':' || c.relkind::text, ', '), '')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'memory'
                  AND c.relkind IN ('r','v','m','S','p','i','I')
                  AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(SubstrateDatabaseResource.MIGRATOR_ROLE)))
                .as("the migrator creates and keeps every relation in this schema — both "
                    + "tables, every index, and the Flyway history table. An object handed "
                    + "to the runtime role would hand it that object's whole privilege set "
                    + "with no grant anywhere to show for it, and on these two tables it "
                    + "would also hand it the DDL that removes the policies")
                .isEmpty();

            assertThat(scalar(c, "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'memory'"))
                .as("the schema itself is an owned object too, and the owner is what "
                    + "decides whether the runtime role can add a table to its own "
                    + "entitlement")
                .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);

            assertThat(scalar(c, "SELECT pg_get_userbyid(p.proowner) FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'memory' "
                + "AND p.proname = 'content_relation_acyclic_supersedes'"))
                .as("and so is the function behind the acyclicity trigger, which a "
                    + "relation-only query would have missed entirely")
                .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);
        }
    }

    /**
     * The fifth acceptance criterion: no PUBLIC anywhere in the schema's own
     * access list.
     *
     * <p>A schema PostgreSQL created and nobody touched carries a null ACL,
     * which reads as "the default" — and the default for a schema is that its
     * owner holds everything and PUBLIC holds nothing. V1 revokes from PUBLIC
     * explicitly anyway, which materialises the ACL. Either shape is correct
     * and the assertion is written to accept both: what must not appear is an
     * entry granting PUBLIC something.
     */
    @Test
    void the_schema_grants_nothing_to_public() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            // An access-list item reads `grantee=privileges/grantor`, and the
            // grantee of a PUBLIC grant is the empty string — so a PUBLIC item
            // is one whose text begins with '='. Searching the whole list for
            // "=U" instead would match every named role's USAGE grant too, which
            // is an assertion that can only ever fail.
            assertThat(scalar(c, "SELECT coalesce(string_agg(acl::text, ', '), '') "
                + "FROM pg_namespace n, unnest(n.nspacl) acl "
                + "WHERE n.nspname = 'memory' AND acl::text LIKE '=%'"))
                .as("no entry for PUBLIC — PUBLIC is every role in the cluster, including "
                    + "ones created next year, so a privilege granted to it is one this "
                    + "schema can never take back by naming somebody")
                .isEmpty();

            // And the list is not empty, which is what makes the absence above
            // an absence rather than an unread column: a schema nobody ever
            // granted anything on carries a null access list and would satisfy
            // the assertion above without it meaning anything.
            assertThat(scalar(c, "SELECT coalesce(string_agg(acl::text, ', '), '') "
                + "FROM pg_namespace n, unnest(n.nspacl) acl WHERE n.nspname = 'memory'"))
                .as("the access list must actually carry the two entries this chain "
                    + "writes — the migrator as owner, and the runtime role's USAGE")
                .contains(SubstrateDatabaseResource.MIGRATOR_ROLE + "=")
                .contains(SubstrateDatabaseResource.SERVICE_ROLE + "=U");
        }
    }

    @Test
    void the_service_reaches_its_own_tables_through_the_grants_it_was_given()
            throws SQLException {
        try (Connection c = Db.asService()) {
            // Not as owner — as grantee. V2 names the privileges one at a time.
            assertThat(Db.countEntries(c))
                .as("the service role must reach its own table")
                .isNotNegative();
        }
    }

    /**
     * Counts {@code V<n>__*.sql} files in the migration directory.
     *
     * <p>Deliberately not a constant: this is the one number in the test that
     * would otherwise need editing every time a migration is added, and the
     * edit that gets forgotten produces a failure describing the wrong thing.
     */
    private static long countVersionedMigrationFiles() throws IOException {
        Path dir = Files.isDirectory(Paths.get("src/main/resources/db/migration"))
            ? Paths.get("src/main/resources/db/migration")
            : Paths.get("backend/src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                .filter(n -> n.matches("V\\d+__.*\\.sql"))
                .count();
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> list(Connection c, String sql) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
