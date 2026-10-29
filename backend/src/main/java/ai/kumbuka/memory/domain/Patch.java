/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import java.util.List;
import java.util.Optional;

/**
 * What an {@code update} changes.
 *
 * <p>Three optionals and one flag. An empty optional is the field not
 * mentioned, and the entry keeps its value; a present one is the new value.
 * {@code reference} is nullable in the table, so removing it is an
 * instruction of its own, {@code referenceRemoved}: an optional cannot hold a
 * null, and input {@code null} means "absent" on every path (C7.4), so
 * neither could say "remove". On both surfaces a caller removes the
 * reference by sending it as the empty text.
 *
 * <p>Scope and key are absent as values. They are fixed for the life of an
 * entry, and a field that cannot be changed has no place among the values
 * that say what changed — a caller that sends one is refused by name rather
 * than having it silently ignored. {@code fixed} names those the caller sent,
 * so that the refusal falls once the scope is resolved (C1.3), and is empty on
 * every path that cannot name one.
 */
public record Patch(Optional<String> type, Optional<String> content,
                    Optional<String> reference, boolean referenceRemoved, List<String> fixed) {

    public Patch {
        fixed = List.copyOf(fixed);
    }

    /** A patch that removes nothing and names no fixed field. */
    public Patch(Optional<String> type, Optional<String> content, Optional<String> reference) {
        this(type, content, reference, false, List.of());
    }

    /**
     * The patch a caller's values ask for: a reference sent as the empty text
     * removes it, any other text sets it, and an absent one leaves it alone.
     */
    public static Patch of(Optional<String> type, Optional<String> content,
                           Optional<String> reference, List<String> fixed) {
        boolean removed = reference.filter(String::isEmpty).isPresent();
        return new Patch(type, content, reference.filter(text -> !text.isEmpty()), removed,
            fixed);
    }

    /** Whether this patch asks for nothing at all. */
    public boolean isEmpty() {
        return type.isEmpty() && content.isEmpty() && reference.isEmpty() && !referenceRemoved;
    }
}
