package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boots the database the probes actually need: a real PostgreSQL, with the
 * real role shape the service runs under.
 *
 * <p><strong>Why not DevServices.</strong> A development datasource connects
 * as a superuser, and a superuser bypasses row-level security
 * unconditionally. Every isolation assertion made against it passes whether
 * the policies exist or not, so the suite would be green on a schema with the
 * security removed. The one thing this service most needs to prove is the one
 * thing that setup cannot prove.
 *
 * <p>So the container is started here and the roles are kept apart:
 *
 * <ul>
 *   <li><b>the administrator</b> — the container's own superuser. It stages
 *       the other roles and the neighbour, and the service never uses it.</li>
 *   <li><b>the migrator</b> — CREATEROLE, and deliberately nothing more.
 *       Creating the service role is the one privileged act the migration set
 *       performs; superuser would additionally hand it BYPASSRLS and make its
 *       DML untestable. It is also the OWNER of this schema and of everything
 *       in it, the Flyway history table included.</li>
 *   <li><b>the service role</b> — created by the migration itself, so the
 *       cold start is exercised rather than staged. Neither superuser nor
 *       BYPASSRLS, and the owner of nothing. It reaches its own tables through
 *       the privileges V2 enumerates, which is what lets a probe ask whether
 *       it holds TRUNCATE and get a meaningful answer.</li>
 *   <li><b>the provider role and the provider's reader</b> — created here,
 *       the reader carrying BYPASSRLS, neither holding any grant on this
 *       service's schema. They are the operator boundary's counterparties, and
 *       BYPASSRLS is the point: a role that bypasses every policy still cannot
 *       reach a schema it holds no USAGE on, which is what makes the boundary a
 *       missing privilege rather than a filter.</li>
 *   <li><b>the three other services' roles</b> — created here, holding
 *       nothing here. The boundary between two services is the same absence as
 *       the boundary to the operator, and it is asserted for each of them by
 *       name rather than assumed to follow.</li>
 * </ul>
 *
 * <p>The tenancy anchor is staged as well — the platform's base tables, its
 * schema, and the one view this service is allowed to read. It is a stand-in
 * and not a dependency: building a real one here would import exactly the
 * coupling the architecture forbids. The base table {@code public.scope} then
 * doubles as the neighbour the service must NOT be able to read, which is a
 * sharper case than an invented table would be — it is the very table the
 * permitted view reads on its behalf.
 */
public class SubstrateDatabaseResource implements QuarkusTestResourceLifecycleManager {

    /** Production major. Chosen over the newest so the gate tests what runs. */
    public static final String POSTGRES_IMAGE = "postgres:16";

    /**
     * The migrating role. CREATEROLE, and deliberately NOT a superuser.
     *
     * <p>A migrator needs exactly one privilege the service does not have —
     * the right to create the service's role — and CREATEROLE is that
     * privilege. Giving it superuser instead would hand it BYPASSRLS as a
     * side effect, and a migration that bypasses row-level security is one
     * whose DML cannot be observed failing when it forgets the tenant
     * binding.
     *
     * <p>It is also the OWNER of this schema and of everything in it. An
     * owner can drop a policy and disable row-level security, which is
     * precisely why that role is not the one the service connects as.
     */
    public static final String MIGRATOR_ROLE = "kumbuka_memory_migrator";
    public static final String MIGRATOR_PASSWORD = "test-only-migrator-password";

    /** The service role. Created by V2 — NOT staged here, so the cold start is real. */
    public static final String SERVICE_ROLE = "kumbuka_memory";
    public static final String SERVICE_PASSWORD = "change-me-kumbuka-memory";

    /** The provider role. Deliberately ungranted on this schema. */
    public static final String PROVIDER_ROLE = "kumbuka_operator";
    public static final String PROVIDER_PASSWORD = "test-only-operator-password";

    /** The provider's read-only role. Deliberately BYPASSRLS, deliberately ungranted. */
    public static final String OPS_READER_ROLE = "kumbuka_ops_reader";
    public static final String OPS_READER_PASSWORD = "test-only-ops-reader-password";

