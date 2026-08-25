package ai.kumbuka.memory.boundary;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outward boundary: this service reaches the tenancy anchor through one
 * published view and cannot touch the tables behind it.
 *
 * <p>The object chosen for the probe is not an invented neighbour but
 * {@code public.scope} — the very base table the permitted view reads on this
 * service's behalf. That makes the case sharper than a stand-in would: a
 * direct read of it succeeding would not mean some unrelated table was
 * exposed, it would mean the read contract had been bypassed rather than
 * consumed, and the narrowing the view performs — one question-shaped answer
 * instead of the membership behind it — would be gone.
 *
 * <p><strong>Why the distinction between a refusal and an empty result is the
 * whole point.</strong> An empty result means the query ran and the database
 * decided the caller may see nothing of what is there — a filter did its job.
 * {@code permission denied} means the query did not run at all. Only the
 * second is a boundary: a filter can be misconfigured into returning rows, and
 * a filter that fails open fails silently. A privilege that was never granted
 * has no failure mode of that shape. So every assertion below insists on the
 * refusal and would reject an empty result as a pass.
 *
 * <p>Each case then grants the missing privilege, watches the access succeed,
 * and revokes it again. Without that second half the tests would pass equally
 * against a database where the table simply does not exist, or is empty, or is
 * misspelled in the query — an assurance about an absence needs a witness that
 * the absence is what is doing the work.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MissingGrantProbeIT {

    private static final String ANCHOR_TABLE = SubstrateDatabaseResource.NEIGHBOUR_SCHEMA
        + "." + SubstrateDatabaseResource.NEIGHBOUR_TABLE;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    /**
     * Outward: the base table behind the read contract is unreachable, while
     * the contract itself is not.
     */
    @Test
    void the_service_role_cannot_read_the_table_behind_the_read_contract()
            throws SQLException {
        assertRefusedThenGrantedThenRefused(
            SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD,
            "SELECT count(*) FROM " + ANCHOR_TABLE,
            "GRANT SELECT ON " + ANCHOR_TABLE + " TO " + SubstrateDatabaseResource.SERVICE_ROLE,
            "REVOKE SELECT ON " + ANCHOR_TABLE + " FROM " + SubstrateDatabaseResource.SERVICE_ROLE,
            "this service must reach the anchor only through the published view. The view "
                + "answers one question and does not publish the membership that produces "
                + "the answer; a direct read of the base table would be that narrowing "
                + "removed, and it is a missing privilege rather than a rule that prevents "
                + "it");
    }

    /**
     * Inward: the provider cannot read a tenant's entries.
     *
     * <p>The boundary here is the absence of {@code USAGE} on the schema
     * rather than the absence of a table grant, which is why granting SELECT
     * alone does not open it — both statements are needed to reach the red
     * state, and that is itself the observation: the wall is one level up from
     * the table and holds for every table this service will ever add.
     */
    @Test
    void the_provider_role_cannot_read_this_services_table() throws SQLException {
        assertRefusedThenGrantedThenRefused(
            SubstrateDatabaseResource.PROVIDER_ROLE,
            SubstrateDatabaseResource.PROVIDER_PASSWORD,
            "SELECT count(*) FROM memory.memory",
            "GRANT USAGE ON SCHEMA memory TO " + SubstrateDatabaseResource.PROVIDER_ROLE
                + "; GRANT SELECT ON memory.memory TO " + SubstrateDatabaseResource.PROVIDER_ROLE,
            "REVOKE SELECT ON memory.memory FROM " + SubstrateDatabaseResource.PROVIDER_ROLE
                + "; REVOKE USAGE ON SCHEMA memory FROM " + SubstrateDatabaseResource.PROVIDER_ROLE,
            "the operator has no read path to a tenant's entries, and the private-memory "
                + "guarantee is that absence rather than a rule in an application that "
                + "could be reconfigured");
    }

    /**
     * Runs one boundary through its two states: refused, then — with the
     * privilege temporarily granted — successful, then refused again.
     */
    private void assertRefusedThenGrantedThenRefused(
            String role, String password, String query, String grant, String revoke, String why)
            throws SQLException {

        assertThat(attempt(role, password, query))
            .as("GREEN STATE. " + why)
            .isInstanceOf(Refused.class);

        try {
            asAdmin(grant);

            assertThat(attempt(role, password, query))
                .as("RED STATE, observed: with the privilege granted, the same role runs the "
                    + "same query and reads the data. So the refusal above was the missing "
                    + "privilege and not a missing table, a typo, or an empty result "
                    + "misread as a boundary")
                .isNotInstanceOf(Refused.class);
        } finally {
            asAdmin(revoke);
        }

        assertThat(attempt(role, password, query))
            .as("and closed again, so the red state was the grant and nothing else")
            .isInstanceOf(Refused.class);
    }

    /**
     * Runs the query under the given role and reports what happened: a
     * {@link Refused} when the database refused it, the scalar result
     * otherwise.
     *
     * <p>Distinguishing the two is the entire assertion, so it is done on the
     * SQLState — {@code 42501 insufficient_privilege} — rather than on message
     * text, which is localised and version-dependent. Any OTHER failure is
     * rethrown rather than folded into "refused": a typo in the query would
     * otherwise read as a boundary holding.
     */
    private Object attempt(String role, String password, String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), role, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return new Refused(e.getMessage());
            }
            throw e;
        }
    }

    private void asAdmin(String statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(),
                config("test.db.admin.username"), config("test.db.admin.password"));
             Statement s = c.createStatement()) {
            for (String statement : statements.split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
        }
    }

    private static String url() {
        return config("test.db.url");
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /** The database refused the statement. Not an empty result — no result. */
    private record Refused(String message) {
    }
}
