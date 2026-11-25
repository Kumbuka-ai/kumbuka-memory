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
import ai.kumbuka.security.quarkus.CurrentCallFacts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static ai.kumbuka.memory.contract.Mcp.args;
import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every call is checked once, before the method runs: does it hold the
 * permission the method declares, and is the scope it addresses one it may
 * address. Probed end to end, on both surfaces, with the roles a token
 * carries and the scopes the substrate gives the subject.
 *
 * <p>The expectations are the order's and the catalogue's: a subject holding
 * {@code memory-reader} alone reads, queries and digests and is refused every
 * write; an agent's token -- {@code dispatch-reader}, {@code memory-reader},
 * {@code worklist-reader} -- reads and is refused update and withdraw; a scope
 * outside the set is answered exactly as a scope that does not exist, its
 * reason in the log and nowhere else; facts with an empty scope set are
 * refused; and a subject holding {@code memory-author} in a locked scope is
 * refused by the lock, which is decided after the permission.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PermissionCheckIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String OUTSIDE = SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG;
    private static final String KEY = "decision.storage-engine";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage-engine";
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/storage-engine";
    private static final String CONTENT = "Entries live in PostgreSQL.";
    private static final String COUNT = "SELECT count(*) FROM memory.memory";

    @Inject CurrentCallFacts facts;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEntryInTheOpenAndTheLockedScope() {
        SurfaceFixture.clearEntries();
        plant(SubstrateDatabaseResource.SCOPE_ID);
        plant(SubstrateDatabaseResource.LOCKED_SCOPE_ID);
    }

    // ======================================================================
    // A subject holding memory-reader alone
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "memory-reader")
    void a_reader_reads_queries_and_digests_and_is_refused_every_write() {
        Response read = call().get(ITEM);
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        assertThat(call().get(API + "/" + SCOPE).statusCode()).as("query").isEqualTo(200);
        assertThat(call().body("{}").post(API + "/" + SCOPE + ":digest").statusCode())
            .as("digest").isEqualTo(200);
        String token = read.jsonPath().getString("conflict_token");

        long before = SurfaceFixture.countRows(COUNT);
        assertRefused(call().body(fields("key", "decision.another", "type", "decision",
            "content", "x")).post(API + "/" + SCOPE + "/decision"), 403, "SCOPE_READ_ONLY");
        assertRefused(call().header("If-Match", token).body(fields("content", "x"))
            .patch(ITEM), 403, "SCOPE_READ_ONLY");
        assertRefused(call().header("If-Match", token).post(ITEM + ":withdraw"),
            403, "SCOPE_READ_ONLY");
        assertRefused(call().body("{\"tag\":\"" + SubstrateDatabaseResource.TAG_COMPONENT
            + "\"}").post(ITEM + ":tag_attach"), 403, "SCOPE_READ_ONLY");
        assertThat(SurfaceFixture.countRows(COUNT)).as("nothing was written").isEqualTo(before);
        assertThat(call().get(ITEM).jsonPath().getString("fields.content"))
            .isEqualTo(CONTENT);
        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class)).isEmpty();

        assertThat(Mcp.call("memory_read", args("address", ADDRESS)).isError()).isFalse();
        assertThat(Mcp.call("memory_query", args("scope", SCOPE)).isError()).isFalse();
        assertThat(Mcp.call("memory_digest", args("scope", SCOPE)).isError()).isFalse();
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "decision", "fields", Map.of("key", "decision.another",
                "type", "decision", "content", "x")));
        assertThat(created.isError()).isTrue();
        assertThat(created.reason()).isEqualTo("SCOPE_READ_ONLY");
    }

    // ======================================================================
    // The agent's token
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT,
        roles = {"dispatch-reader", "memory-reader", "worklist-reader"})
    void an_agent_reads_and_is_refused_update_and_withdraw() {
        Response read = call().get(ITEM);
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        assertThat(read.jsonPath().getList("next.call", String.class))
            .as("the answer offers what the agent may do, and that is reading")
            .containsExactly("read " + ADDRESS);
        String token = read.jsonPath().getString("conflict_token");

        assertRefused(call().header("If-Match", token).body(fields("content", "x"))
            .patch(ITEM), 403, "SCOPE_READ_ONLY");
        assertRefused(call().header("If-Match", token).post(ITEM + ":withdraw"),
            403, "SCOPE_READ_ONLY");

        Mcp.Result withdrawn = Mcp.call("memory_withdraw", args("address", ADDRESS,
            "conflict_token", token));
        assertThat(withdrawn.reason()).isEqualTo("SCOPE_READ_ONLY");
        assertThat(call().get(ITEM).jsonPath().getString("fields.content"))
            .as("the entry stands as it was")
            .isEqualTo(CONTENT);
    }

    // ======================================================================
    // A permission held, another one not
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "memory-author")
    void an_author_withdraws_nothing_and_is_told_the_role_does_not_grant_it() {
        assertRefused(call().header("If-Match", "any").post(ITEM + ":withdraw"),
            403, "ROLE_INSUFFICIENT");
        Mcp.Result withdrawn = Mcp.call("memory_withdraw", args("address", ADDRESS,
            "conflict_token", "any"));
        assertThat(withdrawn.reason()).isEqualTo("ROLE_INSUFFICIENT");
        assertThat(SurfaceFixture.countRows(COUNT + " WHERE key = '" + KEY + "'"))
            .as("both entries stand").isEqualTo(2);
    }

    // ======================================================================
    // The scope set
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "owner")
    void a_scope_outside_the_set_is_answered_as_a_scope_that_does_not_exist() {
        List<String> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger security = Logger.getLogger("ai.kumbuka.security");
        security.addHandler(capture);
        try {
            Response outside = call().get(API + "/" + OUTSIDE + "/decision/storage-engine");
            Response unknown = call().get(API + "/no-such-scope/decision/storage-engine");
            Response absent = call().get(API + "/" + SCOPE + "/decision/nothing-here");

            for (Response answer : List.of(outside, unknown, absent)) {
                assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(404);
                assertThat(answer.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
            }
            assertThat(outside.asString())
                .as("the same status and the same body, byte for byte")
                .isEqualTo(unknown.asString())
                .isEqualTo(absent.asString())
                .doesNotContain("scope-outside-set")
                .doesNotContain(OUTSIDE);
            assertThat(call().get(API + "/" + OUTSIDE).asString())
                .as("a query on it as well").isEqualTo(absent.asString());

            Mcp.Result overMcp = Mcp.call("memory_read",
                args("address", "memory://" + OUTSIDE + "/decision/storage-engine"));
            Mcp.Result nothing = Mcp.call("memory_read",
                args("address", "memory://" + SCOPE + "/decision/nothing-here"));
            assertThat(overMcp.structured()).isEqualTo(nothing.structured());
        } finally {
            security.removeHandler(capture);
        }
        assertThat(logged)
            .as("the reason stands in the log, with the slug that was addressed")
            .anyMatch(line -> line.contains("scope-outside-set") && line.contains(OUTSIDE));
    }

    @Test
    @TestSecurity(user = "a-subject-of-no-scope", roles = "owner")
    void facts_with_an_empty_scope_set_are_refused() {
        assertThat(facts.find()).as("the subject is no member of any scope").isPresent();
        assertThat(facts.find().orElseThrow().scopes()).isEmpty();
        assertThat(facts.find().orElseThrow().permissions()).as("and holds every permission")
            .hasSize(5);

        long before = SurfaceFixture.countRows(COUNT);
        assertRefused(call().get(ITEM), 404, "NOT_FOUND");
        assertRefused(call().body(fields("key", "decision.another", "type", "decision",
            "content", "x")).post(API + "/" + SCOPE + "/decision"), 404, "NOT_FOUND");
        assertThat(Mcp.call("memory_digest", args("scope", SCOPE)).reason())
            .isEqualTo("NOT_FOUND");
        assertThat(SurfaceFixture.countRows(COUNT)).as("nothing was written").isEqualTo(before);
    }

    // ======================================================================
    // The lock, after the permission
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "memory-author")
    void an_author_in_a_locked_scope_is_refused_by_the_lock_after_the_permission() {
        String body = fields("key", "decision.another", "type", "decision", "content", "x");
        assertRefused(call().body(body).post(API + "/" + LOCKED + "/decision"),
            409, "SCOPE_LOCKED");
        Mcp.Result overMcp = Mcp.call("memory_create", args("scope", LOCKED,
            "selector", "decision", "fields", Map.of("key", "decision.another",
                "type", "decision", "content", "x")));
        assertThat(overMcp.reason()).isEqualTo("SCOPE_LOCKED");

        Response open = call().body(body).post(API + "/" + SCOPE + "/decision");
        assertThat(open.statusCode())
            .as("the same call in an open scope is admitted: the permission is held, and "
                + "the lock alone refused it: %s", open.asString())
            .isEqualTo(201);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "memory-reader")
    void a_reader_in_a_locked_scope_is_refused_by_the_permission_first() {
        assertRefused(call().body(fields("key", "decision.another", "type", "decision",
            "content", "x")).post(API + "/" + LOCKED + "/decision"), 403, "SCOPE_READ_ONLY");
    }

    // ------------------------------------------------------------------

    private static void assertRefused(Response answer, int status, String reason) {
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(status);
        assertThat(answer.jsonPath().getString("reason")).as(answer.asString())
            .isEqualTo(reason);
    }

    private static void plant(String scopeId) {
        SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID, scopeId, false,
            SubstrateDatabaseResource.PROBE_SUBJECT, "decision", KEY, CONTENT, Instant.now()));
    }
}
