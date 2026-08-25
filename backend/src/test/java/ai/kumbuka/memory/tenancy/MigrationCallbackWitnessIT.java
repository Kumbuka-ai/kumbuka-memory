package ai.kumbuka.memory.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the tenant-binding Flyway callback.
 *
 * <h2>Why this needs its own test</h2>
 *
 * The Quarkus Flyway extension resolves callbacks from
 * {@code quarkus.flyway.callbacks} by class name and instantiates them
 * reflectively. It does <strong>not</strong> discover them as CDI beans. A
 * callback that is written, annotated and never named in that line is simply
 * never registered — with no warning, no error, and migrations that run
 * happily without it.
 *
 * <p>That failure is invisible while every migration is pure DDL, because
 * row-level security filters DML only. The schema this model comes from
 * carries such a callback and no registration key at all, and no test that
 * witnesses it running — so whether the mechanism works there is, to this
 * day, not known. That is the gap this class exists so as not to reproduce.
 *
 * <h2>What this observes, stated exactly</h2>
 *
 * This service's shipped chain is pure DDL, so the DML the probe needs is
 * supplied here, as one extra migration in a test-only location layered on
 * top of the real chain. The schema, the policies and the roles it writes
 * against are the real ones, produced by the real V1 to V3; only the writing
 * statement is the test's.
 *
 * <h2>Why Flyway is driven directly here</h2>
 *
 * The callback list is build-time configuration, and a running application
 * cannot un-register one. Driving Flyway against a container of this test's
 * own is what makes the negative case reachable at all.
 */
class MigrationCallbackWitnessIT {

    private static final String MIGRATOR = "witness_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-witness-password";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        // The migrating role: CREATEROLE and, critically, NOT BYPASSRLS. A
        // privileged migrator would walk past the policy, and the negative
        // case below would pass for the wrong reason — the DML would succeed
        // whether the callback ran or not.
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE NOSUPERUSER "
                + "NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The red state: without the callback, the migration carrying DML fails.
     *
     * <p>It fails at the policy, and the message says so. That is better than
     * the alternative — writing zero rows and reporting success — because a
     * migration that succeeds while writing nothing leaves the deployment
     * looking healthy and the data missing.
     */
    @Test
    void without_the_callback_the_dml_migration_is_refused_by_the_policy() throws SQLException {
        String url = freshDatabase("witness_without_callback");

        assertThatThrownBy(() -> migrate(url, false))
            .as("RED STATE, observed: with the callback absent from the configuration, "
                + "app.tenant_id is never bound, the WITH CHECK clause compares the "
                + "incoming row against nothing, and the row cannot be written. This is "
                + "the failure that stays invisible for as long as every migration is "
                + "pure DDL — which, in this repository, is all of them")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("row-level security");
    }

    /**
     * The green state: with the callback registered, the same set applies and
     * the row is there.
     *
     * <p>Both halves are the probe. The red state alone would hold against a
     * migration that is broken for some other reason entirely.
     */
    @Test
    void with_the_callback_the_same_migration_applies_and_writes_its_row() throws SQLException {
        String url = freshDatabase("witness_with_callback");
        migrate(url, true);

        try (Connection c = DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT key FROM memory.memory ORDER BY key")) {
            var found = new ArrayList<String>();
            while (rs.next()) {
                found.add(rs.getString(1));
            }
            assertThat(found)
                .as("and with it registered the write lands — so the refusal above was the "
                    + "missing callback and not a broken migration")
                .containsExactly("witness-entry");
        }
    }

    /**
     * The other half of the witness: the application's own configuration names
     * the callback.
     *
     * <p>The two cases above prove what a registered callback DOES. They drive
     * Flyway themselves, so they would prove exactly the same thing about a
     * service whose {@code quarkus.flyway.callbacks} line was missing
     * altogether — which is the state the schema this model comes from is in,
     * and the reason it is not known there whether the mechanism runs at all.
     *
     * <p>So the registration itself is asserted, and it is asserted against the
     * shipped file rather than against resolved configuration: a test-profile
     * override would otherwise be able to satisfy it, and the claim is about
     * what a deployment boots with.
     */
    @Test
    void the_application_configuration_registers_the_callback() throws IOException {
        Path properties = Files.isRegularFile(Path.of("src/main/resources/application.properties"))
            ? Path.of("src/main/resources/application.properties")
            : Path.of("backend/src/main/resources/application.properties");

        String configured = Files.readAllLines(properties).stream()
            .map(String::strip)
            .filter(line -> line.startsWith("quarkus.flyway.callbacks="))
            .findFirst()
            .orElse("");

        assertThat(configured)
            .as("the Quarkus Flyway extension resolves callbacks from this key by class "
                + "name and instantiates them reflectively — it does not discover them as "
                + "CDI beans. A callback that is written, annotated and not named here is "
                + "never registered, with no warning and no error, and every migration "
                + "runs without it")
            .contains(TenantMigrationCallback.class.getName());
    }

