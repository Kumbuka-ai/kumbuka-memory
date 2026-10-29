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

import static ai.kumbuka.memory.contract.Mcp.args;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type {@code open_question} is accepted in its vocabulary form
 * {@code open-question} as well (C7.6), wherever a type is written:
 * a create, an update, the type filter of a query and the types of a digest,
 * on both surfaces. {@code open_question} stays accepted, and an answer still
 * writes it, until the breaking step.
 *
 * <p>The declaration lists both spellings as values of every argument that
 * takes a type, and the tool schema carries what the declaration lists (C4.7).
 * The selector grammar takes the six fixed selector values the declaration
 * declares.
 *
 * <p>RED STATE, observed 2026-10-29 with {@code EntryType} planted back to the
 * one spelling: a create with {@code open-question} was refused as {@code
 * TYPE_UNKNOWN} on both surfaces, and the declaration listed {@code
 * open_question} alone; three of the four cases turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class OpenQuestionSpellingIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String COLLECTION = SurfaceFixture.API + "/" + SCOPE + "/open-question";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void noEntries() {
        SurfaceFixture.clearEntries();
    }

    @Test
    void over_rest_a_create_and_an_update_take_the_hyphen_form() {
        Response created = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "open-question.storage", "type", "open-question",
                "content", "Which engine?"))
            .post(COLLECTION);
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        assertThat(created.jsonPath().getString("fields.type"))
            .as("an answer writes the stored name until the breaking step")
            .isEqualTo("open_question");

        Response changed = SurfaceFixture.call().header("If-Match", created.header("ETag"))
            .body(SurfaceFixture.fields("type", "open-question", "content", "Which engine, now?"))
            .patch(COLLECTION + "/storage");
        assertThat(changed.statusCode()).as(changed.asString()).isEqualTo(200);

        Response listed = SurfaceFixture.call()
            .get(SurfaceFixture.API + "/" + SCOPE + "?type=open-question");
        assertThat(listed.jsonPath().getList("objects.address", String.class))
            .as("the filter takes the hyphen form and finds the entry")
            .containsExactly("memory://" + SCOPE + "/open-question/storage");
    }

    @Test
    void over_mcp_a_create_a_query_and_a_digest_take_the_hyphen_form() {
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "open-question", "fields", Map.of("key", "open-question.storage",
                "type", "open-question", "content", "Which engine?")));
        assertThat(created.isError()).as(created.text()).isFalse();

        Mcp.Result listed = Mcp.call("memory_query", args("scope", SCOPE,
            "type", "open-question"));
        assertThat(listed.isError()).as(listed.text()).isFalse();
        assertThat(listed.strings("objects.address"))
            .containsExactly("memory://" + SCOPE + "/open-question/storage");

        Mcp.Result digest = Mcp.call("memory_digest", args("scope", SCOPE,
            "types", List.of("open-question")));
        assertThat(digest.isError()).as(digest.text()).isFalse();
        assertThat(digest.text()).contains("Which engine?");
    }

    @Test
    void the_old_spelling_stays_accepted() {
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "open-question", "fields", Map.of("key", "open-question.old",
                "type", "open_question", "content", "Still taken?")));
        assertThat(created.isError()).as(created.text()).isFalse();
    }

    @Test
    void the_declaration_and_the_tool_schema_list_both_spellings() {
        Map<String, Object> declaration = given().get(SurfaceFixture.API + "/declaration")
            .jsonPath().getMap("$");
        List<String> typeValues = given().get(SurfaceFixture.API + "/declaration").jsonPath()
            .getList("calls.find { it.call == 'memory_create' }.arguments"
                + ".find { it.name == 'type' }.values", String.class);
        assertThat(declaration).isNotEmpty();
        assertThat(typeValues).contains("open_question", "open-question");

        List<String> schemaValues = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.find { it.name == 'memory_create' }.inputSchema"
                + ".properties.fields.properties.type.enum", String.class);
        assertThat(schemaValues).as("the tool schema carries what the declaration lists (C4.7)")
            .isEqualTo(typeValues);

        List<String> selectors = given().get(SurfaceFixture.API + "/declaration").jsonPath()
            .getList("calls.find { it.call == 'memory_create' }.arguments"
                + ".find { it.name == 'selector' }.values", String.class);
        assertThat(selectors).hasSize(6);
        for (String selector : selectors) {
            assertThat(AddressParser.parse(SCOPE, selector, "x").selector())
                .as("the selector grammar takes every declared selector value")
                .isEqualTo(selector);
        }
    }
}
