/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an answer is written (C7.2, C7.3): a member with no value is absent, and
 * an instant is ISO-8601 in UTC, to the microsecond at most.
 *
 * <p>A set of answers is collected over both surfaces — an entry created
 * without a reference, read, listed as the last page, refused for a stale
 * token, withdrawn, a digest, a not-found refusal, the protocol's own
 * answers — and every node of every answer is walked: no member is
 * {@code null}, and every member that names an instant has the one form. The
 * one member the protocol requires to stand as {@code null}, the id of an
 * envelope that could not be read, is held to that separately.
 *
 * <p>The instants this service answers with come from the store, which keeps
 * microseconds, so no answer can carry a finer one; the form is therefore
 * also asked of the service's own mapper with an instant to the nanosecond.
 *
 * <p>The red run, observed on 2026-10-28 against the tree before this change:
 * every entry answer without a reference carried {@code "reference": null},
 * on both surfaces, in a listing and inside the stale-token refusal — seven
 * members in the walk — and the service's mapper wrote an instant with all
 * nine digits of its nanoseconds.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
@CounterProbe
class SerialisationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/serialised";
    private static final String ITEM = SurfaceFixture.API + "/" + SCOPE + "/decision/serialised";
    private static final Pattern INSTANT = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");
    private static final List<String> INSTANT_MEMBERS = List.of("created_at", "updated_at");

    /** The service's own mapper, as every answer is written with it. */
    @Inject ObjectMapper service;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void empty() {
        SurfaceFixture.clearEntries();
    }

    @Test
    void no_member_is_null_and_every_instant_has_the_one_form() throws Exception {
        List<String> answers = new ArrayList<>();
        Mcp.Result created = Mcp.call("memory_create", args("scope", SCOPE,
            "selector", "decision", "fields", Map.of("key", "decision.serialised",
                "type", "decision", "content", "written without a reference")));
        assertThat(created.isError()).as("the create succeeded: %s", created).isFalse();
        answers.add(created.json().prettify());
        answers.add(Mcp.call("memory_read", args("address", ADDRESS)).json().prettify());
        answers.add(Mcp.call("memory_query", args("scope", SCOPE)).json().prettify());
        answers.add(Mcp.call("memory_update", args("address", ADDRESS,
            "conflict_token", "2000-01-01T00:00:00Z",
            "fields", Map.of("content", "changed"))).json().prettify());
        answers.add(Mcp.call("memory_digest", args("scope", SCOPE)).json().prettify());
        answers.add(Mcp.call("memory_read",
            args("address", "memory://" + SCOPE + "/decision/absent")).json().prettify());
        answers.add(Mcp.rpc("initialize", Map.of()).asString());
        answers.add(Mcp.rpc("tools/list", Map.of()).asString());
        answers.add(Mcp.rpc("ping", Map.of()).asString());

        answers.add(SurfaceFixture.call().get(ITEM).asString());
        answers.add(SurfaceFixture.call().get(SurfaceFixture.API + "/" + SCOPE + "/decision")
            .asString());
        answers.add(SurfaceFixture.call().header("If-Match", "2000-01-01T00:00:00Z")
            .body(SurfaceFixture.fields("content", "changed")).patch(ITEM).asString());
        answers.add(SurfaceFixture.call().get(SurfaceFixture.API + "/" + SCOPE
            + "/decision/absent").asString());
        answers.add(SurfaceFixture.call().header("If-Match", etag()).delete(ITEM).asString());

        List<String> offences = new ArrayList<>();
        for (String answer : answers) {
            walk(JSON.readTree(answer), "$", offences);
        }
        assertThat(offences).as("members with no value, and instants of another form")
            .isEmpty();
        assertThat(answers).as("the stale-token refusal carried the entry, and an entry "
                + "carried its instants, so both were walked at all")
            .anySatisfy(a -> assertThat(a).contains("CONFLICT_TOKEN_STALE")
                .contains("\"fields\""))
            .anySatisfy(a -> assertThat(a).contains("created_at"));
    }

    @Test
    void an_entry_without_a_reference_carries_no_reference_member() {
        Mcp.call("memory_create", args("scope", SCOPE, "selector", "decision",
            "fields", Map.of("key", "decision.serialised", "type", "decision",
                "content", "written without a reference")));
        assertThat(Mcp.call("memory_read", args("address", ADDRESS)).map("fields"))
            .as("on the assistant surface").doesNotContainKey("reference")
            .containsKey("content");
        assertThat(SurfaceFixture.call().get(ITEM).jsonPath().getMap("fields"))
            .as("on the generic surface").doesNotContainKey("reference")
            .containsKey("content");
    }

    @Test
    void an_instant_is_written_to_the_microsecond_in_utc() throws Exception {
        assertThat(service.writeValueAsString(Instant.parse("2026-10-28T19:03:49.123456789Z")))
            .as("an instant to the nanosecond is written to the microsecond, in UTC; "
                + "Jackson's own serializer would write all nine digits")
            .isEqualTo("\"2026-10-28T19:03:49.123456Z\"");
    }

    @Test
    void not_found_carries_no_data_member_at_all() {
        Response answer = SurfaceFixture.call().get(SurfaceFixture.API + "/" + SCOPE
            + "/decision/absent");
        assertThat(answer.jsonPath().getMap("$")).containsOnlyKeys("reason", "message");
        assertThat(answer.asString()).doesNotContain("null");
    }

    @Test
    void an_envelope_that_could_not_be_read_answers_with_a_null_id() throws Exception {
        Response answer = SurfaceFixture.call().body("not json").post(Mcp.PATH);
        JsonNode envelope = JSON.readTree(answer.asString());
        assertThat(envelope.has("id"))
            .as("JSON-RPC answers an unreadable request with the id member present and null; "
                + "the global rule that a null member is absent must not take it away")
            .isTrue();
        assertThat(envelope.get("id").isNull()).isTrue();
    }

    private static void walk(JsonNode node, String path, List<String> offences) {
        if (node.isNull()) {
            offences.add(path + " is null");
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String at = path + "." + member.getKey();
                if (INSTANT_MEMBERS.contains(member.getKey()) && member.getValue().isTextual()
                        && !INSTANT.matcher(member.getValue().asText()).matches()) {
                    offences.add(at + " is " + member.getValue().asText());
                }
                walk(member.getValue(), at, offences);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", offences);
            }
        }
    }

    private static String etag() {
        return SurfaceFixture.call().get(ITEM).header("ETag");
    }
}
