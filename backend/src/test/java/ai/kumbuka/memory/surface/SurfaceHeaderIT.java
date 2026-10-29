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
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST surface with {@code Kumbuka-Surface: assistant}: it answers in the
 * vocabulary of the assistant surface, and that is all the header changes.
 *
 * <p>Each comparison is a JSON value against a JSON value — the body of the
 * REST answer against {@code structuredContent} of the service's own MCP
 * adapter, for the same call by the same identity at the same entry. Where a
 * write happens once, the REST answer of the write is held against the MCP
 * adapter's read of the entry it left, which answers in the same shape.
 *
 * <p>A value the header cannot take is {@code HEADER_INVALID} 400 with no
 * {@code data}, decided after the caller is authenticated and before anything
 * else: before the route, the address, the scope, the body and the entry
 * (C14.4, C14.5). The MCP adapter does not read the header at all.
 *
 * <p>RED STATE, observed 2026-10-29 against the tree before this run, with
 * this class copied in: the header was read on {@code query} alone, so
 * {@link #with_the_header_rest_answers_what_the_assistant_surface_answers}
 * turned red at its first comparison, on {@code next[0].call} of the read;
 * and an unknown value was refused as {@code ARGUMENT_INVALID} on the listing
 * only, after the route was found, so
 * {@link #an_unknown_value_is_header_invalid_and_writes_nothing} and
 * {@link #the_header_is_decided_before_anything_else} turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceHeaderIT {

    private static final String HEADER = "Kumbuka-Surface";
    private static final String ASSISTANT = "assistant";
    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "decision.storage-engine";
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/storage-engine";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage-engine";
    private static final String COLLECTION = API + "/" + SCOPE + "/decision";
    private static final String COUNT = "SELECT count(*) FROM memory.memory";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntry() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "decision", "postgres"));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void with_the_header_rest_answers_what_the_assistant_surface_answers() {
        Response read = assistant().get(ITEM);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(body(read)).as("read")
            .isEqualTo(Mcp.call("memory_read", args("address", ADDRESS)).structured());

        Response listed = assistant().get(COLLECTION + "?page_size=2");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(body(listed)).as("query")
            .isEqualTo(Mcp.call("memory_query", args("scope", SCOPE, "selector", "decision",
                "page_size", 2)).structured());

        Response rejected = assistant().get(COLLECTION + "?page_size=abc");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(body(rejected)).as("a refusal of an argument")
            .isEqualTo(Mcp.call("memory_query", args("scope", SCOPE, "selector", "decision",
                "page_size", "abc")).structured());

        String token = read.header("ETag");
        Response updated = assistant().header("If-Match", token)
            .body(fields("content", "postgres 16")).patch(ITEM);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(body(updated)).as("update, as the entry it left reads")
            .isEqualTo(Mcp.call("memory_read", args("address", ADDRESS)).structured());

        Response stale = assistant().header("If-Match", token)
            .body(fields("content", "postgres 17")).patch(ITEM);
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(stale.jsonPath().getList("data.next.call", String.class))
            .isNotEmpty().allMatch(call -> call.startsWith("memory_"));
        assertThat(body(stale)).as("a refusal for the state of the entry")
            .isEqualTo(Mcp.call("memory_update", args("address", ADDRESS,
                "conflict_token", token, "fields", Map.of("content", "postgres 17")))
                .structured());

        Response missing = assistant().get(API + "/" + SCOPE + "/decision/nothing-here");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing)).as("NOT_FOUND")
            .isEqualTo(Mcp.call("memory_read", args("address",
                "memory://" + SCOPE + "/decision/nothing-here")).structured());

        Response withdrawn = assistant()
            .header("If-Match", updated.jsonPath().getString("conflict_token"))
            .post(ITEM + ":withdraw");
        assertThat(withdrawn.statusCode()).isEqualTo(200);
        assertThat(withdrawn.jsonPath().getList("next.call", String.class))
            .as("withdraw").allMatch(call -> call.startsWith("memory_"));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_unknown_value_is_header_invalid_and_writes_nothing() {
        for (String value : List.of("mcp", "Assistant", "rest", "")) {
            long before = SurfaceFixture.countRows(COUNT);
            Response refused = call().header(HEADER, value)
                .body(fields("key", "decision.another", "type", "decision", "content", "x"))
                .post(COLLECTION);
            headerInvalid(refused, "'" + value + "'");
            assertThat(SurfaceFixture.countRows(COUNT)).as("'%s' wrote nothing", value)
                .isEqualTo(before);
        }

        long before = SurfaceFixture.countRows(COUNT);
        Response twice = call().header(HEADER, ASSISTANT).header(HEADER, ASSISTANT)
            .body(fields("key", "decision.another", "type", "decision", "content", "x"))
            .post(COLLECTION);
        headerInvalid(twice, "the header twice");
        assertThat(SurfaceFixture.countRows(COUNT)).as("twice wrote nothing").isEqualTo(before);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void the_header_is_decided_before_anything_else() {
        headerInvalid(wrong().post(ITEM + ":invented"),
            "before the route: a verb not carried");
        headerInvalid(wrong().delete(COLLECTION), "before the route: a method not carried");
        headerInvalid(wrong().get(API + "/" + SCOPE + "/decision/nothing-here"),
            "before the address: an entry that is not there");
        headerInvalid(wrong().get(API + "/Not A Scope/decision"),
            "before the grammar: a scope out of it");
        headerInvalid(wrong().get(API + "/" + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG
            + "/decision"), "before the scope: a scope the caller does not see");
        headerInvalid(wrong().body("[not json").post(COLLECTION),
            "before the body: no JSON object");
        headerInvalid(wrong().get(COLLECTION + "?bogus=x"),
            "before the arguments: one the call does not declare");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void the_mcp_adapter_does_not_read_the_header() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "memory_read",
            "arguments", Map.of("address", ADDRESS)));

        for (Headers headers : List.of(new Headers(new Header(HEADER, "mcp")),
                new Headers(new Header(HEADER, ASSISTANT), new Header(HEADER, ASSISTANT)))) {
            Response read = call().headers(headers).body(envelope).post(Mcp.PATH);
            assertThat(read.statusCode()).as("%s", headers).isEqualTo(200);
            assertThat(read.jsonPath().getMap("result.structuredContent")).as("%s", headers)
                .isEqualTo(Mcp.call("memory_read", args("address", ADDRESS)).structured());
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void the_header_decides_nothing_but_the_names() {
        long before = SurfaceFixture.countRows(COUNT);
        String body = fields("key", "decision.another", "type", "decision", "content", "x");

        Response without = call().body(body).post(COLLECTION);
        Response with = assistant().body(body).post(COLLECTION);

        assertThat(without.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        assertThat(with.statusCode()).isEqualTo(without.statusCode());
        assertThat(with.jsonPath().getString("reason"))
            .isEqualTo(without.jsonPath().getString("reason"));
        assertThat(without.jsonPath().getString("data.attempted")).isEqualTo("create");
        assertThat(with.jsonPath().getString("data.attempted")).isEqualTo("memory_create");
        assertThat(with.asString().replace("\"memory_create\"", "\"create\""))
            .as("and nothing but the names")
            .isEqualTo(without.asString());
        assertThat(SurfaceFixture.countRows(COUNT)).as("neither wrote").isEqualTo(before);
    }

    // -----------------------------------------------------------------------

    private static RequestSpecification assistant() {
        return call().header(HEADER, ASSISTANT);
    }

    private static RequestSpecification wrong() {
        return call().header(HEADER, "mcp");
    }

    /** {@code HEADER_INVALID} 400: the header and its one value named, and no data. */
    private static void headerInvalid(Response refused, String what) {
        assertThat(refused.statusCode()).as("%s: %s", what, refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).as(what).isEqualTo("HEADER_INVALID");
        assertThat(refused.jsonPath().getString("message")).as(what)
            .contains(HEADER).contains(ASSISTANT);
        assertThat(body(refused)).as(what + ": no data").doesNotContainKey("data");
    }

    private static Map<String, Object> body(Response response) {
        return response.jsonPath().getMap("$");
    }
}
