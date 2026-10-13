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

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic listing, asked for the assistant surface's projection by the
 * hop in front of the service.
 *
 * <h2>Why a header and not a second route</h2>
 *
 * A router that publishes this service's calls as assistant tools reaches it
 * over REST. Without saying so it is answered in the generic vocabulary, and
 * the assistant behind it is told to call {@code read memory://…}, which is no
 * tool it holds. With {@code Kumbuka-Surface: assistant} the same listing
 * names {@code memory_read}, and a refusal is worded and shaped as the
 * service's own MCP adapter words and shapes it — measured against that
 * adapter here, not against a copy of its text.
 *
 * <h2>The red run this probe was built against, observed on 2026-10-12</h2>
 *
 * On the state before the alignment the header was ignored: the listing with
 * it named {@code read memory://…} like the one without it, and its refusal
 * was the generic one, without {@code attempted}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class SurfaceHeaderIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String SURFACE = "Kumbuka-Surface";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void aFewEntries() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID, "decision", "decision", 3);
    }

    @Test
    void the_same_listing_names_the_calls_of_the_surface_the_header_names() {
        Response generic = call().get(API + "/" + SCOPE + "/decision?page_size=2");
        Response assistant = call().header(SURFACE, "assistant")
            .get(API + "/" + SCOPE + "/decision?page_size=2");

        assertThat(generic.statusCode()).isEqualTo(200);
        assertThat(assistant.statusCode()).isEqualTo(200);
        assertThat(generic.jsonPath().getList("objects[0].next.call", String.class))
            .as("without the header: the generic surface's calls, unchanged")
            .containsExactly("read memory://probe-scope/decision/item-1",
                "update memory://probe-scope/decision/item-1",
                "withdraw memory://probe-scope/decision/item-1");
        assertThat(assistant.jsonPath().getList("objects[0].next.call", String.class))
            .as("with it: the tools the assistant behind the hop holds")
            .containsExactly("memory_read", "memory_update", "memory_withdraw");

        Mcp.Result direct = Mcp.call("memory_query",
            Mcp.args("scope", SCOPE, "selector", "decision", "page_size", 2));
        assertThat(assistant.jsonPath().getMap("$"))
            .as("and the whole page is the one the service's own MCP adapter answers")
            .isEqualTo(direct.structured());
    }

    @Test
    void a_refusal_under_the_header_is_the_assistant_surfaces_refusal() {
        Response refused = call().header(SURFACE, "assistant")
            .get(API + "/" + SCOPE + "?page_size=0");
        Mcp.Result direct = Mcp.call("memory_query",
            Mcp.args("scope", SCOPE, "page_size", 0));

        assertThat(refused.statusCode())
            .as("the status is the generic surface's: the header changes the vocabulary, "
                + "not the transport")
            .isEqualTo(400);
        assertThat(refused.jsonPath().getMap("$"))
            .as("reason, message and data as the service's MCP adapter answers them")
            .isEqualTo(direct.structured());
        assertThat(refused.jsonPath().getString("data.attempted")).isEqualTo("memory_query");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("page_size");

        Response generic = call().get(API + "/" + SCOPE + "?page_size=0");
        assertThat(generic.jsonPath().getMap("data"))
            .as("without the header the refusal stays the generic one")
            .doesNotContainKey("attempted");
    }

    /**
     * The header is no argument of the call, and whether its value is one it
     * takes is decidable without a scope. So the refusal carries no
     * {@code data}, and it answers the same for a scope this caller cannot
     * see as for one it can: before the scope is looked at, as the dispatch
     * and the worklist service refuse it.
     *
     * <p>The red run, observed on 2026-10-13: every case answered with
     * {@code data.offenders = ["Kumbuka-Surface"]}.
     */
    @Test
    void a_value_the_header_cannot_take_is_refused_before_the_scope_is_looked_at() {
        for (String scope : List.of(SCOPE, SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG,
                "no-such-scope")) {
            for (List<String> values : List.of(List.of("zq-marked-surface"), List.of(""),
                    List.of("assistant", "assistant"))) {
                var request = call();
                for (String value : values) {
                    request = request.header(SURFACE, value);
                }
                Response answer = request.get(API + "/" + scope);

                assertThat(answer.statusCode()).as("%s %s: %s", scope, values,
                    answer.asString()).isEqualTo(400);
                assertThat(answer.jsonPath().getString("reason"))
                    .isEqualTo("ARGUMENT_INVALID");
                assertThat(answer.jsonPath().getMap("$"))
                    .as("no data: the header is not an argument — %s", answer.asString())
                    .containsOnlyKeys("reason", "message");
                assertThat(answer.jsonPath().getString("message"))
                    .as("names the header and the value it takes, and not the value sent")
                    .contains(SURFACE).contains("assistant")
                    .doesNotContain("zq-marked-surface");
            }
        }
    }
}
