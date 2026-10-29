/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.adapter.mcp.McpAdapter;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=false} (C4.6): it
 * starts, the assistant surface is not in the application, and REST answers
 * as it does with the surface on.
 *
 * <p>The switch is a build property, so the class it governs is left out of
 * the application rather than refusing at runtime: {@code /mcp} and {@code
 * /mcp/declaration} find no route, and answer the not-found of every other
 * route that does not exist. With {@code Kumbuka-Surface: assistant} REST
 * answers in the vocabulary of the assistant surface here too: that
 * vocabulary belongs to the call, not to the adapter this build leaves out.
 *
 * <p>RED STATE, observed 2026-10-29: without the annotation on
 * {@link McpAdapter}, {@code /mcp} answered and this class turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOffIT.Off.class)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class LocalMcpOffIT {

    private static final String COLLECTION = SurfaceFixture.API + "/"
        + SubstrateDatabaseResource.PROBE_SCOPE_SLUG + "/decision";

    /** The build without the service's own assistant surface. */
    public static class Off implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "false", "quarkus.http.test-port", "0");
        }
    }

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void noEntries() {
        SurfaceFixture.clearEntries();
    }

    @Test
    void the_assistant_surface_is_not_in_the_application() {
        Response rpc = given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
            .post("/mcp");
        assertThat(rpc.statusCode()).as("no route serves /mcp").isEqualTo(404);
        assertThat(given().get("/mcp/declaration").statusCode()).isEqualTo(404);

        assertThat(Arc.container().instance(McpAdapter.class).isAvailable())
            .as("the resource is not a bean of this build").isFalse();
        assertThat(Arc.container().instance(VerbSurface.class).isAvailable())
            .as("the verb surface both adapters call stays").isTrue();
    }

    @Test
    void a_write_and_a_read_over_rest_answer_as_with_the_surface_on() {
        Response created = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "decision.without-mcp", "type", "decision",
                "content", "written over rest alone"))
            .post(COLLECTION);
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);

        Response read = SurfaceFixture.call().get(COLLECTION + "/without-mcp");
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getString("fields.content"))
            .isEqualTo("written over rest alone");
        assertThat(given().get(SurfaceFixture.API + "/declaration").statusCode())
            .as("the declaration stays where the router reads it").isEqualTo(200);
    }

    @Test
    void with_the_header_rest_names_the_assistant_tools_though_there_is_no_mcp() {
        SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "decision.named", "type", "decision",
                "content", "named for the assistant"))
            .post(COLLECTION);
        Response read = SurfaceFixture.call().header("Kumbuka-Surface", "assistant")
            .get(COLLECTION + "/named");
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getList("next.call", String.class))
            .isNotEmpty().allMatch(call -> call.startsWith("memory_"));
    }
}
