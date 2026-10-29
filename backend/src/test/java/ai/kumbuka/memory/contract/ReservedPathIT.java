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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two literal paths of the REST surface, and the two names they reserve
 * (C3.2, C16.2).
 *
 * <p>{@code GET /api/declaration} and {@code GET /api/whoami} are the only
 * literal paths under {@code /api}; {@code declaration} and {@code whoami}
 * are therefore never a scope. A path below either is {@code
 * ADDRESS_MALFORMED} 400 with no {@code data}, refused before the route is
 * matched, and the assistant surface's address grammar refuses the same names.
 *
 * <p>RED STATE, observed 2026-10-29 with the filter left out (no
 * {@code @Provider}) and the names out of the grammar: a create under
 * {@code /api/whoami/decision} reached the scope directory and answered
 * {@code NOT_FOUND} instead of {@code ADDRESS_MALFORMED}, and so did
 * {@code memory_query} on scope {@code declaration}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class ReservedPathIT {

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void whoami_answers_the_subject_and_the_tenant_the_request_was_bound_to() {
        Response answer = SurfaceFixture.call().get(SurfaceFixture.API + "/whoami");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        assertThat(answer.jsonPath().getString("subject"))
            .isEqualTo(SubstrateDatabaseResource.PROBE_SUBJECT);
        assertThat(answer.jsonPath().getString("tenant")).isNotBlank();
    }

    @Test
    void a_path_below_a_reserved_name_is_address_malformed_without_data() {
        for (String path : new String[] {"/whoami/decision", "/declaration/decision",
                "/whoami/decision/storage-engine", "/declaration/"}) {
            Response get = SurfaceFixture.call().get(SurfaceFixture.API + path);
            assertMalformed(get.statusCode(), get.jsonPath().getMap("$"), path);
            Response post = SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", "decision.x", "type", "decision",
                    "content", "c"))
                .post(SurfaceFixture.API + path);
            assertMalformed(post.statusCode(), post.jsonPath().getMap("$"), path);
        }
    }

    @Test
    void the_assistant_surface_refuses_a_reserved_name_as_a_scope() {
        for (String reserved : new String[] {"declaration", "whoami"}) {
            Mcp.Result query = Mcp.call("memory_query", args("scope", reserved));
            assertThat(query.isError()).isTrue();
            assertThat(query.reason()).as(query.text()).isEqualTo("ADDRESS_MALFORMED");
            Mcp.Result read = Mcp.call("memory_read",
                args("address", "memory://" + reserved + "/decision/storage-engine"));
            assertThat(read.reason()).as(read.text()).isEqualTo("ADDRESS_MALFORMED");
        }
    }

    private static void assertMalformed(int status, Map<String, Object> envelope, String path) {
        assertThat(status).as(path + " " + envelope).isEqualTo(400);
        assertThat(envelope.get("reason")).as(path).isEqualTo("ADDRESS_MALFORMED");
        assertThat(envelope).as(path + ": no data").doesNotContainKey("data");
    }
}
