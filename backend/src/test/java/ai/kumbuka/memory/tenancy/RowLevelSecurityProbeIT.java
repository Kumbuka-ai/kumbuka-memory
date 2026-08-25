package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation, with the red state observed rather than described.
 *
 * <p>Each test below asserts the guarantee AND then removes the line the
 * guarantee rests on and watches it break, in the same run, on the same
 * connection, before putting the line back. That is the difference between a
 * gate and a comment. A test that only asserts the green half passes
 * identically on a schema where the policy was silently dropped — which is
 * the failure mode this whole arrangement exists to catch, so a probe blind
 * to it is worth very little.
 *
 * <h2>Where ENABLE and FORCE each do their work here</h2>
 *
 * The runtime role owns nothing (V2), so {@code ENABLE} already binds it and
 * removing {@code FORCE} changes nothing about what it sees. The two halves
 * therefore separate, and both are probed:
 *
 * <ul>
 *   <li>the RUNTIME role's isolation rests on the POLICY and on row-level
 *       security being switched on at all, and the two behave OPPOSITELY when
 *       taken away — dropping the policy closes the table, disabling
 *       row-level security opens it. Both are watched;</li>
 *   <li>{@code FORCE} is still load-bearing, for the MIGRATOR, which owns
 *       these tables and is the role every future migration carrying DML runs
 *       as. Removing it is watched there, where it actually does something.</li>
 * </ul>
 *
 * <p>The removals are made against the running database and undone in a
 * {@code finally}, rather than as a throwaway migration. The effect is the
 * same and the observation is stronger: a throwaway migration is run once by
 * whoever wrote it, and this runs on every build.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RowLevelSecurityProbeIT {

    private static final String POLICY = "memory_tenant_isolation";

    /**
     * A fresh pair of tenants per test method.
     *
     * <p>The tests share one database, and row-level security counts every row
     * a tenant owns — including rows an earlier test planted. Fixed tenant ids
     * would make each assertion depend on which tests ran before it, so the
     * suite would pass or fail by execution order and the failure would look
     * like a broken policy. Fresh ids make each test's arithmetic its own.
     */
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    /**
     * The first probe: a read under one tenant does not see another tenant's
     * entry — and not merely hidden from a listing, absent from a count, which
     * is the form that cannot be papered over by a presentation layer.
     */
    @Test
    void a_read_under_one_tenant_does_not_see_another_tenants_entry() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertEntry(c, tenantA, keyFor(tenantA));
            Db.bindTenant(c, tenantB);
            Db.insertEntry(c, tenantB, keyFor(tenantB));
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThat(Db.countEntries(c))
                .as("a session bound to tenant A must see A's entry and only A's")
                .isEqualTo(1);

            Db.bindTenant(c, tenantB);
            assertThat(Db.countEntries(c))
                .as("and symmetrically for B — otherwise the filter is not a filter but "
                    + "a coincidence about which rows happen to exist")
                .isEqualTo(1);
        }
    }

    /**
     * The two ways this table's isolation can be taken away, and they behave
     * oppositely.
     *
     * <p>Both halves of the green state are asserted first, and the second one
     * is the half that is usually left out. Before anything is touched, the
     * foreign tenant's entry is counted UNDER ITS OWN BINDING and must be
     * there: a probe that is green because the table is empty proves nothing
     * at all, and "tenant A sees zero rows of tenant B" is satisfied perfectly
     * by a tenant B that has no rows.
     *
     * <h2>Dropping the policy fails CLOSED</h2>
     *
     * With row-level security enabled and no policy present, PostgreSQL
     * applies a default-deny: the table returns nothing to a non-owner, not
     * everything. So a schema that loses its policy loses ACCESS rather than
     * isolation — the service stops working instead of leaking, which is the
     * direction one wants a mistake to go in and is not what one would guess.
     *
     * <h2>Disabling row-level security fails OPEN</h2>
     *
     * That is the collapse. {@code DISABLE ROW LEVEL SECURITY} leaves the
     * policy sitting in the catalogue, readable, apparently correct, and
     * applied to nobody. The foreign entry appears with no error and no
     * warning.
     */
    @Test
    void the_policy_admits_the_rows_and_switching_rls_off_admits_everybody_elses()
            throws SQLException {
        String ownKey = keyFor(tenantA);
        String foreignKey = keyFor(tenantB);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertEntry(c, tenantA, ownKey);
            Db.bindTenant(c, tenantB);
            Db.insertEntry(c, tenantB, foreignKey);
            c.commit();

            // The half that makes every other assertion mean something: the
            // foreign row EXISTS, counted under the tenant that owns it.
            Db.bindTenant(c, tenantB);
            assertThat(Db.countEntriesKeyed(c, foreignKey))
                .as("green run, and this is the count the whole probe rests on: tenant B's "
                    + "entry is really in the table. Without it the assertions below would "
                    + "be satisfied by an empty table")
                .isEqualTo(1);

            Db.bindTenant(c, tenantA);
            assertThat(Db.countEntriesKeyed(c, ownKey))
                .as("green state: this tenant sees its own entry")
                .isEqualTo(1);
            assertThat(Db.countEntriesKeyed(c, foreignKey))
                .as("green state: and not the other tenant's")
                .isZero();

            // Every lock this connection holds is released BEFORE the owner
            // asks for an ACCESS EXCLUSIVE one. The reads above left an open
            // transaction with a share lock on the table; without this commit
            // the DDL below waits for it, forever, and a hung build is much
            // worse than a failed one.
            c.commit();

            try {
                asOwner("DROP POLICY " + POLICY + " ON " + Db.ENTRY_TABLE);

                Db.bindTenant(c, tenantA);
                assertThat(Db.countEntriesKeyed(c, ownKey))
                    .as("RED STATE, observed: with the policy gone the table is CLOSED, not "
                        + "open — row-level security with no policy is a default deny, so "
                        + "even this tenant's own entry disappears. The failure mode of a "
                        + "dropped policy is a service that cannot read, which is the "
                        + "direction a mistake should fall in")
                    .isZero();
                assertThat(Db.countEntriesKeyed(c, foreignKey))
                    .as("and nothing of the other tenant's either")
                    .isZero();
                c.commit();

                asOwner("ALTER TABLE " + Db.ENTRY_TABLE + " DISABLE ROW LEVEL SECURITY");

                Db.bindTenant(c, tenantA);
                assertThat(Db.countEntriesKeyed(c, foreignKey))
                    .as("RED STATE, observed, and this is the one that leaks: with "
                        + "row-level security switched OFF the same session under the same "
                        + "tenant reads the other tenant's entry. No error, no warning, and "
                        + "every tenant's rows returned")
                    .isEqualTo(1);
            } finally {
                // Unconditionally, and before the restore — a failed assertion
                // above must not leave a lock for the DDL to wait on.
                c.commit();
                asOwner("ALTER TABLE " + Db.ENTRY_TABLE + " ENABLE ROW LEVEL SECURITY");
                asOwner("CREATE POLICY " + POLICY + " ON " + Db.ENTRY_TABLE
                    + " USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
                    + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
            }

            Db.bindTenant(c, tenantA);
            assertThat(Db.countEntriesKeyed(c, ownKey))
                .as("and restored: this tenant reads its own entry again")
                .isEqualTo(1);
            assertThat(Db.countEntriesKeyed(c, foreignKey))
                .as("and still not the other tenant's, so both red states above were the "
                    + "changes made and not some other drift")
                .isZero();
        }
    }

    /**
     * {@code FORCE}, where it still does something: the OWNER.
     *
     * <p>{@code ENABLE ROW LEVEL SECURITY} switches a policy on for every role
     * EXCEPT the table's owner. Here the owner is the migrator, and the
     * migrator is the role every future migration carrying DML runs as — a
     * seed, a backfill, a data correction. Without {@code FORCE} such a
     * migration would read and write across every tenant in the table and
     * report success.
     */
    @Test
    void without_force_the_owner_walks_straight_past_the_policy() throws SQLException {
        String foreignKey = keyFor(tenantB);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            Db.insertEntry(c, tenantB, foreignKey);
            c.commit();
        }

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, tenantA);
            assertThat(Db.countEntriesKeyed(owner, foreignKey))
                .as("green state: FORCE binds the owner to its own policy, so the migrator "
                    + "bound to tenant A does not see tenant B's entry either")
                .isZero();

            try {
                Db.exec(owner, "ALTER TABLE " + Db.ENTRY_TABLE + " NO FORCE ROW LEVEL SECURITY");
                owner.commit();
                Db.bindTenant(owner, tenantA);

                assertThat(Db.countEntriesKeyed(owner, foreignKey))
                    .as("RED STATE, observed: with FORCE removed the owner reads the other "
                        + "tenant's entry. The policy still exists and still says the right "
                        + "thing; it simply does not apply to the owner. This is what a "
                        + "migration carrying DML would silently do to every tenant in the "
                        + "table")
                    .isEqualTo(1);
            } finally {
                Db.exec(owner, "ALTER TABLE " + Db.ENTRY_TABLE + " FORCE ROW LEVEL SECURITY");
                owner.commit();
            }

            Db.bindTenant(owner, tenantA);
            assertThat(Db.countEntriesKeyed(owner, foreignKey))
                .as("and restored, so the red state was the removal and nothing else")
                .isZero();
        }
    }

    /**
     * The write half: a session bound to one tenant cannot plant a row under
     * another. Data planted across the boundary would be invisible to the
     * planter and to the tenant that now owns it, which is the one failure
     * shape a read-side filter cannot surface.
     *
     * <p><strong>The insert deliberately carries no {@code RETURNING}.</strong>
     * {@code RETURNING} reads back the row it just wrote and is therefore
     * subject to the policy's {@code USING} clause as well, so a foreign-tenant
     * insert with {@code RETURNING} is refused by whichever clause happens to
     * be evaluated — and the probe would be green against a policy whose
     * write-side predicate had been replaced by {@code true}. Measured: it was.
     * Without the clause, only the write-side predicate can refuse this
     * statement, which is what the probe is about.
     */
    @Test
    void a_write_under_a_foreign_tenant_is_refused_by_the_policy() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            try {
                Db.insertEntryWithoutReturning(c, tenantB, keyFor(tenantB));
                throw new AssertionError(
                    "a session bound to tenant A inserted a row owned by tenant B — the "
                        + "policy's write-side predicate is admitting foreign rows, and every "
                        + "write path can now cross the boundary the reads defend");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal must come from the policy rather than from a constraint "
                        + "that happens to fire first")
                    .contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * The same two guarantees on the relation table, which carries its own
     * policy and would otherwise be covered only by the resemblance between
     * the two migrations.
     */
    @Test
    void the_relation_table_is_isolated_and_refuses_a_foreign_tenant_write()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            UUID from = Db.insertEntry(c, tenantB, keyFor(tenantB));
            UUID to = Db.insertEntry(c, tenantB, keyFor(tenantB) + "-b");
            Db.insertRelation(c, tenantB, from, to, "refines");
            c.commit();

            Db.bindTenant(c, tenantB);
            assertThat(countRelations(c))
                .as("the relation is really in the table, counted under its own tenant")
                .isEqualTo(1);

            Db.bindTenant(c, tenantA);
            assertThat(countRelations(c))
                .as("and tenant A sees none of it")
                .isZero();

            try {
                Db.insertRelation(c, tenantB, from, to, "refines");
                throw new AssertionError(
                    "a session bound to tenant A wrote a relation owned by tenant B — the "
                        + "relation table's policy is missing its WITH CHECK");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    private static long countRelations(Connection c) throws SQLException {
        try (var s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM " + Db.RELATION_TABLE)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * A key that is unique to the tenant and satisfies the key-format check.
     *
     * <p>A uuid renders as lower-case hexadecimal separated by hyphens, which
     * is exactly what {@code memory_key_format} admits — so the probe can
     * carry the tenant in the key it later counts on, and the count in a
     * failing assertion says which tenant it was about.
     */
    private static String keyFor(UUID tenant) {
        return "probe-" + tenant;
    }

    /** Runs one statement as the table's owner, on a connection of its own. */
    private static void asOwner(String statement) throws SQLException {
        try (Connection owner = Db.asMigrator()) {
            Db.exec(owner, statement);
            owner.commit();
        }
    }
}
