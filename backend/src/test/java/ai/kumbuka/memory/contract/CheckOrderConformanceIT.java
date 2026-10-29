/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static ai.kumbuka.memory.contract.Mcp.args;
import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check order: grammar and argument form, then the scope's visibility,
 * then the vocabulary, then the entry (C1.3, DEC-0042). A scope the caller
 * cannot see answers {@code NOT_FOUND} whatever else is wrong with the call;
 * grammar and argument form are excepted and answer first.
 *
 * <p>Each case is a call on a scope of another tenant that carries one further
 * defect, of every kind this service decides after the scope: a type outside
 * the entry types, a field fixed for the life of an entry, a cursor the
 * service did not hand out, a stale conflict token, a missing one. Each
 * answers {@code NOT_FOUND}, on both surfaces: answering the defect instead
 * would tell the caller the scope is there. The grammar and form cases stand
 * beside them: an address out of the grammar and an argument the call does not
 * declare are refused as such, which says nothing about any scope.
 *
 * <p>Of two write refusals the lock is decided first: a locked scope refuses
 * a muted member with {@code SCOPE_LOCKED}, never {@code SCOPE_READ_ONLY}, so
 * that the order never leaves the code unreachable (C8.7).
 *
 * <p>RED STATE, observed 2026-10-29, one planted violation per run: with the
 * type checked before the scope was resolved, the query on an invisible scope
 * answered {@code TYPE_UNKNOWN}; with the fixed fields refused before it, the
 * create answered {@code FIELD_IMMUTABLE} 422; with the conflict token required
 * by the MCP argument check, the update answered {@code ARGUMENT_MISSING};
 * with the write right checked before the lock, the muted member answered
 * {@code SCOPE_READ_ONLY} 403; and with the REST catch-all unregistered, the
 * framework's 404 came without the envelope.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CheckOrderConformanceIT {

    private static final String INVISIBLE = NotFoundIdentityIT.INVISIBLE_SCOPE;
    private static final String VISIBLE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String ADDRESS = "memory://" + INVISIBLE + "/decision/storage-engine";
    private static final String ITEM = API + "/" + INVISIBLE + "/decision/storage-engine";
    private static final String UNKNOWN_TYPE = "no-such-type";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void noEntries() {
        SurfaceFixture.clearEntries();
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_invisible_scope_answers_not_found_whatever_else_the_call_gets_wrong() {
        Map<String, Map<String, Object>> overMcp = new LinkedHashMap<>();
        overMcp.put("memory_create a type outside the entry types", args("scope", INVISIBLE,
            "selector", "decision", "fields", Map.of("key", "decision.storage-engine",
                "type", UNKNOWN_TYPE, "content", "x")));
        overMcp.put("memory_query a type outside the entry types",
            args("scope", INVISIBLE, "type", UNKNOWN_TYPE));
        overMcp.put("memory_query a cursor not handed out",
            args("scope", INVISIBLE, "after", "no-cursor"));
        overMcp.put("memory_update without the conflict token",
            args("address", ADDRESS, "fields", Map.of("content", "x")));
        overMcp.put("memory_update a stale conflict token",
            args("address", ADDRESS, "conflict_token", "stale", "fields", Map.of("content", "x")));
        overMcp.put("memory_withdraw a stale conflict token",
            args("address", ADDRESS, "conflict_token", "stale"));
        overMcp.put("memory_digest a type outside the entry types",
            args("scope", INVISIBLE, "types", List.of(UNKNOWN_TYPE)));
        overMcp.forEach((what, arguments) -> assertThat(
                Mcp.call(what.substring(0, what.indexOf(' ')), arguments).string("reason"))
            .as("%s over MCP", what).isEqualTo("NOT_FOUND"));

        Map<String, Supplier<Response>> overRest = new LinkedHashMap<>();
        overRest.put("create a type outside the entry types", () -> call().body(fields(
            "key", "decision.storage-engine", "type", UNKNOWN_TYPE, "content", "x"))
            .post(API + "/" + INVISIBLE + "/decision"));
        overRest.put("create naming a fixed field", () -> call().body(fields(
            "key", "decision.storage-engine", "type", "decision", "content", "x",
            "state", "draft")).post(API + "/" + INVISIBLE + "/decision"));
        overRest.put("query a type outside the entry types",
            () -> call().get(API + "/" + INVISIBLE + "/decision?type=" + UNKNOWN_TYPE));
        overRest.put("query a cursor not handed out",
            () -> call().get(API + "/" + INVISIBLE + "?after=no-cursor"));
        overRest.put("update without the conflict token",
            () -> call().body(fields("content", "x")).patch(ITEM));
        overRest.put("update a stale conflict token", () -> call().header("If-Match", "stale")
            .body(fields("content", "x")).patch(ITEM));
        overRest.put("update naming a fixed field", () -> call().header("If-Match", "stale")
            .body(fields("scope", "elsewhere")).patch(ITEM));
        overRest.put("withdraw a stale conflict token",
            () -> call().header("If-Match", "stale").post(ITEM + ":withdraw"));
        overRest.put("digest a type outside the entry types", () -> call()
            .body("{\"types\":[\"" + UNKNOWN_TYPE + "\"]}").post(API + "/" + INVISIBLE + ":digest"));
        overRest.forEach((what, request) -> {
            Response answer = request.get();
            assertThat(List.of(answer.statusCode(), answer.jsonPath().getString("reason")))
                .as("%s over REST: %s", what, answer.asString())
                .isEqualTo(List.of(404, "NOT_FOUND"));
        });
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void grammar_is_decided_before_the_scope_and_says_nothing_about_it() {
        Mcp.Result invisible = Mcp.call("memory_read",
            args("address", "memory://" + INVISIBLE + "/decision"));
        assertThat(invisible.string("reason")).isEqualTo("ADDRESS_MALFORMED");
        assertThat(invisible.strings("data.offenders"))
            .as("the declared name of the part that fails the grammar (C8.7)")
            .containsExactly("address");

        Mcp.Result visible = Mcp.call("memory_read",
            args("address", "memory://" + VISIBLE + "/decision"));
        assertThat(invisible.structured())
            .as("the same refusal whether the scope is visible or not")
            .isEqualTo(visible.structured());

        Response overRest = call().get(API + "/Not A Scope/decision");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ADDRESS_MALFORMED");
        assertThat(overRest.jsonPath().getList("data.offenders", String.class))
            .containsExactly("scope");
        assertThat(overRest.jsonPath().getString("data.attempted"))
            .as("the route stands, so the refusal names the call").isEqualTo("query");
    }

    /**
     * The form of an argument is checked before any scope is resolved (C1.2,
     * C1.3): a parameter the query does not declare, on a scope the caller
     * cannot see, is {@code ARGUMENT_UNKNOWN} 400 with {@code data.accepts},
     * not {@code NOT_FOUND}. The refusal is the same whether the scope is
     * there or not.
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_form_fault_on_an_invisible_scope_is_decided_before_the_scope() {
        Response overRest = call().get(API + "/" + INVISIBLE + "/decision?bogus=x");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(overRest.jsonPath().getList("data.accepts", String.class)).isNotEmpty();

        Response visible = call().get(API + "/" + VISIBLE + "/decision?bogus=x");
        assertThat(overRest.asString()).as("the same refusal whether the scope is there or not")
            .isEqualTo(visible.asString());

        Mcp.Result overMcp = Mcp.call("memory_query", args("scope", INVISIBLE, "bogus", "x"));
        assertThat(overMcp.string("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        Response size = call().get(API + "/" + INVISIBLE + "?page_size=abc");
        assertThat(List.of(size.statusCode(), size.jsonPath().getString("reason")))
            .as("a page size that is no number: %s", size.asString())
            .isEqualTo(List.of(400, "PAGE_SIZE_REJECTED"));
    }

    /**
     * The lock is a state of the scope and holds for every caller; the
     * read-only right is a property of the caller. A member without the write
     * right, writing to a locked scope, is told the scope is locked (C8.7).
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void a_locked_scope_and_a_muted_member_at_once_answer_scope_locked() {
        Response overRest = call().body(fields("key", "decision.another", "type", "decision",
            "content", "x")).post(API + "/" + LOCKED + "/decision");
        assertThat(List.of(overRest.statusCode(), overRest.jsonPath().getString("reason")))
            .as(overRest.asString()).isEqualTo(List.of(409, "SCOPE_LOCKED"));

        Mcp.Result overMcp = Mcp.call("memory_create", args("scope", LOCKED,
            "selector", "decision", "fields", Map.of("key", "decision.another",
                "type", "decision", "content", "x")));
        assertThat(overMcp.string("reason")).isEqualTo("SCOPE_LOCKED");

        Response readOnly = call().body(fields("key", "decision.another", "type", "decision",
            "content", "x")).post(API + "/" + VISIBLE + "/decision");
        assertThat(readOnly.jsonPath().getString("reason"))
            .as("and the same member in an open scope is told about its right")
            .isEqualTo("SCOPE_READ_ONLY");
    }

    /**
     * A route the service does not carry answers in the envelope (C3.3, C8.3):
     * the framework's 404 as {@code NOT_FOUND} without {@code data}, its 405 as
     * {@code VERB_NOT_CARRIED} with {@code Allow} and {@code data.accepts}.
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_route_the_framework_does_not_carry_answers_in_the_envelope() {
        Response nothing = call().get(API + "/" + VISIBLE + "/decision/storage-engine/more");
        assertThat(nothing.statusCode()).as(nothing.asString()).isEqualTo(404);
        assertThat(nothing.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
        assertThat(nothing.jsonPath().getMap("$")).as("no data").doesNotContainKey("data");

        Response put = call().body("{}").put(API + "/" + VISIBLE + "/decision/storage-engine");
        assertThat(put.statusCode()).as(put.asString()).isEqualTo(405);
        assertThat(put.jsonPath().getString("reason")).isEqualTo("VERB_NOT_CARRIED");
        assertThat(put.header("Allow")).as("the methods the route carries").isNotBlank();
        assertThat(put.jsonPath().getList("data.accepts", String.class))
            .as("the verbs carried at the address, in the REST names")
            .containsExactly("read", "update", "withdraw");

        Response verb = call().post(API + "/" + VISIBLE + "/decision/storage-engine:invented");
        assertThat(verb.statusCode()).isEqualTo(405);
        assertThat(verb.jsonPath().getList("data.accepts", String.class))
            .as("a colon verb the address does not carry names them too (C3.3)")
            .containsExactly("read", "update", "withdraw");
    }

    /** The security layer's 401 is handed back as it is (DEC-0042). */
    @Test
    void an_unauthenticated_call_gets_the_challenge_and_not_the_envelope() {
        Response unauthenticated = call().get(API + "/" + VISIBLE + "/decision");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.asString()).doesNotContain("\"reason\"");
    }
}
