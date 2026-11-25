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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The type {@code open-question} in its vocabulary form (C7.6): every answer
 * writes it so -- an entry, a listing, a digest with its summary, its selected
 * types and its sections -- on both surfaces. The input takes it, and takes
 * the earlier spelling {@code open_question} beside it until the next
 * release, wherever a type is written: a create, an update, the type filter
 * of a query and the types of a digest.
 *
 * <p>The declaration lists both spellings as values of every argument that
 * takes a type, the earlier one described as the transition it is, and the
 * tool schema carries what the declaration lists (C4.7). The selector grammar
 * takes the six fixed selector values the declaration declares.
 *
 * <p>RED STATE, observed 2026-10-29 with {@code EntryType} planted back to the
 * one spelling: a create with {@code open-question} was refused as {@code
 * TYPE_UNKNOWN} on both surfaces, and the declaration listed {@code
 * open_question} alone; three of the four cases turned red. Observed again in
 * the pull request that moved the answers to the vocabulary form: against the
 * service that still answered the stored name, the answer cases turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "owner")
class OpenQuestionSpellingIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String COLLECTION = SurfaceFixture.API + "/" + SCOPE + "/open-question";

    /** The type in its vocabulary form (C7.6), the one every answer writes. */
    private static final String VOCABULARY_FORM = "open-question";

    /** Its earlier spelling, which the input takes until the next release. */
    private static final String EARLIER_SPELLING = "open_question";

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
            .body(SurfaceFixture.fields("key", "open-question.storage", "type", VOCABULARY_FORM,
                "content", "Which engine?"))
            .post(COLLECTION);
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        assertThat(created.jsonPath().getString("fields.type"))
            .as("an answer writes the type in its vocabulary form (C7.6)")
            .isEqualTo(VOCABULARY_FORM);

        Response changed = SurfaceFixture.call().header("If-Match", created.header("ETag"))
            .body(SurfaceFixture.fields("type", VOCABULARY_FORM, "content", "Which engine, now?"))
            .patch(COLLECTION + "/storage");
        assertThat(changed.statusCode()).as(changed.asString()).isEqualTo(200);
        assertThat(changed.jsonPath().getString("fields.type")).isEqualTo(VOCABULARY_FORM);

        Response listed = SurfaceFixture.call()
            .get(SurfaceFixture.API + "/" + SCOPE + "?type=open-question");
        assertThat(listed.jsonPath().getList("objects.address", String.class))
            .as("the filter takes the hyphen form and finds the entry")
            .containsExactly("memory://" + SCOPE + "/open-question/storage");
        assertThat(listed.jsonPath().getList("objects.fields.type", String.class))
            .as("a listing writes the vocabulary form too")
            .containsExactly(VOCABULARY_FORM);
    }

    @Test
    void an_entry_laid_down_in_the_earlier_spelling_is_answered_in_the_vocabulary_form() {
        Response created = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "open-question.old", "type", EARLIER_SPELLING,
                "content", "Still taken?"))
            .post(COLLECTION);
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        assertThat(created.jsonPath().getString("fields.type"))
            .as("the earlier spelling is taken as input and never answered (C7.6)")
            .isEqualTo(VOCABULARY_FORM);

        Response read = SurfaceFixture.call().get(COLLECTION + "/old");
        assertThat(read.statusCode()).as(read.asString()).isEqualTo(200);
        assertThat(read.jsonPath().getString("fields.type")).isEqualTo(VOCABULARY_FORM);
    }

    @Test
    void over_rest_a_digest_names_the_type_in_its_vocabulary_form() {
        SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "open-question.storage", "type", VOCABULARY_FORM,
                "content", "Which engine?"))
            .post(COLLECTION);

        Response digest = SurfaceFixture.call().body(Map.of("types", List.of(EARLIER_SPELLING)))
            .post(SurfaceFixture.API + "/" + SCOPE + ":digest");
        assertThat(digest.statusCode()).as(digest.asString()).isEqualTo(200);
        assertThat(digest.jsonPath().getList("selected_types", String.class))
            .as("the selected types name the type as an answer does, whatever spelling was asked")
            .containsExactly(VOCABULARY_FORM);
        assertThat(digest.jsonPath().getList("sections.type", String.class))
            .containsExactly(VOCABULARY_FORM);
        assertThat(digest.jsonPath().getList("summary.type", String.class))
            .as("the summary names all six in their vocabulary form")
            .contains(VOCABULARY_FORM)
            .doesNotContain(EARLIER_SPELLING);
        assertThat(digest.asString()).contains("Which engine?");
    }

    @Test
    void over_mcp_a_create_a_query_and_a_digest_take_the_hyphen_form() {
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "open-question", "fields", Map.of("key", "open-question.storage",
                "type", VOCABULARY_FORM, "content", "Which engine?")));
        assertThat(created.isError()).as(created.text()).isFalse();
        assertThat(created.string("fields.type"))
            .as("the assistant surface answers the vocabulary form as well")
            .isEqualTo(VOCABULARY_FORM);

        Mcp.Result listed = Mcp.call("memory_query", args("scope", SCOPE,
            "type", VOCABULARY_FORM));
        assertThat(listed.isError()).as(listed.text()).isFalse();
        assertThat(listed.strings("objects.address"))
            .containsExactly("memory://" + SCOPE + "/open-question/storage");
        assertThat(listed.strings("objects.fields.type")).containsExactly(VOCABULARY_FORM);

        Mcp.Result digest = Mcp.call("memory_digest", args("scope", SCOPE,
            "types", List.of(VOCABULARY_FORM)));
        assertThat(digest.isError()).as(digest.text()).isFalse();
        assertThat(digest.text()).contains("Which engine?");
        assertThat(digest.strings("selected_types")).containsExactly(VOCABULARY_FORM);
        assertThat(digest.strings("sections.type")).containsExactly(VOCABULARY_FORM);
    }

    /**
     * A cursor binds the type filter by the type's stored name, as it did
     * before the wire name took the vocabulary form: a cursor handed out by the
     * release before continues its listing, and a listing filtered in one
     * spelling continues in the other. The earlier cursor is built here by hand
     * in the form version 1 states.
     */
    @Test
    void a_listing_filtered_to_open_questions_continues_across_the_release_and_both_spellings() {
        for (String key : List.of("open-question.a", "open-question.b", "open-question.c")) {
            SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", key, "type", VOCABULARY_FORM,
                    "content", "Which engine?"))
                .post(COLLECTION);
        }
        String scope = SurfaceFixture.API + "/" + SCOPE;

        Response first = SurfaceFixture.call().queryParam("type", VOCABULARY_FORM)
            .queryParam("page_size", 1).get(scope);
        String cursor = first.jsonPath().getString("next_cursor");
        assertThat(cursor).as(first.asString()).isNotNull();
        Response second = SurfaceFixture.call().queryParam("type", EARLIER_SPELLING)
            .queryParam("page_size", 1).queryParam("after", cursor).get(scope);
        assertThat(second.statusCode()).as(second.asString()).isEqualTo(200);
        assertThat(second.jsonPath().getList("objects.fields.key", String.class))
            .as("one filter in two spellings is one query, and its cursor continues it")
            .containsExactly("open-question.b");

        String handedOutBefore = "v1\n" + digest("scope=" + SubstrateDatabaseResource.SCOPE_ID)
            + "\n\n" + digest("type=open_question\ntext=") + "\nid\nasc\n0\nopen-question.a";
        Response continued = SurfaceFixture.call().queryParam("type", VOCABULARY_FORM)
            .queryParam("after", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(handedOutBefore.getBytes(StandardCharsets.UTF_8)))
            .get(scope);
        assertThat(continued.statusCode())
            .as("a cursor handed out before the release is read, never refused: %s",
                continued.asString())
            .isEqualTo(200);
        assertThat(continued.jsonPath().getList("objects.fields.key", String.class))
            .containsExactly("open-question.b", "open-question.c");
    }

    @Test
    void the_old_spelling_stays_accepted() {
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "open-question", "fields", Map.of("key", "open-question.old",
                "type", EARLIER_SPELLING, "content", "Still taken?")));
        assertThat(created.isError()).as(created.text()).isFalse();
        assertThat(created.string("fields.type")).isEqualTo(VOCABULARY_FORM);
    }

    @Test
    void a_refused_type_lists_the_vocabulary_form_among_the_accepted_values() {
        Response refused = SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", "open-question.wrong", "type", "open question",
                "content", "Which engine?"))
            .post(COLLECTION);
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("TYPE_UNKNOWN");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .as("the values a refusal expects are the declared ones, the vocabulary form "
                + "first and the earlier spelling beside it until the next release")
            .containsSubsequence(VOCABULARY_FORM, EARLIER_SPELLING);
    }

    @Test
    void the_declaration_and_the_tool_schema_list_both_spellings() {
        Response served = given().get(SurfaceFixture.API + "/declaration");
        List<String> typeValues = served.jsonPath()
            .getList("calls.find { it.call == 'memory_create' }.arguments"
                + ".find { it.name == 'type' }.values", String.class);
        assertThat(typeValues).containsSubsequence(VOCABULARY_FORM, EARLIER_SPELLING);

        String transition = served.jsonPath()
            .getString("calls.find { it.call == 'memory_create' }.arguments"
                + ".find { it.name == 'type' }.value_descriptions"
                + ".find { it.value == 'open_question' }.description");
        assertThat(transition)
            .as("the earlier spelling is declared as a transition that the next release "
                + "removes")
            .contains("next release");

        List<String> schemaValues = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.find { it.name == 'memory_create' }.inputSchema"
                + ".properties.fields.properties.type.enum", String.class);
        assertThat(schemaValues).as("the tool schema carries what the declaration lists (C4.7)")
            .isEqualTo(typeValues);

        List<String> selectors = served.jsonPath()
            .getList("calls.find { it.call == 'memory_create' }.arguments"
                + ".find { it.name == 'selector' }.values", String.class);
        assertThat(selectors).hasSize(6);
        for (String selector : selectors) {
            assertThat(AddressParser.parse(SCOPE, selector, "x").selector())
                .as("the selector grammar takes every declared selector value")
                .isEqualTo(selector);
        }
    }

    /** The first sixteen hexadecimal digits of SHA-256, as version 1 of the cursor states its digests. */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
