/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import java.util.Map;
import java.util.function.Function;

/**
 * One argument of one call on the assistant surface, as the declaration states it.
 *
 * <p>The input schema, the check that refuses an undeclared argument, the
 * refusal for a missing one and the declaration served at
 * {@code /mcp/declaration} are all read off this record. That is the point of
 * it: a schema assembled in one place and a check written in another would
 * describe two surfaces, and the caller would only ever see one of them.
 *
 * @param argumentName the name the caller writes, as the declaration names it
 * @param type         the JSON type: {@code string}, {@code integer} or {@code array}
 * @param required     whether the call is refused without it
 * @param placement    top-level, or under {@code fields}
 * @param describe     the text that says what the value IS, in its bundle
 *                     ({@link ArgumentDescriptions})
 * @param keywords     further JSON-schema keywords the argument publishes,
 *                     such as an enumeration or bounds; never {@code type} or
 *                     {@code description}
 */
public record Argument(ArgumentName argumentName, String type, boolean required,
                       Placement placement, Function<ArgumentDescriptions, String> describe,
                       Map<String, Object> keywords) {

    public static final String STRING = "string";
    public static final String INTEGER = "integer";
    public static final String ARRAY = "array";

    /** Where an argument sits (DEC-0040). */
    public enum Placement {

        /** Chooses the target, or is a transport artefact such as the conflict token. */
        TOP,

        /** Is written into the entry. */
        FIELDS
    }

    public Argument {
        keywords = Map.copyOf(keywords);
    }

    static Argument top(String name, String type, boolean required,
                        Function<ArgumentDescriptions, String> describe) {
        return top(name, type, required, describe, Map.of());
    }

    static Argument top(String name, String type, boolean required,
                        Function<ArgumentDescriptions, String> describe,
                        Map<String, Object> keywords) {
        return new Argument(new ArgumentName(name), type, required, Placement.TOP, describe,
            keywords);
    }

    static Argument field(String name, boolean required,
                          Function<ArgumentDescriptions, String> describe) {
        return field(name, required, describe, Map.of());
    }

    static Argument field(String name, boolean required,
                          Function<ArgumentDescriptions, String> describe,
                          Map<String, Object> keywords) {
        return new Argument(new ArgumentName(name), STRING, required, Placement.FIELDS,
            describe, keywords);
    }

    /** The name the caller writes. */
    public String name() {
        return argumentName.value();
    }

    /** What the value is, from its bundle. */
    public String description() {
        return describe.apply(Texts.arguments());
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
