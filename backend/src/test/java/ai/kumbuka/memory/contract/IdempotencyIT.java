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

import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency key of {@code create} (C10.4, C8.7): a repeat with the same
 * key and the same values answers the first result while it stands; other
 * values, or a first result that no longer stands, are refused with
 * {@code IDEMPOTENCY_KEY_REUSED} 409, and the call is never carried out a
 * second time.
 *
 * <p>On both surfaces: the header {@code Idempotency-Key} on REST, the
 * argument {@code idempotency_key} on MCP. The key carries the marked value,
 * and no answer gives it back (C8.8).
 *
 * <p>RED STATE, observed 2026-10-29 with a violation planted for the run: on
 * the path of a repeat whose entry is gone, a new entry was laid down instead
 * of the refusal (as the reference service does today). Both cases of a
 * withdrawn entry turned red, the repeat answering 201 with a new entry where
 * 409 {@code IDEMPOTENCY_KEY_REUSED} and no entry were expected.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class IdempotencyIT {

    /** The marked value: a key no answer may repeat. */
    static final String MARK = "zz-marked-key-4711";

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String COLLECTION = SurfaceFixture.API + "/" + SCOPE + "/decision";
    private static final String HEADER = "Idempotency-Key";
    private static final String ENTRIES = "SELECT count(*) FROM memory.memory";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void emptyStore() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.run("DELETE FROM memory.idempotency_key");
    }

    // ======================================================================
    // REST
    // ======================================================================

    @Test
    void over_rest_the_same_values_answer_the_first_result() {
        Response first = restCreate(MARK, "the first text");
        Response repeat = restCreate(MARK, "the first text");

        assertThat(first.statusCode()).as(first.asString()).isEqualTo(201);
        assertThat(repeat.statusCode()).as(repeat.asString()).isEqualTo(201);
        assertThat(repeat.jsonPath().getString("address"))
            .isEqualTo(first.jsonPath().getString("address"));
        assertThat(repeat.jsonPath().getString("conflict_token"))
            .isEqualTo(first.jsonPath().getString("conflict_token"));
        assertThat(SurfaceFixture.countRows(ENTRIES)).as("one entry, laid down once")
            .isEqualTo(1);
    }

    @Test
    void over_rest_the_repeat_answers_the_entry_as_it_stands_now() {
        Response first = restCreate(MARK, "the first text");
        Response changed = SurfaceFixture.call()
            .header("If-Match", first.header("ETag"))
            .body(SurfaceFixture.fields("content", "a later text"))
            .patch(SurfaceFixture.API + "/" + SCOPE + "/decision/storage-engine");
        assertThat(changed.statusCode()).as(changed.asString()).isEqualTo(200);

        Response repeat = restCreate(MARK, "the first text");
        assertThat(repeat.statusCode()).as(repeat.asString()).isEqualTo(201);
        assertThat(repeat.jsonPath().getString("fields.content")).isEqualTo("a later text");
    }

    @Test
    void over_rest_other_values_under_a_spent_key_are_refused() {
        restCreate(MARK, "the first text");
        Response other = restCreate(MARK, "another text");

        assertReused(other.statusCode(), other.jsonPath().getMap("$"), other.asString());
        assertThat(SurfaceFixture.countRows(ENTRIES)).isEqualTo(1);
    }

    @Test
    void over_rest_a_repeat_after_the_withdrawal_is_refused_and_lays_nothing_down() {
        Response first = restCreate(MARK, "the first text");
        Response withdrawn = SurfaceFixture.call()
            .header("If-Match", first.header("ETag"))
            .post(SurfaceFixture.API + "/" + SCOPE + "/decision/storage-engine:withdraw");
        assertThat(withdrawn.statusCode()).as(withdrawn.asString()).isEqualTo(200);

        Response repeat = restCreate(MARK, "the first text");
        assertReused(repeat.statusCode(), repeat.jsonPath().getMap("$"), repeat.asString());
        assertThat(SurfaceFixture.countRows(ENTRIES))
            .as("the withdrawn entry is not laid down again (C10.4)").isZero();
    }

    @Test
    void over_rest_a_key_older_than_24_hours_is_free_again() {
        Response first = restCreate(MARK, "the first text");
        SurfaceFixture.call().header("If-Match", first.header("ETag"))
            .post(SurfaceFixture.API + "/" + SCOPE + "/decision/storage-engine:withdraw");
        SurfaceFixture.run("UPDATE memory.idempotency_key "
            + "SET first_seen_at = first_seen_at - interval '25 hours'");

        Response again = restCreate(MARK, "the first text");
        assertThat(again.statusCode()).as(again.asString()).isEqualTo(201);
        assertThat(SurfaceFixture.countRows(ENTRIES)).isEqualTo(1);
        assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.idempotency_key"))
            .as("the expired row is replaced in place, not joined by a second")
            .isEqualTo(1);
    }

    @Test
    void without_a_key_a_repeat_answers_as_before() {
        Response first = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "decision.storage-engine", "type", "decision",
                "content", "the first text"))
            .post(COLLECTION);
        Response repeat = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "decision.storage-engine", "type", "decision",
                "content", "the first text"))
            .post(COLLECTION);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(repeat.statusCode()).isEqualTo(409);
        assertThat(repeat.jsonPath().getString("reason")).isEqualTo("ALREADY_EXISTS");
        assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.idempotency_key"))
            .as("a create without a key spends none").isZero();
    }

    // ======================================================================
    // MCP
    // ======================================================================

    @Test
    void over_mcp_the_same_values_answer_the_first_result() {
        Mcp.Result first = mcpCreate(MARK, "the first text");
        Mcp.Result repeat = mcpCreate(MARK, "the first text");

        assertThat(first.isError()).as(first.text()).isFalse();
        assertThat(repeat.isError()).as(repeat.text()).isFalse();
        assertThat(repeat.string("address")).isEqualTo(first.string("address"));
        assertThat(SurfaceFixture.countRows(ENTRIES)).isEqualTo(1);
    }

    @Test
    void over_mcp_other_values_under_a_spent_key_are_refused() {
        mcpCreate(MARK, "the first text");
        Mcp.Result other = mcpCreate(MARK, "another text");

        assertThat(other.isError()).isTrue();
        assertReused(409, other.structured(), other.text());
    }

    @Test
    void over_mcp_a_repeat_after_the_withdrawal_is_refused_and_lays_nothing_down() {
        Mcp.Result first = mcpCreate(MARK, "the first text");
        Mcp.Result withdrawn = Mcp.call("memory_withdraw", args("address",
            first.string("address"), "conflict_token", first.string("conflict_token")));
        assertThat(withdrawn.isError()).as(withdrawn.text()).isFalse();

        Mcp.Result repeat = mcpCreate(MARK, "the first text");
        assertThat(repeat.isError()).as(repeat.text()).isTrue();
        assertReused(409, repeat.structured(), repeat.text());
        assertThat(SurfaceFixture.countRows(ENTRIES)).isZero();
    }

    @Test
    void a_key_spent_on_rest_is_the_same_key_on_mcp() {
        restCreate(MARK, "the first text");
        Mcp.Result repeat = mcpCreate(MARK, "the first text");
        assertThat(repeat.isError()).as(repeat.text()).isFalse();
        assertThat(SurfaceFixture.countRows(ENTRIES)).isEqualTo(1);
    }

    // ======================================================================
    // The calls and the check
    // ======================================================================

    private static Response restCreate(String key, String content) {
        return SurfaceFixture.call().header(HEADER, key)
            .body(SurfaceFixture.fields("key", "decision.storage-engine", "type", "decision",
                "content", content))
            .post(COLLECTION);
    }

    private static Mcp.Result mcpCreate(String key, String content) {
        return Mcp.call("memory_create", args("scope", SCOPE, "selector", "decision",
            "idempotency_key", key,
            "fields", Map.of("key", "decision.storage-engine", "type", "decision",
                "content", content)));
    }

    /** The refusal of C8.7: 409, the code, nothing in data beyond attempted, no mark. */
    private static void assertReused(int status, Map<String, Object> envelope, String text) {
        assertThat(status).as(text).isEqualTo(409);
        assertThat(envelope.get("reason")).as(text).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> members) {
            assertThat(members.keySet().stream().map(String::valueOf).toList())
                .as("no data beyond attempted (C8.7)").isSubsetOf("attempted");
        }
        assertThat(text).as("the key is the caller's value and is never repeated (C8.8)")
            .doesNotContain(MARK);
    }
}