    /**
     * The other services' roles. They exist so that the absence of any
     * privilege of theirs on this schema is asserted against real roles rather
     * than against names that resolve to nothing.
     */
    public static final List<String> OTHER_SERVICE_ROLES =
        List.of("kumbuka_dispatch", "kumbuka_worklist", "kumbuka_logbook");

    /**
     * The neighbouring object this service must not reach: a base table of the
     * tenancy anchor. The permitted view reads it on the service's behalf, and
     * that is the whole design — so a direct read of it succeeding would mean
     * the read contract had been bypassed rather than consumed.
     */
    public static final String NEIGHBOUR_SCHEMA = "public";
    public static final String NEIGHBOUR_TABLE = "scope";

    /**
     * The platform's role, and the read contract it publishes.
     *
     * <p>The directory view is owned by this role rather than by the container
     * superuser, which is what a deployment's owner-normalisation sweep
     * arranges. The distinction is load-bearing: a view without
     * {@code security_invoker} reads its base tables with its OWNER's
     * privileges, so a superuser-owned view is exempt from
     * {@code FORCE ROW LEVEL SECURITY} — and the failure is silent, because
     * the view returns rows and raises nothing.
     */
    public static final String PLATFORM_ROLE = "kumbuka";
    public static final String PLATFORM_SCHEMA = "platform";
    public static final String DIRECTORY_VIEW = "scope_access";

    /** The scope the directory publishes to the probing subject. */
    public static final String PROBE_SCOPE_SLUG = "probe-scope";
    public static final String PROBE_SUBJECT = "probe-subject";

    /**
     * The tenancy axis and the scope under test. Fixed rather than random so
     * that the value in a failure message can be recognised, and matched to
     * the same two values in the test configuration.
     */
    public static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    public static final String SCOPE_ID  = "00000000-0000-0000-0000-000000000010";

    private static PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        seedRolesAndAnchor();

        Map<String, String> cfg = new HashMap<>();

        // The runtime connection: the service's own role, created by V2.
        cfg.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
        cfg.put("quarkus.datasource.username", SERVICE_ROLE);
        cfg.put("quarkus.datasource.password", SERVICE_PASSWORD);

        // The migrating connection: CREATEROLE, and nothing more than that.
        cfg.put("quarkus.flyway.jdbc-url", postgres.getJdbcUrl());
        cfg.put("quarkus.flyway.username", MIGRATOR_ROLE);
        cfg.put("quarkus.flyway.password", MIGRATOR_PASSWORD);

        // Raw-JDBC coordinates for the probes, which open their own
        // connections under each role to see what that role can actually do.
        cfg.put("test.db.url", postgres.getJdbcUrl());
        cfg.put("test.db.admin.username", postgres.getUsername());
        cfg.put("test.db.admin.password", postgres.getPassword());

