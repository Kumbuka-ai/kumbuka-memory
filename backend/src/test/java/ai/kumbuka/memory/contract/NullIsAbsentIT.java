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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON {@code null} on an argument means the argument was not sent
 * (DEC-0043 R17): an optional one takes its default, a required one is
 * refused as {@code ARGUMENT_MISSING}, on the assistant surface and on the
 * generic one alike.
 *
 * <h2>How</h2>
 *
 * Every argument section 5 of the contract gives a tool is sent once as
 * {@code null} and once left out, beside otherwise valid arguments, and the
 * two answers are compared whole. What a write changes between the two calls
 * -- the conflict token and the two timestamps -- is taken out of both before
 * the comparison, and the scope is emptied before each create. The arguments
 * come from the contract copy, not from the declaration, so an argument the
 * declaration gained without the contract is not silently skipped: it would
 * be refused as unknown and turn this probe red.
 *
 * <p>The generic surface is held over the bodies it takes: {@code fields}
 * and each value under it on create and update, and {@code types} on a
 * digest. A query parameter cannot be {@code null}.
 *
 * <p>The red run, observed on 2026-10-15 before this change: on the generic
 * surface a create whose {@code key} was {@code null} answered
 * {@code KEY_MALFORMED}, quoting {@code 'null'} as the key, where the
 * assistant surface answered {@code ARGUMENT_MISSING}. The assistant surface
 * itself was green in the same run: its argument check already read a
 * {@code null} as an absence.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
