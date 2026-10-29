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
 * <p>Three optionals, and the optional is doing real work: {@code reference}
 * is nullable in the table, so "leave it alone" and "clear it" are different
 * instructions and a plain null could only express one of them. An empty
 * optional is the field not mentioned; a present optional holding null is the
 * field cleared.
 *
 * <p>Scope and key are absent as values. They are fixed for the life of an
 * entry, and a field that cannot be changed has no place among the values
 * that say what changed — a caller that sends one is refused by name rather
 * than having it silently ignored. {@code fixed} names those the caller sent,
 * so that the refusal falls once the scope is resolved (C1.3), and is empty on
 * every path that cannot name one.
 */
public record Patch(Optional<String> type, Optional<String> content,
                    Optional<String> reference, List<String> fixed) {

    public Patch {
        fixed = List.copyOf(fixed);
    }

    /** A patch that names no fixed field. */
    public Patch(Optional<String> type, Optional<String> content, Optional<String> reference) {
        this(type, content, reference, List.of());
    }

    /** Whether this patch asks for nothing at all. */
    public boolean isEmpty() {
        return type.isEmpty() && content.isEmpty() && reference.isEmpty();
    }
}
