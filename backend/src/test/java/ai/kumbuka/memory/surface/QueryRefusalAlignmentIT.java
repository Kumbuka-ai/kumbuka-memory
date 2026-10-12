/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.contract.Mcp;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paging refusals of {@code query}, one condition per code, on both
 * surfaces (ADR-0049, DEC-0042).
 *
 * <h2>What is held, case by case</h2>
 *
 * The same condition answers the same code on REST and on the service's own
 * MCP adapter, the REST status is 400 because every one of them is decidable
 * without a scope, and {@code data.offenders} names the argument and never the
 * value the caller sent. Each case is asked on both surfaces in one probe, so
 * a surface that drifts from the other turns that probe red rather than
 * another one somewhere else.
 *
 * <h2>The red run this probe was built against, observed on 2026-10-12</h2>
 *
 * On the state before the alignment: an unknown argument answered 422
 * {@code PREDICATE_UNKNOWN} on REST; {@code page_size} of {@code abc} and
 * {@code 7.5} answered {@code ARGUMENT_INVALID} on MCP; an unknown direction
 * answered {@code SORT_UNDECLARED}; {@code SORT_UNDECLARED} named the value
 * {@code title} as its offender; the page-size and cursor refusals carried no
 * {@code offenders} at all; and the listing answered {@code entries} and
 * {@code page_size}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class QueryRefusalAlignmentIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String QUERY = "memory_query";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void aScopeWithMoreThanOnePage() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID, "decision", "decision", 5);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID, "convention", "convention",
            3);
    }

    // ======================================================================
    // Criterion 2: an unknown argument
    // ======================================================================

    @Test
    void an_unknown_argument_is_argument_unknown_on_both_surfaces() {
        Response rest = call().get(API + "/" + SCOPE + "?limit=5&cursor=x&foo=1");
        assertRest(rest, "ARGUMENT_UNKNOWN", List.of("cursor", "foo", "limit"));

        for (String name : List.of("limit", "cursor", "foo")) {
            assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, name, "5")),
                "ARGUMENT_UNKNOWN", List.of(name));
        }
    }

    // ======================================================================
    // Criterion 3: every page size the verb cannot take
    // ======================================================================

    @Test
    void every_invalid_page_size_is_page_size_rejected_on_both_surfaces() {
        for (String size : List.of("0", "201", "abc", "7.5")) {
            assertRest(call().get(API + "/" + SCOPE + "?page_size=" + size),
                "PAGE_SIZE_REJECTED", List.of("page_size"));
        }
        for (Object size : List.of(0, 201, "abc", 7.5)) {
            assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, "page_size", size)),
                "PAGE_SIZE_REJECTED", List.of("page_size"));
        }
    }

    // ======================================================================
    // Criterion 4: a direction that is neither of the two
    // ======================================================================

    @Test
    void a_direction_that_is_neither_of_the_two_is_argument_invalid_on_both_surfaces() {
        assertRest(call().get(API + "/" + SCOPE + "?direction=sideways"),
            "ARGUMENT_INVALID", List.of("direction"));
        assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, "direction", "sideways")),
            "ARGUMENT_INVALID", List.of("direction"));
    }

    // ======================================================================
    // Criterion 5: offenders name the argument, never the value
    // ======================================================================

    @Test
    void an_undeclared_order_names_the_argument_and_not_the_value() {
        assertRest(call().get(API + "/" + SCOPE + "?sort=title"),
            "SORT_UNDECLARED", List.of("sort"));
        assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, "sort", "title")),
            "SORT_UNDECLARED", List.of("sort"));
    }

    @Test
    void a_malformed_cursor_names_after() {
        assertRest(call().get(API + "/" + SCOPE + "?after=not-a-cursor!!"),
            "CURSOR_MALFORMED", List.of("after"));
        assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, "after", "not-a-cursor!!")),
            "CURSOR_MALFORMED", List.of("after"));
    }

    @Test
    void a_foreign_cursor_names_after() {
        String cursor = call().get(API + "/" + SCOPE + "/decision?page_size=2")
            .jsonPath().getString("next_cursor");
        assertThat(cursor).isNotBlank();

        assertRest(call().get(API + "/" + SCOPE + "/convention?after=" + cursor),
            "CURSOR_FOREIGN", List.of("after"));
        assertMcp(Mcp.call(QUERY, Mcp.args("scope", SCOPE, "selector", "convention",
                "after", cursor)),
            "CURSOR_FOREIGN", List.of("after"));
    }

    // ======================================================================
    // Criterion 6: the page
    // ======================================================================

    @Test
    void a_page_carries_objects_and_does_not_repeat_the_request_on_both_surfaces() {
        Response rest = call().get(API + "/" + SCOPE + "?page_size=3");
        assertThat(rest.statusCode()).isEqualTo(200);
        assertThat(rest.jsonPath().getMap("$"))
            .as("objects, next_cursor and unaddressable; no entries, no page_size echo")
            .containsOnlyKeys("objects", "next_cursor", "unaddressable");
        assertThat(rest.jsonPath().getList("objects")).hasSize(3);

        Mcp.Result mcp = Mcp.call(QUERY, Mcp.args("scope", SCOPE, "page_size", 3));
        assertThat(mcp.isError()).as("%s", mcp).isFalse();
        assertThat(mcp.structured())
            .containsOnlyKeys("objects", "next_cursor", "unaddressable");
        assertThat(mcp.strings("objects.address")).hasSize(3);
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static void assertRest(Response answer, String reason, List<String> offenders) {
        assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).as("%s", answer.asString())
            .isEqualTo(reason);
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .as("the arguments, never their values (DEC-0042) — %s", answer.asString())
            .isEqualTo(offenders);
    }

    private static void assertMcp(Mcp.Result answer, String reason, List<String> offenders) {
        assertThat(answer.isError()).as("%s", answer).isTrue();
        assertThat(answer.reason()).as("%s", answer).isEqualTo(reason);
        Map<String, Object> data = answer.map("data");
        assertThat(data.get("offenders"))
            .as("the arguments, never their values (DEC-0042) — %s", answer)
            .isEqualTo(offenders);
        assertThat(data.get("attempted"))
            .as("attempted stays where it stood")
            .isEqualTo(QUERY);
    }
}