@CounterProbe
class NullIsAbsentIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "convention.branch-names";
    private static final String ADDRESS = "memory://" + SCOPE + "/convention/branch-names";
    private static final String ITEM = SurfaceFixture.API + "/" + SCOPE
        + "/convention/branch-names";

    /** What a write changes between two otherwise identical calls. */
    private static final Set<String> VOLATILE = Set.of("conflict_token", "created_at",
        "updated_at");

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntry() {
        plant();
    }

    // ======================================================================
    // The assistant surface
    // ======================================================================

    @Test
    void every_argument_of_every_tool_answers_alike_as_null_and_left_out() {
        List<String> differing = new ArrayList<>();
        for (String tool : Contract.tools()) {
            Contract.Arguments declared = Contract.argumentsOf(tool);
            for (String name : declared.top().keySet()) {
                compare(tool, name, false, differing);
            }
            if (!declared.fields().isEmpty()) {
                compare(tool, "fields", false, differing);
            }
            for (String name : declared.fields().keySet()) {
                compare(tool, name, true, differing);
            }
        }
        assertThat(differing)
            .as("null and absence are one thing (DEC-0043 R17)")
            .isEmpty();
    }

    @Test
    void a_required_argument_sent_as_null_is_refused_as_missing() {
        Mcp.Result refused = Mcp.call(Contract.toolFor("read"), args("address", null));
        assertThat(refused.reason()).isEqualTo("ARGUMENT_MISSING");
        assertThat(refused.strings("data.offenders")).containsExactly("address");
    }

    private void compare(String tool, String name, boolean underFields, List<String> into) {
        Map<String, Object> left = validArguments(tool);
        Map<String, Object> sentNull = validArguments(tool);
        Map<String, Object> leftIn = underFields ? fieldsOf(left) : left;
        Map<String, Object> nullIn = underFields ? fieldsOf(sentNull) : sentNull;
        leftIn.remove(name);
        nullIn.put(name, null);

        Map<String, Object> absent = normalised(call(tool, left));
        Map<String, Object> asNull = normalised(call(tool, sentNull));
        if (!absent.equals(asNull)) {
            into.add(tool + " " + (underFields ? "fields." : "") + name + ": left out "
                + absent + ", null " + asNull);
        }
    }

    /** Calls a tool against a fresh state, so that two calls meet the same one. */
    private static Map<String, Object> call(String tool, Map<String, Object> arguments) {
        plant();
        if (arguments.get("conflict_token") != null) {
            arguments.put("conflict_token", token());
        }
        return Mcp.call(tool, arguments).structured();
    }

    /** Arguments under which each tool succeeds, before one of them is taken out. */
    private static Map<String, Object> validArguments(String tool) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        switch (tool.substring(tool.indexOf('_') + 1)) {
            case "create" -> {
                arguments.put("scope", SCOPE);
                arguments.put("selector", "decision");
                arguments.put("fields", new LinkedHashMap<>(Map.of("key", "decision.tabs",
                    "type", "decision", "content", "tabs", "reference",
                    "https://example.org/tabs")));
            }
            case "read" -> arguments.put("address", ADDRESS);
            case "update" -> {
                arguments.put("address", ADDRESS);
                arguments.put("conflict_token", "set per call");
                arguments.put("fields", new LinkedHashMap<>(Map.of("content", "changed",
                    "type", "decision", "reference", "https://example.org/changed")));
            }
            case "withdraw" -> {
                arguments.put("address", ADDRESS);
                arguments.put("conflict_token", "set per call");
            }
            case "query" -> {
                arguments.put("scope", SCOPE);
                arguments.put("selector", "convention");
                arguments.put("type", "convention");
                arguments.put("text", "planted");
                arguments.put("sort", "created_at");
                arguments.put("direction", "desc");
                arguments.put("page_size", 5);
            }
            case "digest" -> {
                arguments.put("scope", SCOPE);
                arguments.put("types", List.of("convention"));
            }
            default -> throw new IllegalStateException("no valid arguments for " + tool);
        }
        return arguments;
    }

    // ======================================================================
    // The generic surface
    // ======================================================================

    @Test
    void every_value_of_a_body_answers_alike_as_null_and_left_out() {
        String collection = SurfaceFixture.API + "/" + SCOPE + "/decision";
        String digest = SurfaceFixture.API + "/" + SCOPE + ":digest";

        List<String> differing = new ArrayList<>();
        for (String name : List.of("key", "type", "content", "reference")) {
            same("create fields." + name, differing, body -> SurfaceFixture.call()
                .body(createBody(name, body)).post(collection));
        }
        same("create fields", differing, body -> SurfaceFixture.call()
            .body(body ? "{\"fields\":null}" : "{}").post(collection));
        for (String name : List.of("content", "type", "reference")) {
            same("update fields." + name, differing, body -> SurfaceFixture.call()
                .header("If-Match", etag()).body(updateBody(name, body)).patch(ITEM));
        }
        same("update fields", differing, body -> SurfaceFixture.call()
            .header("If-Match", etag()).body(body ? "{\"fields\":null}" : "{}").patch(ITEM));
        same("digest types", differing, body -> SurfaceFixture.call()
            .body(body ? "{\"types\":null}" : "{}").post(digest));

        assertThat(differing)
            .as("null and absence are one thing on this surface too (DEC-0043 R17)")
            .isEmpty();

        Response missing = SurfaceFixture.call()
            .body(createBody("key", true)).post(collection);
        assertThat(missing.jsonPath().getString("reason"))
            .as("a required value sent as null is refused as missing, as the assistant "
                + "surface refuses it")
            .isEqualTo("ARGUMENT_MISSING");
        assertThat(missing.jsonPath().getList("data.offenders", String.class))
            .containsExactly("key");
    }

    private static void same(String what, List<String> into,
                             Function<Boolean, Response> sentAsNull) {
        plant();
        Response absent = sentAsNull.apply(false);
        plant();
        Response asNull = sentAsNull.apply(true);
        Map<String, Object> left = normalised(absent.jsonPath().getMap("$"));
        Map<String, Object> nulled = normalised(asNull.jsonPath().getMap("$"));
        if (absent.statusCode() != asNull.statusCode() || !left.equals(nulled)) {
            into.add(what + ": left out " + absent.statusCode() + " " + left + ", null "
                + asNull.statusCode() + " " + nulled);
        }
    }

    private static String createBody(String nulled, boolean sendNull) {
        return body(new String[] {"key", "decision.tabs", "type", "decision",
            "content", "tabs", "reference", "https://example.org/tabs"}, nulled, sendNull);
    }

    private static String updateBody(String nulled, boolean sendNull) {
        return body(new String[] {"content", "changed", "type", "decision",
            "reference", "https://example.org/changed"}, nulled, sendNull);
    }

    /** A body under fields, with one value null or left out. */
    private static String body(String[] namesAndValues, String nulled, boolean sendNull) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i].equals(nulled)) {
                if (sendNull) {
                    kept.add(namesAndValues[i]);
                    kept.add(null);
                }
                continue;
            }
            kept.add(namesAndValues[i]);
            kept.add(namesAndValues[i + 1]);
        }
        return SurfaceFixture.fields(kept.toArray(String[]::new));
    }

    // ======================================================================

    private static void plant() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "convention", "planted"));
    }

    private static String token() {
        return Mcp.call(Contract.toolFor("read"), args("address", ADDRESS))
            .string("conflict_token");
    }

    private static String etag() {
        return SurfaceFixture.call().get(ITEM).header("ETag");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> arguments) {
        return (Map<String, Object>) arguments.get("fields");
    }

    /** The answer without what a write changes between two calls. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalised(Map<String, Object> answer) {
        Map<String, Object> copy = new LinkedHashMap<>();
        answer.forEach((key, value) -> {
            if (VOLATILE.contains(key)) {
                return;
            }
            copy.put(key, value instanceof Map<?, ?> map
                ? normalised((Map<String, Object>) map)
                : value instanceof List<?> list ? list.stream()
                    .map(e -> e instanceof Map<?, ?> m ? normalised((Map<String, Object>) m) : e)
                    .toList()
                : value);
        });
        return copy;
    }
}
