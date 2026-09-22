package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5's platform row: readable by every tenant, writable by none, and not a
 * hole in the tenant boundary.
 *
 * <h2>What the row is for</h2>
 *
 * V4 seeds one selection, for the tenant bound when the migration ran. In the
 * enterprise composition one database serves many tenants and every other one
 * had no row at all, so {@code digest} answered it 500. V5 adds a row under
 * the null tenant and a second, permissive, {@code FOR SELECT} policy that
 * makes exactly that row visible to everybody.
 *
 * <h2>Why this is asserted against the database and not through the surface</h2>
 *
 * The three claims here are claims about privileges and policies: what a role
 * holds, what a policy admits, what a bound session can see. None of them is
 * observable through a verb — a verb that cannot write this table looks
 * exactly like a verb that was never asked to. {@code DigestPreferenceIT}
 * carries what the contract answers; this class carries what the database
 * permits.
 *
 * <p>Every connection here is a real role: the runtime role the service
 * connects as, and the migrator that owns the schema. The container superuser
 * is used only to plant a foreign tenant's row, which is a row no role under
 * test could write — and planting is setup, not a claim.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PlatformDefaultRowIT {

    private static final String TABLE = "memory.digest_preference";
    private static final UUID NULL_UUID = new UUID(0L, 0L);
    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final UUID OTHER_TENANT =
        UUID.fromString(SubstrateDatabaseResource.OTHER_TENANT_ID);

    /** V4's selection, which V5 repeats value for value. */
    private static final List<String> SEEDED_SELECTION =
        List.of("constraint", "decision", "convention", "glossary", "status");

    // ==================================================================
    // The row the migration left behind
    // ==================================================================

    /**
     * The migration wrote it, and wrote the selection V4 carries.
     *
     * <p>Read as the migrator, which owns the table: the question here is
     * what is IN the table, before any question about who can see it.
     */
    @Test
    void the_migration_leaves_exactly_one_platform_row_carrying_v4s_selection()
            throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, NULL_UUID);

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT types, include_global FROM " + TABLE
                         + " WHERE tenant_id = '" + NULL_UUID + "'"
                         + "   AND scope_id  = '" + NULL_UUID + "'")) {
                assertThat(rs.next())
                    .as("V5 seeds the platform selection and verifies its own seed; if "
                        + "this is absent the migration reported success without writing")
                    .isTrue();
                assertThat(List.of((String[]) rs.getArray(1).getArray()))
                    .as("the platform row carries V4's selection value for value — this "
                        + "migration closes a gap in WHO is served, and changing WHAT "
                        + "they are served at the same time would make a later "
                        + "difference in a digest impossible to attribute")
                    .containsExactlyElementsOf(SEEDED_SELECTION);
                assertThat(rs.getBoolean(2))
                    .as("and the global scope is left out, as in V4")
                    .isFalse();
                assertThat(rs.next())
                    .as("exactly one: the primary key is (tenant_id, scope_id), so a "
                        + "second would not be possible — asserted anyway, because that "
                        + "is a statement about the key and this is one about the seed")
                    .isFalse();
            }
        }
    }

    // ==================================================================
    // Who can see it
    // ==================================================================

    /**
     * A tenant sees its own rows and the platform's, and no other tenant's.
     *
     * <p>Counted rather than asserted as an absence: "cannot see tenant B" is
     * not observable, "sees exactly these three rows" is. The foreign row is
     * planted first, so the count is over a table that genuinely holds one.
     */
    @Test
    void a_bound_tenant_sees_its_own_rows_and_the_platform_row_and_nothing_else()
            throws SQLException {
        plantSelectionFor(OTHER_TENANT, NULL_UUID);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);

            assertThat(tenantsVisibleIn(c))
                .as("V4's policy admits the bound tenant's rows; V5's second policy is "
                    + "permissive and FOR SELECT, so PostgreSQL combines the two with OR "
                    + "and the platform row joins them. A policy written without the "
                    + "restriction to the null uuid would put tenant B's row here too, "
                    + "which is the isolation this schema exists to hold")
                .containsExactlyInAnyOrder(TENANT, NULL_UUID);
        }
    }

    /** And the other tenant sees the mirror image, which is what makes it a boundary. */
    @Test
    void the_other_tenant_sees_its_own_row_and_the_same_platform_row() throws SQLException {
        plantSelectionFor(OTHER_TENANT, NULL_UUID);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, OTHER_TENANT);

            assertThat(tenantsVisibleIn(c))
                .as("the platform row is the ONE row both tenants see, and V4's row of "
                    + "tenant A is not among them")
                .containsExactlyInAnyOrder(OTHER_TENANT, NULL_UUID);
        }
    }

    // ==================================================================
    // Who can write it — nobody
    // ==================================================================

    /**
     * The runtime role holds SELECT, so its writes are refused before any
     * policy is consulted.
     *
     * <p>Three statements rather than one: an UPDATE, a DELETE and an INSERT
     * fail on three different privileges, and a probe that tried only one
     * would leave the other two resting on the assumption that V4's grant
     * line means what it says. {@code ServiceRolePrivilegeIT} holds the grant
     * list itself; this asserts what the absence of the grants does.
     */
    @Test
    void the_runtime_role_can_neither_change_nor_remove_nor_add_a_selection()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);

            assertThatThrownBy(() -> execute(c,
                "UPDATE " + TABLE + " SET include_global = true"
                    + " WHERE tenant_id = '" + NULL_UUID + "'"))
                .as("ADR-0005: the runtime role holds enumerated privileges, and UPDATE "
                    + "is not among them for this table")
                .hasMessageContaining("permission denied");
            c.rollback();

            assertThatThrownBy(() -> execute(c,
                "DELETE FROM " + TABLE + " WHERE tenant_id = '" + NULL_UUID + "'"))
                .hasMessageContaining("permission denied");
            c.rollback();

            assertThatThrownBy(() -> execute(c,
                "INSERT INTO " + TABLE + " (tenant_id, scope_id, types, include_global)"
                    + " VALUES ('" + TENANT + "', '" + NULL_UUID + "',"
                    + " ARRAY['decision']::TEXT[], false)"))
                .as("there is no write surface for this table and no grant to build one "
                    + "with — changing a selection stays an operator act against the "
                    + "database, as V4 decided")
                .hasMessageContaining("permission denied");
            c.rollback();
        }
    }

    /**
     * A writer that DOES hold the privilege still cannot put a row under the
     * null tenant, unless it binds itself to it.
     *
     * <p>This is the claim V5's second policy deliberately does not make. The
     * new policy is {@code FOR SELECT}, so INSERT falls to V4's policy alone
     * and its {@code WITH CHECK} admits only the bound tenant's own rows. The
     * migrator owns the table and is bound by it anyway, because the table
     * carries {@code FORCE ROW LEVEL SECURITY} — which is why V5's own seed
     * has to switch the binding to write the row at all.
     */
    @Test
    void not_even_the_owner_writes_a_platform_row_while_bound_to_a_tenant()
            throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, TENANT);

            assertThatThrownBy(() -> execute(c,
                "INSERT INTO " + TABLE + " (tenant_id, scope_id, types, include_global)"
                    + " VALUES ('" + NULL_UUID + "', '"
                    + UUID.fromString(SubstrateDatabaseResource.SCOPE_ID) + "',"
                    + " ARRAY['decision']::TEXT[], false)"))
                .as("V4's WITH CHECK is unchanged by V5 and still governs every write: a "
                    + "session bound to a tenant writes that tenant's rows and no others, "
                    + "owner or not")
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    // ------------------------------------------------------------------

    /** The distinct tenants whose rows the current session can see. */
    private static List<UUID> tenantsVisibleIn(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT DISTINCT tenant_id FROM " + TABLE + " ORDER BY tenant_id")) {
            List<UUID> tenants = new java.util.ArrayList<>();
            while (rs.next()) {
                tenants.add((UUID) rs.getObject(1));
            }
            return tenants;
        }
    }

    /**
     * A selection for a tenant, planted as the container superuser.
     *
     * <p>The superuser bypasses row-level security, which is exactly why it is
     * the only role that can put a foreign tenant's row there — and why
     * planting with it is setup rather than a claim about anything.
     */
    private static void plantSelectionFor(UUID tenant, UUID scope) throws SQLException {
        try (Connection c = Db.asAdmin()) {
            execute(c, "INSERT INTO " + TABLE
                + " (tenant_id, scope_id, types, include_global)"
                + " VALUES ('" + tenant + "', '" + scope + "',"
                + " ARRAY['decision']::TEXT[], false)"
                + " ON CONFLICT (tenant_id, scope_id) DO NOTHING");
            c.commit();
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
