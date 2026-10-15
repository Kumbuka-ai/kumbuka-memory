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
import io.restassured.http.ContentType;
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
 * Two refusals of a call's arguments that named the wrong thing, on the
 * generic surface and on the service's own MCP adapter (DEC-0042).
 *
 * <h2>A type that is not one</h2>
 *
 * {@code TYPE_UNKNOWN} on {@code create} and on the digest names the
 * argument, {@code type} or {@code types}, in {@code offenders}, and never
 * the value sent.
 *
 * <h2>A parameter on an address that names one entry</h2>
 *
 * {@code read} declares no argument beyond the address, so every query
 * parameter on an item address is {@code ARGUMENT_UNKNOWN} with 400 and its
 * name in {@code offenders}: decidable from the verb alone, as an unknown
 * argument of the listing is. The service's own adapter answers an unknown
 * argument of {@code memory_read} the same way.
 *
 * <p>The red run, observed on 2026-10-13 on the state before, failed all
 * three probes on their REST half: {@code offenders} carried the type sent
 * ({@code ["zqmark"]}) on {@code create} and on the digest, and a parameter
 * on an item address answered 422 {@code PREDICATE_UNKNOWN}. The MCP half,
 * observed the same day with {@code offenders} put back on refusals of
 * {@code memory_query} alone as the adapter had it: {@code memory_create} and
 * {@code memory_digest} refused a type with no {@code offenders}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class ArgumentOffendersIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String MARK = "zqmark";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntry() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "decision.storage-engine", "decision", "postgres"));
    }

    // ======================================================================
    // TYPE_UNKNOWN
    // ======================================================================

    @Test
    void a_type_that_is_not_one_names_the_argument_on_create() {
        Response rest = call().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("key", "decision.tabs", "type", MARK,
                "content", "x")))
            .post(API + "/" + SCOPE + "/decision");
        assertRest(rest, 422, "TYPE_UNKNOWN", List.of("type"));

        assertMcp(Mcp.call("memory_create", Mcp.args("scope", SCOPE, "selector", "decision",
            "fields", Map.of("key", "decision.tabs", "type", MARK, "content", "x"))),
            "TYPE_UNKNOWN", List.of("type"));
    }

    @Test
    void a_type_that_is_not_one_names_the_argument_on_the_digest() {
        Response rest = call().contentType(ContentType.JSON)
            .body(Map.of("types", List.of("decision", MARK)))
            .post(API + "/" + SCOPE + ":digest");
        // By its position, as the argument's path: which of several values was
        // wrong is what the caller needs, and the value is its own (DEC-0043 R19).
        assertRest(rest, 422, "TYPE_UNKNOWN", List.of("types[1]"));

        assertMcp(Mcp.call("memory_digest", Mcp.args("scope", SCOPE,
            "types", List.of("decision", MARK))), "TYPE_UNKNOWN", List.of("types[1]"));
    }

    // ======================================================================
    // Parameters on an address that names one entry
    // ======================================================================

    /**
     * Answered with the names the read declares and never with those that
     * arrived (DEC-0043 R19): on the generic surface a read takes no
     * parameter, so {@code accepts} is empty, and on the assistant surface it
     * lists {@code address}. The names sent are repeated nowhere.
     */
    @Test
    void a_parameter_on_an_item_address_is_an_unknown_argument() {
        assertUnknown(call().get(API + "/" + SCOPE + "/decision/storage-engine?type=" + MARK
            + "&" + MARK + "=" + MARK), List.of());

        assertUnknown(call().get(API + "/" + SurfaceFixture.logicalIdOf(
                SubstrateDatabaseResource.SCOPE_ID, "decision.storage-engine")
                + "?after=" + MARK), List.of());

        Mcp.Result mcp = Mcp.call("memory_read", Mcp.args("address",
            "memory://" + SCOPE + "/decision/storage-engine", MARK, MARK));
        assertThat(mcp.reason()).as("%s", mcp).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(mcp.strings("data.accepts")).containsExactly("address");
        assertThat(mcp.map("data")).doesNotContainKey("offenders");
        assertThat(mcp.text()).doesNotContain(MARK);
    }

    private static void assertUnknown(Response answer, List<String> accepts) {
        assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(answer.jsonPath().getList("data.accepts", String.class))
            .as("the names the call declares — %s", answer.asString())
            .isEqualTo(accepts);
        assertThat(answer.jsonPath().getMap("data")).doesNotContainKey("offenders");
        assertThat(answer.asString()).doesNotContain(MARK);
    }

    // ------------------------------------------------------------------

    private static void assertRest(Response answer, int status, String reason,
                                   List<String> offenders) {
        assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(status);
        assertThat(answer.jsonPath().getString("reason")).as("%s", answer.asString())
            .isEqualTo(reason);
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .as("the arguments, never their values — %s", answer.asString())
            .isEqualTo(offenders);
        assertThat(answer.asString()).doesNotContain(MARK);
    }

    private static void assertMcp(Mcp.Result answer, String reason, List<String> offenders) {
        assertThat(answer.isError()).as("%s", answer).isTrue();
        assertThat(answer.reason()).as("%s", answer).isEqualTo(reason);
        assertThat(answer.map("data").get("offenders"))
            .as("the arguments, never their values — %s", answer)
            .isEqualTo(offenders);
        assertThat(answer.text()).doesNotContain(MARK);
    }
}
