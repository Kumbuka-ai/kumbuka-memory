/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.adapter.payload.SchemaPayload;
import ai.kumbuka.memory.adapter.payload.ToolPayload;
import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.AssistantVerb;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool list, as {@code tools/list} answers it, derived from the declaration.
 *
 * <p>Nothing here is written by hand: the names, the descriptions and every
 * schema come from {@link AssistantVerb}. A tool list kept beside the
 * declaration would be a second statement of the surface, and the two would
 * disagree the first time one of them changed.
 *
 * <h2>Closed at every level</h2>
 *
 * Every object schema carries {@code additionalProperties: false}, the
 * {@code fields} object included. One closed level is as much use as none:
 * the argument a caller misspells is as likely to sit inside {@code fields}
 * as beside it, and a schema that admits it tells the caller it was read.
 *
 * <h2>The keywords an argument publishes, read by name</h2>
 *
 * An argument carries its further schema keywords as the declaration states
 * them. Each is read here by name into its member of the schema. A keyword
 * outside {@link Argument#SCHEMA_KEYWORDS} refuses the start of the service,
 * before any {@code tools/list}, rather than vanishing from the tool list
 * while the declaration still publishes it.
 */
public final class McpTools {

    private McpTools() {
    }

    /** The tool names, in the order of the declaration, as {@code tools/list} lists them. */
    public static List<String> names() {
        return Arrays.stream(AssistantVerb.values()).map(AssistantVerb::call).toList();
    }

    public static List<ToolPayload> declared() {
        return Arrays.stream(AssistantVerb.values())
            .map(verb -> new ToolPayload(verb.call(), verb.description(), schemaOf(verb)))
            .toList();
    }

    static SchemaPayload schemaOf(AssistantVerb verb) {
        Map<String, SchemaPayload> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Argument argument : verb.topArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        if (verb.writesFields()) {
            Map<String, SchemaPayload> fields = new LinkedHashMap<>();
            List<String> requiredFields = new ArrayList<>();
            for (Argument argument : verb.fieldArguments()) {
                fields.put(argument.name(), property(argument));
                if (argument.required()) {
                    requiredFields.add(argument.name());
                }
            }
            properties.put(AssistantVerb.FIELDS,
                closedObject(AssistantVerb.fieldsDescription(), fields, requiredFields));
            required.add(AssistantVerb.FIELDS);
        }
        return closedObject(null, properties, required);
    }

    private static SchemaPayload property(Argument argument) {
        Map<String, Object> keywords = argument.keywords();
        // Refused at the start already (DeclarationGuard); kept here so that
        // no keyword ever vanishes from the tool list while the declaration
        // still publishes it.
        keywords.keySet().stream().filter(keyword -> !Argument.SCHEMA_KEYWORDS.contains(keyword))
            .findFirst()
            .ifPresent(keyword -> {
                throw new IllegalStateException(argument.name() + " publishes the schema "
                    + "keyword " + keyword + ", which the tool schema does not carry");
            });
        return new SchemaPayload(argument.type(), argument.description(), null, null, null,
            items(keywords.get("items")), integer(keywords.get("minItems")),
            strings(keywords.get("enum")), keywords.get("default"),
            integer(keywords.get("minimum")), integer(keywords.get("maximum")));
    }

    /** The rule every element of a list obeys: its type, and the set its values come from. */
    private static @Nullable SchemaPayload items(@Nullable Object items) {
        if (!(items instanceof Map<?, ?> rule)) {
            return null;
        }
        return new SchemaPayload(String.valueOf(rule.get("type")), null, null, null, null,
            null, null, strings(rule.get("enum")), null, null, null);
    }

    private static @Nullable List<String> strings(@Nullable Object values) {
        return values instanceof List<?> list ? list.stream().map(String::valueOf).toList() : null;
    }

    private static @Nullable Integer integer(@Nullable Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static SchemaPayload closedObject(@Nullable String description,
                                              Map<String, SchemaPayload> properties,
                                              List<String> required) {
        return new SchemaPayload("object", description, properties, List.copyOf(required),
            false, null, null, null, null, null, null);
    }
}
