/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entry laid down before the migrations of this release is, after them,
 * unchanged, and can still be read, changed and withdrawn: the data survive
 * the widened checks of V8, the renames of V9 and the new table of V10.
 *
 * <p>A database of its own is migrated to V7, the last version the image
 * before this release ran on, and an entry is written the way that image
 * writes one: by the service role, with the tenant bound, and without a time,
 * so that the column defaults set {@code created_at} and {@code updated_at}.
 * Then the rest of the chain runs over it. The three verbs are exercised in
 * the statements they send, under the service role and its grants: the
 * locking read {@code update} and {@code withdraw} take, the update with the
 * channel V8 admits, and the delete of this edition's withdrawal.
 *
 * <p>Plain JUnit and a container of its own, as {@code
 * MigrationCallbackWitnessIT}: the application's database is migrated to the
 * end when it boots, so a state before the migrations is only reachable in a
 * database nobody else migrated.
 */
class MigrationSurvivalIT {

    private static final String MIGRATOR = "survival_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-survival-password";

    /** The service role and its password as V2 creates it. */
    private static final String SERVICE_ROLE = "kumbuka_memory";
    private static final String SERVICE_PASSWORD = "change-me-kumbuka-memory";

    /** The last migration the image before this release ran on. */
    private static final String BEFORE_THIS_RELEASE = "7";

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a1";
    private static final String SCOPE = "00000000-0000-0000-0000-0000000000b1";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        try (Connection c = asAdmin(postgres.getJdbcUrl()); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE NOSUPERUSER "
                + "NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
            s.execute("CREATE DATABASE survival");
            s.execute("GRANT CREATE ON DATABASE survival TO " + MIGRATOR);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void an_entry_laid_down_before_the_migrations_is_read_changed_and_withdrawn_after_them()
            throws SQLException {
        String url = postgres.getJdbcUrl().replace("/kumbuka?", "/survival?");
        migrate(url, BEFORE_THIS_RELEASE);
        assertThat(constraintNames(url))
            .as("the state before: the names V1 and V4 gave")
            .contains("memory_source_check", "memory_key_format", "content_relation_kind_check",
                "ix_content_relation_from");

        UUID entry = UUID.randomUUID();
        UUID related = UUID.randomUUID();
        asService(url, c -> {
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO memory.memory (logical_id, version, tenant_id, scope_id,
                        is_private, owner_subject, type, key, content, source)
                    VALUES (?, 1, ?::uuid, ?::uuid, false, 'survivor', 'decision',
                        'decision.survives', 'laid down before', 'mcp'),
                           (?, 1, ?::uuid, ?::uuid, false, 'survivor', 'decision',
                        'decision.related', 'its neighbour', 'mcp')
                    """)) {
                st.setObject(1, entry);
                st.setString(2, TENANT);
                st.setString(3, SCOPE);
                st.setObject(4, related);
                st.setString(5, TENANT);
                st.setString(6, SCOPE);
                st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement("""
                    INSERT INTO memory.content_relation (tenant_id, from_logical_id,
                        to_logical_id, kind) VALUES (?::uuid, ?, ?, 'refines')
                    """)) {
                st.setString(1, TENANT);
                st.setObject(2, entry);
                st.setObject(3, related);
                st.executeUpdate();
            }
        });
        Map<String, String> before = row(url, entry);
        assertThat(before.get("updated_at")).as("set by the column default").isNotNull();

        migrate(url, null);

        assertThat(constraintNames(url))
            .as("the state after: every name the rule refused is gone")
            .doesNotContain("memory_source_check", "memory_updated_source_check",
                "memory_key_format", "memory_content_len", "memory_lock_check",
                "memory_reference_no_credentials", "memory_state_check", "memory_type_check",
                "content_relation_kind_check", "digest_preference_types_known",
                "digest_preference_types_present", "ix_content_relation_from",
                "ix_content_relation_to")
            .contains("ck_memory_source", "ck_memory_updated_source", "ck_memory_key_format",
                "ck_content_relation_kind", "ck_digest_preference_types_known",
                "idx_content_relation_from", "idx_content_relation_to");
        assertThat(row(url, entry))
            .as("the entry is what it was, column for column")
            .isEqualTo(before);

        asService(url, c -> {
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT content FROM memory.memory
                    WHERE scope_id = ?::uuid AND key = 'decision.survives'
                      AND is_head AND NOT is_deleted
                    FOR NO KEY UPDATE
                    """)) {
                st.setString(1, SCOPE);
                try (ResultSet rs = st.executeQuery()) {
                    assertThat(rs.next()).as("read, under the lock update takes").isTrue();
                    assertThat(rs.getString(1)).isEqualTo("laid down before");
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    UPDATE memory.memory SET content = 'changed after',
                        updated_at = updated_at + interval '1 microsecond',
                        updated_by = 'survivor', updated_source = 'rest'
                    WHERE logical_id = ? AND version = 1
                    """)) {
                st.setObject(1, entry);
                assertThat(st.executeUpdate())
                    .as("changed, through the channel V8 admits").isEqualTo(1);
            }
            try (PreparedStatement st = c.prepareStatement(
                    "DELETE FROM memory.memory WHERE logical_id = ? AND version = 1")) {
                st.setObject(1, entry);
                assertThat(st.executeUpdate()).as("and withdrawn").isEqualTo(1);
            }
        });
    }

    // -----------------------------------------------------------------------

    /** Runs the service's migration chain up to {@code target}, or to its end. */
    private static void migrate(String url, String target) {
        var flyway = Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .schemas("memory")
            .defaultSchema("memory")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .placeholders(Map.of("memoryTenantId",
                ConfigProvider.getConfig().getValue("memory.tenant-id", String.class)))
            .callbacks(new Callback[] { new TenantMigrationCallback() });
        if (target != null) {
            flyway.target(MigrationVersion.fromVersion(target));
        }
        flyway.load().migrate();
    }

    private interface Work {
        void on(Connection c) throws SQLException;
    }

    /** One transaction as the service role, with the tenant bound as the service binds it. */
    private static void asService(String url, Work work) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, SERVICE_ROLE, SERVICE_PASSWORD)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', true)");
            }
            work.on(c);
            c.commit();
        }
    }

    /** Every column of the entry, as text, read past the policy. */
    private static Map<String, String> row(String url, UUID logicalId) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (Connection c = asAdmin(url);
             PreparedStatement st = c.prepareStatement(
                 "SELECT * FROM memory.memory WHERE logical_id = ?")) {
            st.setObject(1, logicalId);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("the entry is there").isTrue();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    columns.put(rs.getMetaData().getColumnName(i), rs.getString(i));
                }
            }
        }
        return columns;
    }

    private static List<String> constraintNames(String url) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = asAdmin(url); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT conname FROM pg_constraint
                 WHERE connamespace = 'memory'::regnamespace
                 UNION ALL
                 SELECT indexname FROM pg_indexes WHERE schemaname = 'memory'
                 """)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static Connection asAdmin(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }
}
