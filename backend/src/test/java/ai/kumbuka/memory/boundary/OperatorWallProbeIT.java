package ai.kumbuka.memory.boundary;

import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator wall, as a missing {@code USAGE} on a schema — and both of its
 * states observed.
 *
 * <h2>What changed about this wall, and why it is stronger now</h2>
 *
 * The guarantee is that the provider cannot read a tenant's entries. It used
 * to be kept by never listing this service's table among the operator role's
 * table grants: an absence in an enumeration, which holds for exactly the
 * tables somebody remembered to leave out of it. Now the entries live in a
 * schema of their own and the operator holds no {@code USAGE} on that schema,
 * so the wall is a missing privilege on a ROOM. It holds automatically for
 * every table this service will ever add, including the ones nobody has
 * thought of.
 *
 * <p>The same absence is asserted for the provider's read-only role and for
 * the other three services' roles. They are named individually rather than
 * covered by a rule about "everybody else", because a rule of that shape
 * would also be satisfied by a cluster where those roles do not exist — and
 * an assertion that passes against absent roles is one that stops meaning
 * anything the day somebody creates them.
 *
 * <h2>Why the red half is not optional</h2>
 *
 * A query that asks whether a privilege is absent returns "absent" just as
 * readily when it is asking about the wrong schema, the wrong role, or a
 * misspelling of either. Granting the privilege for the length of one
 * assertion is what distinguishes a boundary from a typo: the check must go
 * red, and it must NAME the role and the privilege it found.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class OperatorWallProbeIT {

    /**
     * Every role that must hold nothing on this schema: the provider, the
     * provider's reader, and the three other services.
     */
    private static final List<String> WALLED_OFF = walledOff();

    /**
     * The privileges a schema can carry. Both are asked about: {@code USAGE}
     * is what makes the tables inside reachable at all, and {@code CREATE}
     * would let the holder add an object to a schema it does not own.
     */
    private static final List<String> SCHEMA_PRIVILEGES = List.of("USAGE", "CREATE");

    private static List<String> walledOff() {
        List<String> roles = new ArrayList<>();
        roles.add(SubstrateDatabaseResource.PROVIDER_ROLE);
        roles.add(SubstrateDatabaseResource.OPS_READER_ROLE);
        roles.addAll(SubstrateDatabaseResource.OTHER_SERVICE_ROLES);
        return List.copyOf(roles);
    }

    private static String schema() {
        return ConfigProvider.getConfig().getValue("quarkus.flyway.default-schema", String.class);
    }

    /** The green state: nobody on the far side of the wall holds anything. */
    @Test
    void no_foreign_role_holds_any_privilege_on_this_schema() throws SQLException {
        try (Connection c = admin()) {
            assertThat(rolesPresent(c))
                .as("the roles must exist for their absence of privilege to mean anything — "
                    + "has_schema_privilege on a role that is not there is not an answer")
                .containsExactlyInAnyOrderElementsOf(WALLED_OFF);

            assertThat(wallDefects(c))
                .as("none of %s may hold USAGE or CREATE on schema %s. The provider's "
                    + "no-content guarantee is this absence and nothing else — not a "
                    + "filter, not a rule in the application, and not row-level security, "
                    + "which the reader role bypasses anyway", WALLED_OFF, schema())
                .isEmpty();
        }
    }

    /**
     * The red state, observed: {@code USAGE} granted to the provider for the
     * length of one assertion.
     */
    @Test
    void the_check_names_the_role_and_the_privilege_when_usage_is_granted()
            throws SQLException {
        String role = SubstrateDatabaseResource.PROVIDER_ROLE;
        try (Connection c = admin()) {
            try {
                exec(c, "GRANT USAGE ON SCHEMA " + schema() + " TO " + role);

                assertThat(wallDefects(c))
                    .as("RED STATE, observed: with USAGE granted the probe must report it, "
                        + "and must say which role and which privilege — a defect report "
                        + "that only says 'wrong' sends the reader back to the catalogue "
                        + "to find out what it meant")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains(role)
                        .contains("USAGE"));
            } finally {
                exec(c, "REVOKE USAGE ON SCHEMA " + schema() + " FROM " + role);
            }

            assertThat(wallDefects(c))
                .as("and closed again, so the red state was that grant and nothing else")
                .isEmpty();
        }
    }

    /**
     * And the same for the reader role, which is the one that carries
     * {@code BYPASSRLS}.
     *
     * <p>A separate case because it makes a different point: this role walks
     * past every policy in the database. If the wall were built as row-level
     * security, granting it {@code USAGE} and a table privilege would let it
     * read every tenant's entries. What stops it is that there is no privilege
     * to bypass anything with.
     */
    @Test
    void the_reader_role_is_refused_by_the_missing_privilege_and_not_by_a_policy()
            throws SQLException {
        String role = SubstrateDatabaseResource.OPS_READER_ROLE;
        String table = schema() + ".memory";

        assertThat(attemptRead(role, SubstrateDatabaseResource.OPS_READER_PASSWORD, table))
            .as("GREEN STATE: the read is REFUSED, not filtered. An empty result would "
                + "mean the query ran and something decided this role may see nothing; a "
                + "refusal means it never ran at all, and only the second is a boundary")
            .isInstanceOf(Refused.class);

        try (Connection c = admin()) {
            try {
                exec(c, "GRANT USAGE ON SCHEMA " + schema() + " TO " + role);
                exec(c, "GRANT SELECT ON " + table + " TO " + role);

                assertThat(attemptRead(role, SubstrateDatabaseResource.OPS_READER_PASSWORD, table))
                    .as("RED STATE, observed: with the two privileges granted this role "
                        + "reads the table — and reads it across every tenant, because "
                        + "BYPASSRLS makes the policy inapplicable to it. So the refusal "
                        + "above was the missing privilege doing the work, and nothing "
                        + "else was ever going to")
                    .isNotInstanceOf(Refused.class);
            } finally {
                exec(c, "REVOKE SELECT ON " + table + " FROM " + role);
                exec(c, "REVOKE USAGE ON SCHEMA " + schema() + " FROM " + role);
            }
        }

        assertThat(attemptRead(role, SubstrateDatabaseResource.OPS_READER_PASSWORD, table))
            .as("and refused again")
            .isInstanceOf(Refused.class);
    }

    // ------------------------------------------------------------------
    // The detection itself, used by both the green and the red cases.
    // ------------------------------------------------------------------

    /** Every privilege a walled-off role holds on this schema, one string each. */
    private static List<String> wallDefects(Connection c) throws SQLException {
        List<String> defects = new ArrayList<>();
        for (String role : WALLED_OFF) {
            for (String privilege : SCHEMA_PRIVILEGES) {
                if (holdsOnSchema(c, role, privilege)) {
                    defects.add(role + " holds " + privilege + " on schema " + schema()
                        + " and must hold nothing there");
                }
            }
        }
        return defects;
    }

    private static boolean holdsOnSchema(Connection c, String role, String privilege)
            throws SQLException {
        try (var st = c.prepareStatement("SELECT has_schema_privilege(?, ?, ?)")) {
            st.setString(1, role);
            st.setString(2, schema());
            st.setString(3, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static List<String> rolesPresent(Connection c) throws SQLException {
        List<String> present = new ArrayList<>();
        try (var st = c.prepareStatement(
                "SELECT rolname FROM pg_roles WHERE rolname = ANY(?)")) {
            st.setArray(1, c.createArrayOf("text", WALLED_OFF.toArray()));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    present.add(rs.getString(1));
                }
            }
        }
        return present;
    }

    /**
     * Reads the table under the given role and reports what happened.
     *
     * <p>The distinction is done on the SQLState — {@code 42501
     * insufficient_privilege} — rather than on message text, which is
     * localised and version-dependent. Any OTHER failure is rethrown rather
     * than folded into "refused": a typo in the query would otherwise read as
     * a boundary holding.
     */
    private static Object attemptRead(String role, String password, String table)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), role, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return new Refused(e.getMessage());
            }
            throw e;
        }
    }

    /** The database refused the statement. Not an empty result — no result. */
    private record Refused(String message) {
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String url() {
        return ConfigProvider.getConfig().getValue("test.db.url", String.class);
    }

    private static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }
}