    /**
     * The fourth case: a run can be SHOWN to have bound its tenant.
     *
     * <p>The three cases above establish that the callback works and that the
     * shipped configuration registers it. None of them lets anyone answer the
     * question a deployment actually raises — <em>did it fire on THAT run</em>
     * — because the binding leaves nothing behind: {@code is_local = true}
     * ends it with the migration's transaction, so the database cannot be
     * asked afterwards.
     *
     * <p>Without a line in the log the only available answer is "the
     * configuration names it, so presumably it did". That is an inference from
     * code, and an inference from code is exactly what let an unregistered
     * callback in a sibling service go unnoticed for two sprints. So the
     * callback emits one, and this case is what keeps it emitted: delete the
     * line and this fails while the other three stay green.
     */
    @Test
    void a_run_leaves_a_line_that_shows_the_binding_happened() throws SQLException {
        var emitted = new ArrayList<LogRecord>();
        var collector = new Handler() {
            @Override public void publish(LogRecord entry) { emitted.add(entry); }
            // Nothing is buffered and nothing is held open — publish() appends
            // to a list in memory — so there is nothing for either to do.
            @Override public void flush() { /* nothing buffered */ }
            @Override public void close() { /* no resource held */ }
        };
        collector.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger(TenantMigrationCallback.class.getName());
        var previousLevel = logger.getLevel();
        logger.addHandler(collector);
        logger.setLevel(Level.ALL);
        try {
            migrate(freshDatabase("witness_log"), true);
        } finally {
            logger.removeHandler(collector);
            logger.setLevel(previousLevel);
        }

        var lines = emitted.stream()
            .map(r -> java.text.MessageFormat.format(
                r.getMessage() == null ? "" : r.getMessage(),
                r.getParameters() == null ? new Object[0] : r.getParameters()))
            .toList();

        assertThat(lines)
            .as("the callback must leave a line naming the binding it applied. Without one, "
                + "'did the callback fire on this deployment' can only be answered from the "
                + "code, and that inference is the failure mode this probe exists against")
            .anySatisfy(line -> assertThat(line).contains(TenantMigrationCallback.FIRED_MARKER));

        assertThat(lines)
            .as("and it must name the tenant it bound — a marker without the value would "
                + "confirm that something ran, not that the right axis was bound")
            .anySatisfy(line -> assertThat(line)
                .contains(ConfigProvider.getConfig().getValue("memory.tenant-id", String.class)));
    }

    /**
     * The fifth case: a binding that cannot be applied stops the migration.
     *
     * <p>The callback wraps its failure in an unchecked exception rather than
     * swallowing it, and that choice is the whole safety property. If binding
     * the tenant could fail quietly, the migration behind it would run
     * unbound — and under {@code FORCE ROW LEVEL SECURITY} an unbound DML
     * migration does not raise either. It writes no rows, reports success, and
     * the seed turns up missing much later and somewhere else.
     *
     * <p>So the throw is asserted rather than assumed. The failure is produced
     * the way it would really arrive — a connection that is no longer usable —
     * instead of by mocking the callback's own internals.
     */
    @Test
    void a_binding_that_cannot_be_applied_stops_the_migration() throws SQLException {
        Connection closed = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        closed.close();

        var context = new StubContext(closed);

        assertThatThrownBy(() -> new TenantMigrationCallback()
                .handle(org.flywaydb.core.api.callback.Event.BEFORE_EACH_MIGRATE, context))
            .as("a callback that swallowed this would let the migration behind it run "
                + "unbound, and an unbound DML migration under FORCE ROW LEVEL SECURITY "
                + "writes nothing and still reports success")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("failed to bind app.tenant_id")
            .hasCauseInstanceOf(SQLException.class);
    }

    /**
     * The one thing the callback reads from its context is the connection; the
     * rest of the interface is unused on this path and returns null.
     */
    private record StubContext(Connection connection)
            implements org.flywaydb.core.api.callback.Context {

        @Override public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
            return null;
        }

        @Override public Connection getConnection() {
            return connection;
        }

        @Override public org.flywaydb.core.api.MigrationInfo getMigrationInfo() {
            return null;
        }

        @Override public org.flywaydb.core.api.callback.Statement getStatement() {
            return null;
        }

        @Override public org.flywaydb.core.api.output.OperationResult getOperationResult() {
            return null;
        }
    }

    /**
     * Runs the service's real migration set, plus the test-only DML migration
     * this probe needs, against this test's container.
     *
     * @param url          the database of this case
     * @param withCallback whether to register the tenant-binding callback, which
     *                     is the single variable this probe changes
     */
    private static void migrate(String url, boolean withCallback) {
        var config = ConfigProvider.getConfig();
        Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .schemas("memory")
            .defaultSchema("memory")
            .createSchemas(true)
            // The real chain first, then the one DML migration this probe
            // needs. The order of the locations does not decide the order of
            // the migrations — the version numbers do, and the fixture is
            // V900 so it can never come between two real ones.
            .locations("classpath:db/migration", "classpath:db/witness")
            .placeholders(Map.of(
                "memoryTenantId", config.getValue("memory.tenant-id", String.class)))
            .callbacks(withCallback
                ? new org.flywaydb.core.api.callback.Callback[] { new TenantMigrationCallback() }
                : new org.flywaydb.core.api.callback.Callback[] {})
            .load()
            .migrate();
    }

    /**
     * A database of this case's own.
     *
     * <p>Each case has to migrate from nothing. Sharing one database would let
     * whichever ran first apply the migration set, and the second would find
     * everything already applied, do nothing, and report success — so the
     * negative case would pass without ever attempting the write it is about.
     */
    private static String freshDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name);
            s.execute("CREATE DATABASE " + name);
            s.execute("GRANT CREATE ON DATABASE " + name + " TO " + MIGRATOR);
        }
        return postgres.getJdbcUrl().replace("/kumbuka?", "/" + name + "?");
    }
}
