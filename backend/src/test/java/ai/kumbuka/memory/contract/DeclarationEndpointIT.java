/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration the generic surface publishes, against an independent
 * transcription of it (ADR-0039): the counter-probe.
 *
 * <p>{@code GET /api/declaration} must carry, for every verb, its identity,
 * its name on the REST surface, its effect class in the router's names, its
 * arguments with type, requiredness, default and the values of a fixed set,
 * the shape of its answer, the reasons a refusal may name, and its texts; and
 * the whole carries a version. Every expectation comes from
 * {@link DeclarationTranscript} or from the contract copy, neither of which
 * imports anything from the production packages: all three classes are
 * marked {@link CounterProbe}, and {@code CounterProbeIndependenceTest} holds
 * every class so marked to that.
 *
 * <p>The red run, observed on 2026-10-15: with the effect class of
 * {@code memory_withdraw} declared as {@code ITEM_DELETE} in the service and
 * the argument {@code page_size} declared as {@code limit}, this probe turned
 * red naming both -- {@code memory_withdraw.effect_class: transcribed
 * ITEM_TRANSITION, served ITEM_DELETE}, and {@code memory_query.page_size}
 * with its type, requiredness, default and bounds served as absent.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
@CounterProbe
class DeclarationEndpointIT {

    private static final String PATH = "/api/declaration";

    @Test
    void every_verb_carries_what_the_transcription_says() {
        JsonPath declaration = fetch(PATH);
        List<Map<String, Object>> calls = declaration.getList("calls");
        List<DeclarationTranscript.Verb> expected = DeclarationTranscript.verbs();
        assertThat(calls.stream().map(c -> c.get("identity")).toList())
            .as("one identity per verb, memory_<verb>, in the contract's order")
            .containsExactlyElementsOf(expected.stream()
                .map(DeclarationTranscript.Verb::identity).toList());

        List<String> differing = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            compare(expected.get(i), calls.get(i), differing);
        }
        assertThat(differing).as("the served declaration and the transcription differ")
            .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_verb_carries_its_texts() {
        JsonPath declaration = fetch(PATH);
        Map<String, String> descriptions = Contract.descriptions();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> call : declaration.<Map<String, Object>>getList("calls")) {
            String identity = (String) call.get("identity");
            if (!descriptions.get(identity).equals(call.get("description"))) {
                missing.add(identity + " description differs from the contract: "
                    + call.get("description"));
            }
            for (Map<String, Object> argument : (List<Map<String, Object>>) call.get("arguments")) {
                String where = identity + "." + argument.get("name");
                String description = (String) argument.get("description");
                if (blank(description)) {
                    missing.add(where + " has no description");
                } else if (!Boolean.TRUE.equals(argument.get("required"))
                        && !description.contains("left out or sent as null")) {
                    missing.add(where + " is optional and its description names no default "
                        + "and no null (DEC-0043 R17): " + description);
                }
                Object values = argument.get("value_descriptions");
                if (values instanceof List<?> list) {
                    for (Object value : list) {
                        if (blank(((Map<String, Object>) value).get("description"))) {
                            missing.add(where + " value " + value + " has no description");
                        }
                    }
                }
            }
        }
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> reason : declaration.<Map<String, Object>>getList("reasons")) {
            codes.add((String) reason.get("reason"));
            if (blank(reason.get("message_pattern")) || blank(reason.get("remedy"))) {
                missing.add(reason.get("reason") + " has no message or no remedy");
            }
            for (Map<String, Object> message
                    : (List<Map<String, Object>>) reason.get("messages")) {
                if (blank(message.get("key")) || blank(message.get("template"))) {
                    missing.add(reason.get("reason") + " has a message with no key or template");
                }
            }
        }
        assertThat(missing).isEmpty();
        List<String> declared = new ArrayList<>(Contract.surfaceReasons());
        declared.addAll(Contract.genericOnlyReasons());
        assertThat(codes).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void the_declaration_is_versioned_and_one_on_every_path_that_serves_it() {
        JsonPath served = fetch(PATH);
        String version = served.getString("version");
        assertThat(version).as("a version over the content").startsWith("sha256:")
            .hasSize("sha256:".length() + 64);
        assertThat(fetch(PATH).getString("version"))
            .as("an unchanged declaration keeps its version").isEqualTo(version);
        for (String transitional : List.of("/api", "/mcp/declaration")) {
            assertThat(fetch(transitional).getMap("$"))
                .as("%s serves the declaration of %s until the router binds it there (C5.1)",
                    transitional, PATH)
                .isEqualTo(served.getMap("$"));
        }
    }

    // ======================================================================

    @SuppressWarnings("unchecked")
    private static void compare(DeclarationTranscript.Verb verb, Map<String, Object> call,
                                List<String> into) {
        String id = verb.identity();
        same(into, id + ".call", id, call.get("call"));
        same(into, id + ".rest_name", verb.restName(), call.get("rest_name"));
        same(into, id + ".effect_class", verb.effectClass(), call.get("effect_class"));
        same(into, id + ".at_scope_depth", verb.atScopeDepth(), call.get("at_scope_depth"));
        same(into, id + ".technical_address", verb.technicalAddress(),
            Boolean.TRUE.equals(call.get("technical_address")));
        Map<String, Object> answer = (Map<String, Object>) call.get("answer");
        same(into, id + ".answer.shape", verb.answer(), answer.get("shape"));
        same(into, id + ".answer.members", verb.members(), answer.get("members"));
        same(into, id + ".refusals", verb.refusals(), call.get("refusals"));

        List<Map<String, Object>> arguments = (List<Map<String, Object>>) call.get("arguments");
        same(into, id + ".arguments", verb.arguments().stream()
            .map(DeclarationTranscript.Arg::name).toList(),
            arguments.stream().map(a -> a.get("name")).toList());
        for (DeclarationTranscript.Arg arg : verb.arguments()) {
            Map<String, Object> served = arguments.stream()
                .filter(a -> arg.name().equals(a.get("name"))).findFirst().orElse(Map.of());
            String where = id + "." + arg.name();
            same(into, where + ".type", arg.type(), served.get("type"));
            same(into, where + ".required", arg.required(), served.get("required"));
            same(into, where + ".placement", arg.placement(), served.get("placement"));
            same(into, where + ".default", arg.fallback(), served.get("default"));
            Object values = served.get("values") instanceof List<?> list ? list : List.of();
            same(into, where + ".values", arg.values(), values);
            List<Object> described = served.get("value_descriptions") instanceof List<?> list
                ? list.stream().map(v -> ((Map<String, Object>) v).get("value")).toList()
                : List.of();
            same(into, where + ".value_descriptions", arg.values(), described);
            if ("page_size".equals(arg.name())) {
                DeclarationTranscript.PAGE_SIZE_BOUNDS.forEach((bound, value) ->
                    same(into, where + "." + bound, value, served.get(bound)));
            }
        }
    }

    private static void same(List<String> into, String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            into.add(what + ": transcribed " + expected + ", served " + actual);
        }
    }

    private static JsonPath fetch(String path) {
        return given().accept(ContentType.JSON).get(path).then().statusCode(200)
            .extract().jsonPath();
    }

    private static boolean blank(Object text) {
        return !(text instanceof String s) || s.isBlank();
    }
}