        return cfg;
    }

    /**
     * Creates what the migration must not create: the migrating role, the
     * provider's two roles, the other services' roles, and the tenancy anchor
     * with its published view.
     *
     * <p>None of it belongs in this service's migration set. The provider's
     * roles are the platform's, and a service that created its own counterparty
     * could quietly grant it something. The anchor is another service's schema,
     * and building it here would mean this service creates the contract it
     * consumes — a consumer that can create its own contract can widen it.
     */
    private void seedRolesAndAnchor() {
        try (Connection c = adminConnection(); Statement s = c.createStatement()) {
            // The migrator. CREATEROLE lets it create the service role in V2;
            // NOSUPERUSER NOBYPASSRLS mean its own DML is subject to the
            // policies, which is what makes a forgotten tenant binding in a
            // migration observable instead of accidentally harmless.
            createRole(s, MIGRATOR_ROLE, "LOGIN CREATEROLE NOSUPERUSER NOBYPASSRLS PASSWORD '"
                + MIGRATOR_PASSWORD + "'");
            s.execute("GRANT CREATE ON DATABASE " + postgres.getDatabaseName()
                + " TO " + MIGRATOR_ROLE);

            createRole(s, PROVIDER_ROLE, "LOGIN PASSWORD '" + PROVIDER_PASSWORD + "'");
            createRole(s, OPS_READER_ROLE, "LOGIN BYPASSRLS PASSWORD '"
                + OPS_READER_PASSWORD + "'");
            for (String role : OTHER_SERVICE_ROLES) {
                createRole(s, role, "LOGIN PASSWORD 'test-only-" + role + "-password'");
            }

            stagePlatformDirectory(s);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to stage the roles and the anchor", e);
        }
    }

    private static void createRole(Statement s, String role, String attributes)
            throws SQLException {
        s.execute("""
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                    CREATE ROLE %s %s;
                END IF;
            END $$;
            """.formatted(role, role, attributes));
    }

    /**
     * Reconstructs the platform's published read contract, as a deployment
     * has it: base tables under FORCE row-level security, a view over them in
     * its own schema, and the consuming role holding SELECT on the view and
     * nothing else.
     */
    private void stagePlatformDirectory(Statement s) throws SQLException {
        createRole(s, PLATFORM_ROLE, "NOSUPERUSER NOBYPASSRLS");

        s.execute("""
            CREATE TABLE IF NOT EXISTS public.scope (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                tenant_id uuid NOT NULL,
                slug text NOT NULL,
                kind text NOT NULL,
                archived boolean NOT NULL DEFAULT false)
            """);
        s.execute("""
            CREATE TABLE IF NOT EXISTS public.user_account (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                tenant_id uuid NOT NULL,
                subject text NOT NULL,
                status text NOT NULL DEFAULT 'active')
            """);

        for (String table : new String[] {"scope", "user_account"}) {
            s.execute("ALTER TABLE public." + table + " ENABLE ROW LEVEL SECURITY");
            s.execute("ALTER TABLE public." + table + " FORCE  ROW LEVEL SECURITY");
            s.execute("DROP POLICY IF EXISTS " + table + "_tenant_isolation ON public." + table);
            s.execute("CREATE POLICY " + table + "_tenant_isolation ON public." + table
                + " USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
                + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
        }

        s.execute("CREATE SCHEMA IF NOT EXISTS " + PLATFORM_SCHEMA);
        s.execute("REVOKE ALL ON SCHEMA " + PLATFORM_SCHEMA + " FROM PUBLIC");
        s.execute("""
            CREATE OR REPLACE VIEW platform.scope_access AS
                SELECT sc.id AS scope_id, sc.tenant_id, sc.slug, sc.archived
                FROM public.scope sc
                JOIN public.user_account ua ON ua.tenant_id = sc.tenant_id
                WHERE sc.kind = 'project'
                  AND sc.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                  AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
                  AND ua.status   = 'active'
            """);

        // The owner-normalisation sweep, in the one respect this suite cares
        // about: the view must not be owned by a superuser.
        s.execute("ALTER TABLE public.scope        OWNER TO " + PLATFORM_ROLE);
        s.execute("ALTER TABLE public.user_account OWNER TO " + PLATFORM_ROLE);
        s.execute("ALTER VIEW  platform.scope_access OWNER TO " + PLATFORM_ROLE);

        // The grants are NOT issued here, and the omission is deliberate. The
        // service's own migration creates its role during boot, which is after
        // this runs — and in a deployment the platform grants to a role it
        // already knows about, in its own migration. PlatformFixture issues
        // them once the role exists, which is also the order a deployment has.

        // One project scope, and a subject that is an active member of its tenant.
        s.execute("SELECT set_config('app.tenant_id', '" + TENANT_ID + "', false)");
        s.execute("INSERT INTO public.scope (id, tenant_id, slug, kind) "
            + "SELECT '" + SCOPE_ID + "', '" + TENANT_ID + "', '" + PROBE_SCOPE_SLUG + "', 'project' "
            + "WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '" + SCOPE_ID + "')");
        s.execute("INSERT INTO public.user_account (tenant_id, subject) "
            + "SELECT '" + TENANT_ID + "', '" + PROBE_SUBJECT + "' "
            + "WHERE NOT EXISTS (SELECT 1 FROM public.user_account "
            + "WHERE subject = '" + PROBE_SUBJECT + "')");
        s.execute("RESET app.tenant_id");
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
