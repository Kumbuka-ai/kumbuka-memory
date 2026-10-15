/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.PageCursor;
import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.domain.SortKey;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner walks an index in the listing's order and sorts nothing, and
 * reads rows in the order of magnitude of the page: asked about the page
 * statement the service issues, as the service role, with the tenant bound,
 * on a scope of two selectors of 3000 entries each among 3000 entries of a
 * neighbouring tenant.
 *
 * <h2>The statement is the one that runs</h2>
 *
 * Hibernate's statement for the page is recorded by {@link RecordedStatements}
 * and handed to {@code PREPARE} unchanged; {@code EXPLAIN (ANALYZE) EXECUTE}
 * then runs it with the values the page binds, in the order they stand in the
 * statement. A statement written by hand to resemble it would prove the plan
 * of a statement nobody sends.
 *
 * <h2>What is held</h2>
 *
 * For both orders, both directions, the first page and a continued one,
 * narrowed to a selector and over the whole scope: no {@code Sort} node, and
 * no scan that reads more than {@link #READ_BOUND} rows for a page of
 * {@link #PAGE} — the rows a node returned plus the rows its filter removed.
 * A sort would read the whole selector, 3000 rows, before it returned one.
 *
 * <p>The count of unaddressable entries every page carries is held to the
 * same bound.
 *
 * <h2>The red run this probe was built against, observed on 2026-10-13</h2>
 *
 * On the state with V6's two indexes and the cursor written as a disjunction,
 * nine of sixteen listings failed: key order over the whole scope was a
 * sequential scan of 9000 rows and a sort, in both directions, with and
 * without a cursor; creation order narrowed to a selector and continued
 * walked V6's creation index, which carries no selector, and its filter
 * removed 3000 to 5300 rows; creation order over the whole scope, continued,
 * walked from the first row because the disjunction is no index condition
 * (1000 to 4200 rows removed); and key order in a selector, backward, passed
 * over the neighbour's 3000 entries because V6's selector index does not
 * begin with the tenant. Without V7's partial index the count was a
 * sequential scan that removed all 9000 rows by its filter.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PageListingPlanIT {

    private static final String TENANT = SubstrateDatabaseResource.TENANT_ID;
    private static final String NEIGHBOUR = SubstrateDatabaseResource.OTHER_TENANT_ID;
    private static final String SCOPE = SubstrateDatabaseResource.SCOPE_ID;
    private static final String CALLER = SubstrateDatabaseResource.PROBE_SUBJECT;

    /** Entries per selector: enough that a sort costs the planner more than a walk. */
    private static final int ENTRIES = 3000;
    private static final int PAGE = 50;

    /**
     * The most rows one scan may read for a page: ten pages. A walk in order
     * reads the page and what its filters pass over — the neighbour's rows and
     * the other selector's, about one page each here. A sort reads the whole
     * selector first.
     */
    private static final int READ_BOUND = 10 * (PAGE + 1);

    private static final String MARK = ".plan-";

    @Inject MemoryRepository entries;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anAnalysedEstate() throws SQLException {
        removeTheEstate();
        try (Connection c = asAdmin(); Statement s = c.createStatement()) {
            seed(s, TENANT, "decision", "a");
            seed(s, TENANT, "convention", "a");
            seed(s, NEIGHBOUR, "decision", "n");
            s.execute("ANALYZE memory.memory");
        }
    }

    @AfterEach
    void removeTheEstate() throws SQLException {
        try (Connection c = asAdmin(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM memory.memory WHERE key LIKE '%" + MARK + "%'");
        }
    }

    static Stream<Arguments> listings() {
        List<Arguments> all = new ArrayList<>();
        for (String selector : new String[] {"decision", null}) {
            for (SortKey sort : SortKey.values()) {
                for (SortKey.Direction direction : SortKey.Direction.values()) {
                    for (boolean continued : new boolean[] {false, true}) {
                        all.add(Arguments.of(selector, sort, direction, continued));
                    }
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "selector={0} sort={1} {2} continued={3}")
    @MethodSource("listings")
    void a_page_walks_an_index_in_order_and_reads_about_a_page(
            String selector, SortKey sort, SortKey.Direction direction, boolean continued)
            throws SQLException {
        PageCursor after = continued ? middleOf(selector) : null;
        List<String> sql = statementsOf(selector, sort, direction, after);

        List<String> values = new ArrayList<>(List.of(TENANT, SCOPE, CALLER));
        if (selector != null) {
            values.add(selector);
        }
        if (after != null) {
            if (sort == SortKey.CREATED_AT) {
                values.add(after.createdAt().toString());
            }
            values.add(after.key());
        }
        values.add(String.valueOf(PAGE + 1));

        String plan = explain(statement(sql, "order by"), values);

        assertThat(plan).as("no sort node:%n%s", plan).doesNotContain("Sort");
        assertThat(readByTheWidestScan(plan))
            .as("rows read by the widest scan, for a page of %d:%n%s", PAGE, plan)
            .isLessThanOrEqualTo(READ_BOUND);
        assertThat(returnedRows(plan)).as("the page and the one beyond it:%n%s", plan)
            .isEqualTo(PAGE + 1);
    }

    /**
     * The count of unaddressable entries every page carries reads none of
     * the scope's ordinary entries.
     */
    @Test
    void the_count_a_page_carries_reads_no_ordinary_entry() throws SQLException {
        List<String> sql = statementsOf("decision", SortKey.ID, SortKey.Direction.ASC, null);

        String plan = explain(statement(sql, "count("), List.of(TENANT, SCOPE, CALLER));

        assertThat(readByTheWidestScan(plan))
            .as("rows read to count what the listing cannot address:%n%s", plan)
            .isLessThanOrEqualTo(READ_BOUND);
    }

    // ------------------------------------------------------------------

    /** The statements the repository prepares for one page, as Hibernate wrote them. */
    private List<String> statementsOf(String selector, SortKey sort,
                                      SortKey.Direction direction, PageCursor after) {
        RecordedStatements.start();
        entries.page(new MemoryRepository.Keyset(UUID.fromString(SCOPE), selector,
            CALLER, new QueryFilter(null, null),
            new PageRequest(null, sort, direction, PAGE), after));
        return RecordedStatements.recorded();
    }

    /** The one statement on {@code memory.memory} that carries a marker. */
    private static String statement(List<String> recorded, String marker) {
        return recorded.stream()
            .filter(sql -> sql.contains("from memory.memory") && sql.contains(marker))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no statement with '" + marker
                + "' among " + recorded));
    }

    /** A position in the middle of the listing, as a cursor would carry it. */
    private static PageCursor middleOf(String selector) throws SQLException {
        String key = (selector == null ? "decision" : selector) + MARK + "a" + (ENTRIES / 2);
        try (Connection c = asAdmin(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT created_at FROM memory.memory WHERE key = '"
                 + key + "' AND tenant_id = '" + TENANT + "'")) {
            assertThat(rs.next()).as(key).isTrue();
            Instant at = rs.getTimestamp(1).toInstant();
            return new PageCursor(null, key, at);
        }
    }

    /**
     * The plan of a statement as the service role runs it, with the tenant
     * bound and the values given for its parameters in their order.
     */
    private static String explain(String sql, List<String> values) throws SQLException {
        StringBuilder numbered = new StringBuilder();
        int parameters = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') {
                numbered.append('$').append(++parameters);
            } else {
                numbered.append(ch);
            }
        }
        assertThat(parameters).as("the parameters of %s", sql).isEqualTo(values.size());
        List<String> literals = values.stream().map(v -> "'" + v + "'").toList();
        try (Connection c = DriverManager.getConnection(config("test.db.url"),
                SubstrateDatabaseResource.SERVICE_ROLE,
                SubstrateDatabaseResource.SERVICE_PASSWORD);
             Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', true)");
                s.execute("PREPARE page_probe AS " + numbered);
                List<String> plan = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, TIMING OFF, SUMMARY OFF) "
                        + "EXECUTE page_probe(" + String.join(", ", literals) + ")")) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
                return String.join("\n", plan);
            } finally {
                c.rollback();
            }
        }
    }

    private static final Pattern NODE_ROWS =
        Pattern.compile("actual rows=(\\d+) loops=(\\d+)");
    private static final Pattern REMOVED =
        Pattern.compile("Rows Removed by (?:Filter|Index Recheck): (\\d+)");

    /**
     * The most rows any one scan read: what it returned plus what its filter
     * removed, over all its loops.
     */
    static long readByTheWidestScan(String plan) {
        long widest = 0;
        long current = -1;
        for (String line : plan.split("\n")) {
            Matcher node = NODE_ROWS.matcher(line);
            if (node.find()) {
                widest = Math.max(widest, current);
                current = line.contains("Scan")
                    ? Long.parseLong(node.group(1)) * Long.parseLong(node.group(2))
                    : -1;
                continue;
            }
            Matcher removed = REMOVED.matcher(line);
            if (removed.find() && current >= 0) {
                current += Long.parseLong(removed.group(1));
            }
        }
        return Math.max(widest, current);
    }

    /** The rows the top node of the plan returned. */
    private static long returnedRows(String plan) {
        Matcher top = NODE_ROWS.matcher(plan.split("\n")[0]);
        assertThat(top.find()).as(plan).isTrue();
        return Long.parseLong(top.group(1));
    }

    /** Entries under one selector, laid down at scattered instants over twelve days. */
    private static void seed(Statement s, String tenant, String selector, String prefix)
            throws SQLException {
        s.execute("INSERT INTO memory.memory"
            + " (logical_id, version, tenant_id, scope_id, is_private, owner_subject,"
            + "  type, key, content, source, created_at, updated_at)"
            + " SELECT gen_random_uuid(), 1, '" + tenant + "', '" + SCOPE + "', false,"
            + " 'probe', 'decision', '" + selector + MARK + prefix + "' || n,"
            + " 'entry ' || n, 'mcp',"
            + " TIMESTAMPTZ '2026-01-01 00:00:00+00' + (random() * 1000000)::int * interval '1 second',"
            + " TIMESTAMPTZ '2026-01-01 00:00:00+00'"
            + " FROM generate_series(1, " + ENTRIES + ") AS n");
    }

    private static Connection asAdmin() throws SQLException {
        return DriverManager.getConnection(config("test.db.url"),
            config("test.db.admin.username"), config("test.db.admin.password"));
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
