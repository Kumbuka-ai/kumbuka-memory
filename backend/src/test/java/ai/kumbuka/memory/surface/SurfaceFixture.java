package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * What the surface probes need that the surface itself cannot give them.
 *
 * <h2>Why anything is planted through JDBC at all</h2>
 *
 * Two of the acceptance cases are about rows this service will never write:
 * an entry belonging to another tenant, and — for the digest — more entries
 * per type than the core it replaces would have carried. The first cannot be
 * written through the verbs, because the tenant is configured for the whole
 * installation and there is exactly one; the second could be, at the cost of
 * a hundred and thirty HTTP calls per run.
 *
 * <p>Everything a probe ASSERTS about still goes through the surface. Planting
 * is setup; a claim made by reading the table directly would be a claim about
 * the table and not about the contract.
 *
 * <p>The connection is the container's superuser, which is the one role the
 * service never uses. That is legitimate here for the same reason it is in
 * the substrate: seeding is not a claim, and a foreign tenant's row cannot be
 * written by a role the policy binds.
 */
public final class SurfaceFixture {

    /** The published prefix of the verb surface. */
    public static final String API = "/api";

    private SurfaceFixture() {
    }

    // ------------------------------------------------------------------
    // Calling the surface
    // ------------------------------------------------------------------

    /** A request that will send and accept JSON. */
    public static RequestSpecification call() {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON);
    }

    /** The body of a create or an update: values under `fields`, and nothing beside. */
    public static String fields(String... namesAndValues) {
        StringBuilder body = new StringBuilder("{\"fields\":{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(namesAndValues[i]).append("\":");
            String value = namesAndValues[i + 1];
            body.append(value == null ? "null" : "\"" + escape(value) + "\"");
        }
        return body.append("}}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ------------------------------------------------------------------
    // Planting rows the verbs cannot write
    // ------------------------------------------------------------------

    /**
     * One entry, written as the superuser, for whichever tenant is named.
     *
     * @param createdAt the moment the entry is recorded as laid down, so that
     *                  a probe about the digest's ordering can fix it rather
     *                  than hope two inserts land a microsecond apart
     */
    public static UUID plant(Planted entry) {
        UUID logicalId = UUID.randomUUID();
        String sql = """
            INSERT INTO memory.memory
                (logical_id, version, tenant_id, scope_id, is_private, owner_subject,
                 type, key, content, source, created_at, updated_at)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, 'mcp', ?, ?)
            """;
        try (Connection c = admin(); PreparedStatement st = c.prepareStatement(sql)) {
            st.setObject(1, logicalId);
            st.setObject(2, UUID.fromString(entry.tenantId()));
            st.setObject(3, UUID.fromString(entry.scopeId()));
            st.setBoolean(4, entry.isPrivate());
            st.setString(5, entry.owner());
            st.setString(6, entry.type());
            st.setString(7, entry.key());
            st.setString(8, entry.content());
            st.setTimestamp(9, Timestamp.from(entry.createdAt()));
            st.setTimestamp(10, Timestamp.from(entry.createdAt()));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not plant " + entry.key(), e);
        }
        return logicalId;
    }

    /**
     * Many entries of one type, laid down one second apart.
     *
     * <p>One statement rather than a loop of inserts, because the digest probe
     * needs more entries per type than the core it replaces would have carried
     * and a connection per row would dominate the run. The created_at values
     * are spaced deliberately: the digest orders within a type by the moment
     * an entry was laid down, and two inserts in the same microsecond would
     * make that ordering unobservable rather than wrong.
     *
     * @param selector the selector every key stands under
     * @param type     the kind of entry
     * @param count    how many
     */
    public static void plantMany(String scopeId, String selector, String type, int count) {
        run("INSERT INTO memory.memory"
            + " (logical_id, version, tenant_id, scope_id, is_private, owner_subject,"
            + "  type, key, content, source, created_at, updated_at)"
            + " SELECT gen_random_uuid(), 1, '" + SubstrateDatabaseResource.TENANT_ID + "',"
            + " '" + scopeId + "', false, '" + SubstrateDatabaseResource.PROBE_SUBJECT + "',"
            + " '" + type + "', '" + selector + ".item-' || n, 'entry number ' || n, 'mcp',"
            + " TIMESTAMPTZ '2026-01-01 00:00:00+00' + (n || ' seconds')::interval,"
            + " TIMESTAMPTZ '2026-01-01 00:00:00+00' + (n || ' seconds')::interval"
            + " FROM generate_series(1, " + count + ") AS n");
    }

    /** The logical id of an entry, for the technical address a probe hands in. */
    public static UUID logicalIdOf(String scopeId, String key) {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT logical_id FROM memory.memory WHERE scope_id = '"
                + scopeId + "' AND key = '" + key + "'");
            if (!rs.next()) {
                throw new IllegalStateException("no entry '" + key + "' to take an id from");
            }
            return (UUID) rs.getObject(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the id of " + key, e);
        }
    }

    /** One number out of the table, for a probe that asks what a verb left behind. */
    public static long countRows(String sql) {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            var rs = s.executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture count failed: " + sql, e);
        }
    }

    /** Empties the entry table between probes that count what is in a scope. */
    public static void clearEntries() {
        run("DELETE FROM memory.memory");
    }

    /** Runs a statement as the container superuser. */
    public static void run(String sql) {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture statement failed: " + sql, e);
        }
    }

    private static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }

    /**
     * One entry to plant.
     *
     * <p>A record rather than eleven arguments: the call sites differ in two
     * fields at a time, and a positional list of eleven is one where a swap
     * produces a valid row in the wrong place.
     */
    public record Planted(String tenantId, String scopeId, boolean isPrivate, String owner,
                          String type, String key, String content, Instant createdAt) {

        /** An ordinary shared entry of the tenant under test, laid down now. */
        public static Planted shared(String scopeId, String key, String type,
                                     String content) {
            return new Planted(SubstrateDatabaseResource.TENANT_ID, scopeId, false,
                SubstrateDatabaseResource.PROBE_SUBJECT, type, key, content, Instant.now());
        }
    }
}
