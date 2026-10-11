/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two listing reads of V6 are served by the indexes V6 lays down.
 *
 * <h2>What is planned, and as whom</h2>
 *
 * The statements are the shape {@code MemoryRepository.page} sends — scope,
 * the in-force and visibility predicates, the addressable predicate, the
 * narrowing, the order and the one-row-beyond limit — written out in SQL with
 * literal values, and planned as the service role with the tenant bound, so
 * that row-level security adds its predicate exactly as it does at run time.
 * The estate is large enough, and analysed, that the planner chooses by cost
 * and not because the table fits in a page: an index nobody forces is the one
 * that is actually used.
 *
 * <p>The red run: with the two {@code CREATE INDEX} statements removed from V6
 * (and a clean build, so the old migration is not left in the target tree),
 * both plans name another path and both probes fail. Observed on 2026-10-11.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ListingIndexIT {

    /** Rows in the probe's estate: enough that a sequential scan has a cost. */
    private static final int ROWS = 4000;

    /** Selectors among which the rows are spread, so that one is a narrow read. */
    private static final int SELECTORS = 40;

    private UUID tenant;
    private UUID scope;

    @BeforeEach
    void anAnalysedEstate() throws SQLException {
        tenant = UUID.randomUUID();
        scope = UUID.randomUUID();
        try (Connection c = Db.asAdmin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO memory.memory"
                + " (logical_id, version, tenant_id, scope_id, is_private, owner_subject,"
                + "  type, key, content, source, created_at, updated_at)"
                + " SELECT gen_random_uuid(), 1, '" + tenant + "', '" + scope + "', false,"
                + " 'probe', 'decision', 'sel' || (n % " + SELECTORS + ") || '.item-' || n,"
                + " 'entry ' || n, 'mcp',"
                + " TIMESTAMPTZ '2026-01-01 00:00:00+00' + (n || ' seconds')::interval,"
                + " TIMESTAMPTZ '2026-01-01 00:00:00+00' + (n || ' seconds')::interval"
                + " FROM generate_series(1, " + ROWS + ") AS n");
            s.execute("ANALYZE memory.memory");
            c.commit();
        }
    }

    @AfterEach
    void removeTheEstate() throws SQLException {
        try (Connection c = Db.asAdmin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM memory.memory WHERE tenant_id = '" + tenant + "'");
            c.commit();
        }
    }

    @Test
    void a_listing_narrowed_to_a_selector_reads_the_selector_index() throws SQLException {
        String plan = planAsTheService(
            listing("AND m.selector = 'sel7'") + " ORDER BY m.key ASC LIMIT 51");

        assertThat(plan)
            .as("the selector is an equality on the generated column, and (scope_id, "
                + "selector, key) serves the narrowing and the key order behind it. Plan:%n%s",
                plan)
            .contains("idx_memory_scope_selector_key");
    }

    @Test
    void a_listing_in_creation_order_reads_the_creation_index() throws SQLException {
        String plan = planAsTheService(
            listing("") + " ORDER BY m.created_at DESC, m.key DESC LIMIT 51");

        assertThat(plan)
            .as("(tenant_id, scope_id, created_at, key) delivers the rows in the order "
                + "asked for, so the limit stops the scan rather than a sort over the scope. "
                + "Plan:%n%s", plan)
            .contains("idx_memory_scope_created_key");
    }

    /** The statement {@code MemoryRepository.page} sends, up to its ORDER BY. */
    private String listing(String narrowing) {
        return "SELECT m.logical_id FROM memory.memory m"
            + " WHERE m.tenant_id = '" + tenant + "'"
            + " AND m.scope_id = '" + scope + "'"
            + " AND m.is_head = true AND m.is_deleted = false"
            + " AND (m.is_private = false OR m.owner_subject = 'probe')"
            + " AND m.key IS NOT NULL AND position('.' in m.key) > 1 "
            + narrowing;
    }

    private String planAsTheService(String sql) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            StringBuilder plan = new StringBuilder();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
            c.rollback();
            return plan.toString();
        }
    }
}
