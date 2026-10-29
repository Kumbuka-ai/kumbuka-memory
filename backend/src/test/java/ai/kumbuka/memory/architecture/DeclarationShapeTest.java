/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import ai.kumbuka.memory.CounterProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of a service declaration, against a schema held in the test tree
 * (C5.3, C5.4, C5.5).
 *
 * <p>The schema is {@code contract/declaration-shape.schema.json}, written
 * from the contract and the reference's form of a reason and of a fixed set,
 * and from nothing this service produces; this class is a counter-probe and
 * imports nothing from the production packages. The schema closes every level
 * the contract enumerates, so a member the contract does not name is reported
 * as much as one it requires and that is missing; what it admits beyond the
 * contract its {@code $comment} names. The {@code shape_version} is pinned to
 * the one value C5.5 names for every service, so the expectation stands here
 * and not in the code that writes the declaration.
 *
 * <p>Three rules are beyond what the schema subset says and are checked here:
 * an argument's {@code default} is a value of the argument's own JSON type,
 * never its text; the fixed values of an argument that carries a selector
 * are written with hyphens, never with an underscore, as every vocabulary
 * value is; and no member on any level carries {@code null} -- a member
 * without a value is left out, so a {@code null} in the declaration is a
 * member its version was taken over and the wire never carries. The last is
 * taken from kumbuka-worklist.
 *
 * <h2>Where the served declaration is read</h2>
 *
 * The declaration carries the texts of its message bundles, which exist only
 * in a running application. So this class holds the schema and the check and
 * observes the check refusing planted declarations on every build; the
 * declaration the service serves is checked by {@link #violations(Map)} from
 * {@code DeclarationAccessIT}, which reads it from {@code GET /api/declaration}.
 *
 * <p>The validator reads the keywords the schema uses and no others: {@code
 * type}, {@code required}, {@code properties}, {@code additionalProperties},
 * {@code items}, {@code minItems}, {@code enum}, {@code const} and {@code
 * pattern}. A keyword it does not read is refused rather than ignored, so the
 * schema cannot say more than is checked. Taken from kumbuka-dispatch, with
 * the selector rule added.
 */
@CounterProbe
public class DeclarationShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> KEYWORDS = List.of("$comment", "type", "required",
        "properties", "additionalProperties", "items", "minItems", "enum", "const", "pattern");

    /** The argument that carries a selector, by the name C5.4's rule reads it under. */
    private static final String SELECTOR = "selector";

    /** A vocabulary value: words of lowercase letters joined by single hyphens. */
    private static final Pattern HYPHENATED = Pattern.compile("^[a-z]+(-[a-z]+)*$");

    /** The violations of one declaration, by JSON path; empty where it conforms. */
    public static List<String> violations(Map<String, Object> declaration) {
        List<String> found = new ArrayList<>();
        nulls(declaration, "$", found);
        check(schema(), declaration, "$", found);
        arguments(declaration, found);
        return found;
    }

    @Test
    void a_declaration_of_the_contract_shape_conforms() {
        assertThat(violations(conforming()))
            .as("a declaration written to C5.3 to C5.5 must pass the schema, or the schema "
                + "says something the contract does not")
            .isEmpty();
    }

    /** The red state, observed on every build: one planted defect per rule. */
    @Test
    void the_check_reports_each_way_a_declaration_leaves_the_contract_shape() {
        Map<String, Object> depth = conforming();
        call(depth).put("depth", "ITEM");
        assertThat(violations(depth))
            .as("RED STATE, observed: a depth the effect class already names is not a "
                + "member of a call (C5.4)")
            .containsExactly("$.calls[0]: 'depth' is not a member of this level");

        Map<String, Object> lowerPlacement = conforming();
        argument(lowerPlacement).put("placement", "top");
        assertThat(violations(lowerPlacement))
            .as("RED STATE, observed: a placement written in lower case")
            .containsExactly("$.calls[0].arguments[0].placement: 'top' is not one of [TOP, FIELDS]");

        Map<String, Object> textDefault = conforming();
        Map<String, Object> pageSize = argument(textDefault);
        pageSize.put("type", "integer");
        pageSize.put("required", false);
        pageSize.put("default", "50");
        assertThat(violations(textDefault))
            .as("RED STATE, observed: an integer argument whose default is its text")
            .containsExactly("$.calls[0].arguments[0].default: a string where the argument "
                + "is integer");

        Map<String, Object> describedValues = conforming();
        selector(describedValues).put("values", List.of(Map.of("value", "decision",
            "description", "a choice")));
        assertThat(violations(describedValues))
            .as("RED STATE, observed: fixed values written as pairs of value and "
                + "description instead of names beside value_descriptions")
            .containsExactly("$.calls[0].arguments[1].values[0]: object where the schema "
                + "says string");

        Map<String, Object> underscore = conforming();
        selector(underscore).put("values", List.of("decision", "open_question"));
        assertThat(violations(underscore))
            .as("RED STATE, observed: a selector written with an underscore")
            .containsExactly("$.calls[0].arguments[1].values[1]: the selector 'open_question' "
                + "is not written with hyphens");

        Map<String, Object> oldReason = conforming();
        reason(oldReason).put("steps", List.of("memory_read"));
        reason(oldReason).put("surface", "both");
        assertThat(violations(oldReason))
            .as("RED STATE, observed: a reason in the second shape, with steps and surface")
            .containsExactly("$.reasons[0]: 'steps' is not a member of this level",
                "$.reasons[0]: 'surface' is not a member of this level");

        Map<String, Object> unnamed = conforming();
        unnamed.remove("address_form");
        unnamed.put("answered_as", Map.of());
        assertThat(violations(unnamed))
            .as("RED STATE, observed: a required member missing and an undeclared one present")
            .containsExactly("$: missing 'address_form'",
                "$: 'answered_as' is not a member of this level");

        Map<String, Object> nullAtTheTop = conforming();
        nullAtTheTop.put("technical_address_form", null);
        assertThat(violations(nullAtTheTop))
            .as("RED STATE, observed: a null member on the top level")
            .containsExactly("$.technical_address_form: null; a member without a value is "
                + "left out");

        Map<String, Object> nullInACall = conforming();
        call(nullInACall).put("at_scope_depth", null);
        assertThat(violations(nullInACall))
            .as("RED STATE, observed: a null member of a call")
            .containsExactly("$.calls[0].at_scope_depth: null; a member without a value is "
                + "left out");

        Map<String, Object> nullDefault = conforming();
        argument(nullDefault).put("default", null);
        assertThat(violations(nullDefault))
            .as("RED STATE, observed: an argument without a fallback that declares one as null")
            .containsExactly("$.calls[0].arguments[0].default: null; a member without a "
                + "value is left out");

        Map<String, Object> nullInADescribedValue = conforming();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("value", "decision");
        described.put("description", null);
        selector(nullInADescribedValue).put("value_descriptions", List.of(described));
        assertThat(violations(nullInADescribedValue))
            .as("RED STATE, observed: a null member in an element of a list, two levels down")
            .containsExactly("$.calls[0].arguments[1].value_descriptions[0].description: "
                + "null; a member without a value is left out");

        Map<String, Object> nullInAReason = conforming();
        reason(nullInAReason).put("remedy", null);
        assertThat(violations(nullInAReason))
            .as("RED STATE, observed: a null member of a reason")
            .containsExactly("$.reasons[0].remedy: null; a member without a value is left out");

        Map<String, Object> otherShape = conforming();
        otherShape.put("shape_version", "2");
        assertThat(violations(otherShape))
            .as("RED STATE, observed: a shape version other than the one C5.5 names")
            .containsExactly("$.shape_version: '2' is not '3'");
    }

    // ======================================================================
    // The check
    // ======================================================================

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String path,
                              List<String> found) {
        for (String keyword : schema.keySet()) {
            if (!KEYWORDS.contains(keyword) && !keyword.equals("default")) {
                throw new IllegalStateException("the schema uses '" + keyword + "', which "
                    + "this check does not read");
            }
        }
        if (value == null) {
            return;
        }
        Object type = schema.get("type");
        if (type != null && !typeOf(value).equals(type)) {
            found.add(path + ": " + typeOf(value) + " where the schema says " + type);
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            found.add(path + ": '" + value + "' is not one of " + allowed);
        }
        if (schema.containsKey("const") && !schema.get("const").equals(value)) {
            found.add(path + ": '" + value + "' is not '" + schema.get("const") + "'");
        }
        if (schema.get("pattern") instanceof String pattern && value instanceof String text
                && !Pattern.compile(pattern).matcher(text).find()) {
            found.add(path + ": '" + text + "' does not match " + pattern);
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> members = (Map<String, Object>) object;
            for (Object required : (List<Object>) schema.getOrDefault("required", List.of())) {
                if (!members.containsKey(required)) {
                    found.add(path + ": missing '" + required + "'");
                }
            }
            Map<String, Object> properties =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> member : members.entrySet()) {
                Object declared = properties.get(member.getKey());
                if (declared != null) {
                    check((Map<String, Object>) declared, member.getValue(),
                        path + "." + member.getKey(), found);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    found.add(path + ": '" + member.getKey() + "' is not a member of this level");
                }
            }
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()) {
                found.add(path + ": fewer than " + min + " items");
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < list.size(); i++) {
                    check((Map<String, Object>) items, list.get(i), path + "[" + i + "]", found);
                }
            }
        }
    }

    /** No member on any level carries null: one without a value is left out. */
    private static void nulls(Object value, String path, List<String> found) {
        if (value instanceof Map<?, ?> object) {
            object.forEach((name, member) -> {
                if (member == null) {
                    found.add(path + "." + name + ": null; a member without a value is left out");
                } else {
                    nulls(member, path + "." + name, found);
                }
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                nulls(list.get(i), path + "[" + i + "]", found);
            }
        }
    }

    /**
     * An argument's default is a value of its own type, never its text (C5.4),
     * and a selector is written with hyphens.
     */
    @SuppressWarnings("unchecked")
    private static void arguments(Map<String, Object> declaration, List<String> found) {
        if (!(declaration.get("calls") instanceof List<?> calls)) {
            return;
        }
        for (int c = 0; c < calls.size(); c++) {
            if (!(calls.get(c) instanceof Map<?, ?> call)
                    || !(call.get("arguments") instanceof List<?> arguments)) {
                continue;
            }
            for (int a = 0; a < arguments.size(); a++) {
                Map<String, Object> argument = (Map<String, Object>) arguments.get(a);
                String path = "$.calls[" + c + "].arguments[" + a + "]";
                if (argument.get("default") != null) {
                    String actual = typeOf(argument.get("default"));
                    if (!actual.equals(argument.get("type"))) {
                        found.add(path + ".default: a " + actual + " where the argument is "
                            + argument.get("type"));
                    }
                }
                if (SELECTOR.equals(argument.get("name"))
                        && argument.get("values") instanceof List<?> values) {
                    for (int v = 0; v < values.size(); v++) {
                        if (values.get(v) instanceof String value
                                && !HYPHENATED.matcher(value).matches()) {
                            found.add(path + ".values[" + v + "]: the selector '" + value
                                + "' is not written with hyphens");
                        }
                    }
                }
            }
        }
    }

    private static String typeOf(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case Integer ignored -> "integer";
            case Long ignored -> "integer";
            case Number ignored -> "number";
            default -> value.getClass().getSimpleName();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() {
        try (InputStream in = DeclarationShapeTest.class.getClassLoader()
                .getResourceAsStream("contract/declaration-shape.schema.json")) {
            assertThat(in).as("the schema must be on the test classpath").isNotNull();
            return JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ======================================================================
    // A declaration of the contract shape, built here for the red states
    // ======================================================================

    private static Map<String, Object> conforming() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "scope");
        scope.put("type", "string");
        scope.put("required", true);
        scope.put("placement", "TOP");
        scope.put("description", "the scope");
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("name", SELECTOR);
        selector.put("type", "string");
        selector.put("required", false);
        selector.put("placement", "TOP");
        selector.put("description", "the selector");
        selector.put("values", List.of("decision", "open-question"));
        selector.put("value_descriptions", List.of(
            Map.of("value", "decision", "description", "a choice"),
            Map.of("value", "open-question", "description", "a question")));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("identity", "thing_query");
        call.put("call", "thing_query");
        call.put("rest_name", "query");
        call.put("effect_class", "COLLECTION_READ");
        call.put("at_scope_depth", "SCOPE_READ");
        call.put("description", "lists");
        call.put("arguments", new ArrayList<>(List.of(scope, selector)));
        call.put("answer", Map.of());
        call.put("refusals", List.of("NOT_FOUND"));
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reason", "NOT_FOUND");
        reason.put("message_pattern", "Nothing is there. Read another.");
        reason.put("messages", List.of(Map.of("key", "notFound", "template", "Nothing is there.")));
        reason.put("remedy", "Read another.");
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("version", "sha256:" + "0".repeat(64));
        declaration.put("shape_version", "3");
        declaration.put("service", "thing");
        declaration.put("scheme", "thing");
        declaration.put("address_form", "thing://<scope>/<selector>/<id>");
        declaration.put("technical_address_form", "thing://<uuid>");
        declaration.put("effect_classes", List.of("COLLECTION_READ", "SCOPE_READ"));
        declaration.put("calls", new ArrayList<>(List.of(call)));
        declaration.put("reasons", new ArrayList<>(List.of(reason)));
        return declaration;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("calls")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argument(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) call(declaration).get("arguments")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> selector(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) call(declaration).get("arguments")).get(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> reason(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("reasons")).get(0);
    }
}
