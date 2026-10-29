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
import io.restassured.response.Response;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an {@code update} takes, on the generic surface as on the assistant
 * surface: every name it is sent either acts or is refused, and the reference
 * of an entry can be removed.
 *
 * <p>Every name: a name the update does not declare, inside {@code fields} or
 * beside it, was read and dropped on the generic surface, and the caller was
 * answered as if it had been applied. It is refused as {@code ARGUMENT_UNKNOWN}
 * 400 with the names that level accepts, before the scope is resolved, and the
 * name sent is repeated nowhere.
 *
 * <p>Removal: the reference could be changed and not removed. The empty text
 * removes it, on both surfaces; {@code null} leaves it as it is (C7.4).
 *
 * <p>RED STATE, observed 2026-10-29 against the tree before this change: the
 * update with an undeclared name answered 200 and changed the content, and on
 * both surfaces the empty reference left the reference standing (REST
 * answered 400 {@code UPDATE_EMPTY} for the empty text alone, MCP refused it
 * as {@code ARGUMENT_INVALID}).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class UpdateFormIT {

    private static final String MARK = "zzinvented4711";
    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String COLLECTION = SurfaceFixture.API + "/" + SCOPE + "/decision";
    private static final String ITEM = COLLECTION + "/storage-engine";
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/storage-engine";
    private static final String REFERENCE = "https://example.org/adr/1";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntryWithAReference() {
        SurfaceFixture.clearEntries();
        Response created = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "decision.storage-engine", "type", "decision",
                "content", "Postgres.", "reference", REFERENCE))
            .post(COLLECTION);
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
    }

    // ======================================================================
    // Every name acts or is refused
    // ======================================================================

    @Test
    void an_undeclared_name_inside_fields_is_refused_by_name_and_changes_nothing() {
        Response refused = SurfaceFixture.call().header("If-Match", token())
            .body("{\"fields\":{\"content\":\"Changed.\",\"" + MARK + "\":\"x\"}}")
            .patch(ITEM);

        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .containsExactly("content", "type", "reference");
        assertThat(refused.asString()).as("the name sent is repeated nowhere")
            .doesNotContain(MARK);
        assertThat(SurfaceFixture.call().get(ITEM).jsonPath().getString("fields.content"))
            .as("and the update was not carried out").isEqualTo("Postgres.");
    }

    @Test
    void an_undeclared_name_beside_fields_is_refused_by_name() {
        Response refused = SurfaceFixture.call().header("If-Match", token())
            .body("{\"fields\":{\"content\":\"Changed.\"},\"" + MARK + "\":1}")
            .patch(ITEM);

        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .containsExactly("fields");
        assertThat(refused.asString()).doesNotContain(MARK);
    }

    @Test
    void the_form_is_refused_before_the_scope_is_resolved() {
        Response refused = SurfaceFixture.call().header("If-Match", "t")
            .body("{\"fields\":{\"" + MARK + "\":\"x\"}}")
            .patch(SurfaceFixture.API + "/" + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG
                + "/decision/storage-engine");
        assertThat(refused.jsonPath().getString("reason"))
            .as("decidable from the call alone, so it answers before the scope (C1.3)")
            .isEqualTo("ARGUMENT_UNKNOWN");
    }

    // ======================================================================
    // Removing the reference
    // ======================================================================

    @Test
    void over_rest_the_empty_text_removes_the_reference() {
        Response removed = SurfaceFixture.call().header("If-Match", token())
            .body(SurfaceFixture.fields("reference", ""))
            .patch(ITEM);

        assertThat(removed.statusCode()).as(removed.asString()).isEqualTo(200);
        assertThat(removed.jsonPath().getMap("fields")).doesNotContainKey("reference");
        assertThat(SurfaceFixture.call().get(ITEM).jsonPath().getMap("fields"))
            .as("read back, the entry stands without one").doesNotContainKey("reference");
    }

    @Test
    void over_mcp_the_empty_text_removes_the_reference() {
        Mcp.Result removed = Mcp.call("memory_update", args("address", ADDRESS,
            "conflict_token", token(), "fields", Map.of("reference", "")));

        assertThat(removed.isError()).as(removed.text()).isFalse();
        assertThat(removed.map("fields")).doesNotContainKey("reference");
    }

    @Test
    void null_leaves_the_reference_as_it_is() {
        Response changed = SurfaceFixture.call().header("If-Match", token())
            .body(SurfaceFixture.fields("content", "Postgres 16.", "reference", null))
            .patch(ITEM);

        assertThat(changed.statusCode()).as(changed.asString()).isEqualTo(200);
        assertThat(changed.jsonPath().getString("fields.reference"))
            .as("null is absence, never removal (C7.4)").isEqualTo(REFERENCE);
    }

    private static String token() {
        return SurfaceFixture.call().get(ITEM).jsonPath().getString("conflict_token");
    }
}
