/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The values a new entry is laid down with.
 *
 * <p>The address is not among them. It is the call's target, not a value of
 * the entry — with the one exception that the key IS stored, which is why
 * {@code create} takes it and {@code read} answers it while the address is
 * derived from it rather than stored beside it.
 *
 * @param type      one of the six, by its name; absent where the caller sent
 *                  none, which the verb refuses
 * @param content   the entry itself; absent where the caller sent none, which
 *                  the verb refuses
 * @param reference an optional provenance pointer; absent where there is none
 * @param fixed     the fields fixed for the life of an entry the caller tried
 *                  to set, by name; refused once the scope is resolved (C1.3).
 *                  Empty on every path that cannot name one.
 */
public record Draft(@Nullable String type, @Nullable String content,
                    @Nullable String reference, List<String> fixed) {

    public Draft {
        fixed = List.copyOf(fixed);
    }

    /** A draft that names no fixed field. */
    public Draft(@Nullable String type, @Nullable String content, @Nullable String reference) {
        this(type, content, reference, List.of());
    }
}
