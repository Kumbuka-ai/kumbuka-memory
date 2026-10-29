/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The six kinds of entry, and the order a digest reports them in.
 *
 * <h2>Why an enum where the database has a check constraint</h2>
 *
 * The constraint is the backstop and this is the vocabulary. What the enum
 * buys that the constraint cannot is exhaustiveness at compile time: the
 * digest reports a section per type, and a seventh type added to the
 * constraint alone would produce a digest that silently omits it. A switch
 * over this enum with no {@code default} is what turns that into a build
 * failure.
 *
 * <h2>The declaration order IS the digest order</h2>
 *
 * Constraints first, then what was decided, then how it is done, then the
 * words, then where things stand, and the unsettled questions last. A reader
 * meets the bounds before the choices made inside them, and meets what is
 * still open only after everything that is settled — which is the whole
 * difference between a digest and a list.
 *
 * <p>{@link #values()} returns constants in declaration order, which is a
 * guarantee of the language rather than of this class, so the order is not
 * restated as a second list somewhere.
 */
public enum EntryType {

    CONSTRAINT("constraint"),
    DECISION("decision"),
    CONVENTION("convention"),
    GLOSSARY("glossary"),
    STATUS("status"),
    OPEN_QUESTION("open_question");

    /** The name on the wire and in the database. Never the enum constant's own. */
    private final String wire;

    EntryType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * The selector entries of this type stand under: the type's word in hyphen
     * form, never with an underscore, so {@code open-question} for
     * {@code open_question}.
     *
     * <p>Declared as the fixed values of an argument that carries a selector.
     * The address grammar admits other selectors as well and does not yet tie
     * a selector to a type.
     */
    public String selector() {
        return wire.replace('_', '-');
    }

    /** The six, in the digest's order. */
    public static List<EntryType> inDigestOrder() {
        return List.of(values());
    }

    /** The six wire names, for a refusal that has to say what was expected. */
    public static List<String> wireNames() {
        return Arrays.stream(values()).map(EntryType::wire).toList();
    }

    /**
     * Every name a caller may write a type as: the six wire names, and beside
     * each one written with an underscore its hyphen form, the vocabulary form
     * of C7.6. {@code open_question} stays accepted until the
     * breaking step; an answer still writes the wire name.
     */
    public static List<String> acceptedNames() {
        List<String> accepted = new ArrayList<>();
        for (EntryType type : values()) {
            accepted.add(type.wire);
            if (!type.selector().equals(type.wire)) {
                accepted.add(type.selector());
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * The type a name denotes, in either of its accepted spellings, or empty.
     *
     * <p>Empty rather than a refusal: whether an unknown type is a malformed
     * call or a malformed row is not this class's to decide, and the two get
     * different refusals from the callers that do decide it.
     */
    public static Optional<EntryType> of(@Nullable String name) {
        return Arrays.stream(values())
            .filter(t -> t.wire.equals(name) || t.selector().equals(name))
            .findFirst();
    }
}
