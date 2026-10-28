/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema, of the kind a tool's input takes: an object closed at its
 * level and its properties, and for a property its type, its description and
 * the keywords the declaration gives it.
 *
 * @param description absent on the outer object of a call
 * @param properties the properties of an object, by name, in declaration order;
 *                   absent on a schema of no object
 * @param required absent on a schema of no object
 * @param additionalProperties absent on a schema of no object
 * @param items absent on a schema of no list
 * @param minItems absent where a list may be empty or the schema is of no list
 * @param values absent where the value is not of a closed set
 * @param fallback the value an absent argument takes; absent where it has none
 * @param minimum absent where a number is not bounded below
 * @param maximum absent where a number is not bounded above
 */
public record SchemaPayload(
    @JsonProperty("type") String type,
    @JsonProperty("description") @Nullable String description,
    @JsonProperty("properties") @Nullable Map<String, SchemaPayload> properties,
    @JsonProperty("required") @Nullable List<String> required,
    @JsonProperty("additionalProperties") @Nullable Boolean additionalProperties,
    @JsonProperty("items") @Nullable SchemaPayload items,
    @JsonProperty("minItems") @Nullable Integer minItems,
    @JsonProperty("enum") @Nullable List<String> values,
    @JsonProperty("default") @Nullable Object fallback,
    @JsonProperty("minimum") @Nullable Integer minimum,
    @JsonProperty("maximum") @Nullable Integer maximum) {
}
