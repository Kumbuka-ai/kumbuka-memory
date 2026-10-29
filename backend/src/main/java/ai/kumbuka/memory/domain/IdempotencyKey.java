/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.jspecify.annotations.Nullable;

/**
 * The idempotency key a {@code create} carries, or that it carries none
 * (C10.4).
 *
 * <p>Two cases and no third: a key the caller chose, and no key. A blank key
 * is no key, because a key nobody can tell from the empty one would make
 * every blank-keyed create a repeat of the first.
 */
public sealed interface IdempotencyKey {

    /** The caller chose a key. */
    record Given(String value) implements IdempotencyKey {
    }

    /** The caller chose none, and every arrival of the call is a call. */
    record NotGiven() implements IdempotencyKey {
    }

    /** The one instance of the absent case; it carries nothing to distinguish. */
    IdempotencyKey NONE = new NotGiven();

    /** The key an argument or a header carries, absent or present. */
    static IdempotencyKey of(@Nullable String value) {
        return value == null || value.isBlank() ? NONE : new Given(value);
    }
}
