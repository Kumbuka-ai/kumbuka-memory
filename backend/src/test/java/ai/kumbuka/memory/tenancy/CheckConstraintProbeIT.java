package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The nine check constraints of this schema, each seen refusing something.
 *
 * <h2>Why a constraint needs a probe at all</h2>
 *
 * Eight of these are the form of an entry and one is the form of a relation.
 * Every one of them also has, or will have, a counterpart in application code
 * — a validator on the write path. The counterpart is where a caller gets a
 * good error message; the constraint is what holds when a statement reaches
 * the database without passing through it, which is precisely the case the
 * validator cannot cover and therefore precisely the case worth testing.
 *
 * <p>In the schema this model comes from, the reference check is the sharpest
 * example: the Java validator in front of it is tested and the constraint
 * behind it is named by no test at all. So the guarantee "a stored reference
 * carries no credential" rests, in the only place it has to, on something
 * nobody has watched work.
 *
 * <p>Each case below writes directly with SQL — around the ORM and around any
 * validator — and requires the refusal to name its constraint. A refusal that
 * does not name it would leave the next reader unable to tell which rule
 * fired.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CheckConstraintProbeIT {

    /** Every check constraint this schema is supposed to carry. */
    private static final List<String> ENTRY_CHECKS = List.of(
        "memory_content_len",
        "memory_key_format",
        "memory_lock_check",
        "memory_reference_no_credentials",
        "memory_source_check",
        "memory_state_check",
        "memory_type_check",
        "memory_updated_source_check");

    private static final String RELATION_CHECK = "content_relation_kind_check";

    private UUID tenant;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
    }

    /**
     * The catalogue, first: the constraints exist and carry the names the
     * model gives them.
     *
     * <p>Names are part of the contract rather than decoration — a refusal
     * identifies itself by its constraint name, and a rule that was
     * re-created under an auto-generated name is a rule whose error message
     * stopped meaning anything.
     */
    @Test
    void the_schema_carries_all_nine_checks_under_their_own_names() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(checkNames(c, "memory"))
                .as("the eight entry checks are the form of an entry, and each one is "
                    + "carried by name")
                .containsExactlyInAnyOrderElementsOf(ENTRY_CHECKS);

            assertThat(checkNames(c, "content_relation"))
                .as("and the relation carries the one that constrains its kind")
                .containsExactly(RELATION_CHECK);
        }
    }

    @Test
    void content_longer_than_the_limit_is_refused() throws SQLException {
        assertRefusedBy("memory_content_len",
            entry -> entry.content = "x".repeat(1501));
    }

    @Test
    void a_key_that_is_not_lower_case_kebab_is_refused() throws SQLException {
        assertRefusedBy("memory_key_format",
            entry -> entry.key = "Not A Key");
    }

    @Test
    void an_unknown_lock_value_is_refused() throws SQLException {
        assertRefusedBy("memory_lock_check",
            entry -> entry.lock = "padlock");
    }

    /**
     * The reference check, at the database, reached by SQL that never passed
     * the application's validator.
     *
     * <p>Two shapes are tried, because the constraint is two expressions: a
     * credential in the authority, and a secret in a query parameter. A probe
     * that only tried the first would be green against a constraint that had
     * lost the second half.
     */
    @Test
    void a_credential_bearing_reference_is_refused_at_the_database() throws SQLException {
        assertRefusedBy("memory_reference_no_credentials",
            entry -> entry.reference = "https://someone:hunter2@example.org/a-document");

        assertRefusedBy("memory_reference_no_credentials",
            entry -> entry.reference = "https://example.org/a-document?access_token=abcdef");

        // And the green half: an ordinary URL is stored, so the refusals above
        // are the credential and not the column.
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            Db.insertEntry(c, tenant, key("plain-reference"), "https://example.org/a-document");
            c.commit();
            assertThat(Db.countEntriesKeyed(c, key("plain-reference")))
                .as("a reference without a credential is admitted — otherwise the check "
                    + "would be refusing the field rather than the credential")
                .isEqualTo(1);
        }
    }

    @Test
    void an_unknown_write_channel_is_refused() throws SQLException {
        assertRefusedBy("memory_source_check",
            entry -> entry.source = "carrier-pigeon");
    }

    @Test
    void an_unknown_state_is_refused() throws SQLException {
        assertRefusedBy("memory_state_check",
            entry -> entry.state = "ambiguous");
    }

    @Test
    void an_unknown_type_is_refused() throws SQLException {
        assertRefusedBy("memory_type_check",
            entry -> entry.type = "anecdote");
    }

    @Test
    void an_unknown_edit_channel_is_refused() throws SQLException {
        assertRefusedBy("memory_updated_source_check",
            entry -> entry.updatedSource = "carrier-pigeon");
    }

    @Test
    void an_unknown_relation_kind_is_refused() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID a = Db.insertEntry(c, tenant, key("kind-a"));
            UUID b = Db.insertEntry(c, tenant, key("kind-b"));
            c.commit();

            try {
                Db.insertRelation(c, tenant, a, b, "implies");
                fail("a relation of an unknown kind was admitted");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains(RELATION_CHECK);
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * The red state, for the check that has never had a gate: with
     * {@code memory_reference_no_credentials} dropped, the same
     * credential-bearing URL is stored without complaint.
     *
     * <p>Dropping a constraint requires ownership, so it happens on the
     * migrator's connection — and the whole case runs inside one transaction
     * that is rolled back. PostgreSQL applies DDL transactionally, so the
     * rollback restores the constraint AND removes the row that violates it,
     * in one step and without a repair sequence that could itself fail.
     */
    @Test
    void with_the_reference_check_dropped_the_credential_is_stored() throws SQLException {
        String credentialUrl = "https://someone:hunter2@example.org/a-document";

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, tenant);
            try {
                Db.exec(owner, "ALTER TABLE " + Db.ENTRY_TABLE
                    + " DROP CONSTRAINT memory_reference_no_credentials");

                Db.insertEntry(owner, tenant, key("red"), credentialUrl);

                try (PreparedStatement st = owner.prepareStatement(
                        "SELECT reference FROM " + Db.ENTRY_TABLE + " WHERE key = ?")) {
                    st.setString(1, key("red"));
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1))
                            .as("RED STATE, observed: with the constraint gone the "
                                + "credential-bearing URL is stored verbatim, in a shared "
                                + "row, where the application's validator was never going "
                                + "to see it. So the refusals above are this constraint's "
                                + "work and not the URL being malformed")
                            .isEqualTo(credentialUrl);
                    }
                }
            } finally {
                // Restores the constraint and removes the offending row in one
                // step. A repair sequence would have to do both in the right
                // order and could fail halfway.
                owner.rollback();
            }
        }

        try (Connection c = Db.asAdmin()) {
            assertThat(checkNames(c, "memory"))
                .as("and restored, so the red state was the dropped constraint and "
                    + "nothing else")
                .contains("memory_reference_no_credentials");
        }
    }

    // ------------------------------------------------------------------
    // Writing one deliberately malformed entry.
    // ------------------------------------------------------------------

    /** The column values of one entry, so a case can spoil exactly one of them. */
    private static final class Entry {
        String type = "decision";
        String key;
        String content = "probe content";
        String reference;
        String state = "published";
        String lock = "none";
        String source = "mcp";
        String updatedSource;
    }

    private interface Spoiler {
        void spoil(Entry entry);
    }

    /**
     * Writes an entry with exactly one field spoiled and requires the named
     * constraint to refuse it.
     *
     * <p>One field at a time is the discipline that makes the assertion mean
     * something: with two spoiled, whichever constraint fires first would
     * satisfy the test and the other would go unobserved.
     */
    private void assertRefusedBy(String constraint, Spoiler spoiler) throws SQLException {
        Entry entry = new Entry();
        entry.key = key(constraint);
        spoiler.spoil(entry);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            try {
                insert(c, entry);
                fail("the row was admitted; " + constraint + " did not fire");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal must name %s — a violation that does not say which "
                        + "rule it broke is one the next reader has to go and look up",
                        constraint)
                    .contains(constraint);
            } finally {
                c.rollback();
            }
        }
    }

    private void insert(Connection c, Entry entry) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO memory.memory
                    (tenant_id, scope_id, is_private, owner_subject,
                     type, key, content, reference, state, lock, source, updated_source)
                VALUES (?::uuid, ?::uuid, false, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, SubstrateDatabaseResource.SCOPE_ID);
            st.setString(3, SubstrateDatabaseResource.PROBE_SUBJECT);
            st.setString(4, entry.type);
            st.setString(5, entry.key);
            st.setString(6, entry.content);
            st.setString(7, entry.reference);
            st.setString(8, entry.state);
            st.setString(9, entry.lock);
            st.setString(10, entry.source);
            st.setString(11, entry.updatedSource);
            st.execute();
        }
    }

    private static List<String> checkNames(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT con.conname
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = 'memory' AND cl.relname = ? AND con.contype = 'c'
                ORDER BY con.conname
                """)) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private String key(String suffix) {
        // Lower-cased and hyphenated, because the key format check is one of
        // the rules under test and a probe key that violated it would make
        // every other case fail for the wrong reason.
        return "check-" + suffix.replace('_', '-') + "-" + tenant;
    }
}
