/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The orders a listing can be read in, and the whole of them.
 *
 * <p>Two, on both surfaces: by the key, which is the entry's id, and by the
 * moment it was laid down with the key as second key. A moment alone is not an
 * order — two entries can carry the same one — and an order with ties is one a
 * cursor cannot continue without skipping or repeating. The key breaks every
 * tie because it is unique among the entries one caller sees in one scope.
 *
 * <p>Never by the logical id: it is a random uuid, so an order over it is an
 * order nobody can read, and it is not part of any address on this surface.
 */
public enum SortKey {

    /** The key, which is the entry's id within its scope. The default. */
    ID("id"),

    /** The moment the entry was laid down, ties broken by the key. */
    CREATED_AT("created_at");

    private final String wire;

    SortKey(String wire) {
        this.wire = wire;
    }

    /** The name a caller writes. */
    public String wire() {
        return wire;
    }

    /** The order a caller names, if this service declares it. */
    public static Optional<SortKey> of(String wire) {
        return Arrays.stream(values()).filter(k -> k.wire.equals(wire)).findFirst();
    }

    /** Every name a caller may write, in declaration order. */
    public static List<String> wireNames() {
        return Arrays.stream(values()).map(SortKey::wire).toList();
    }

    /** Which way a listing runs. */
    public enum Direction {

        /** Smallest first. The default. */
        ASC("asc"),

        /** Largest first. */
        DESC("desc");

        private final String wire;

        Direction(String wire) {
            this.wire = wire;
        }

        /** The name a caller writes. */
        public String wire() {
            return wire;
        }

        /** The direction a caller names, if this service declares it. */
        public static Optional<Direction> of(String wire) {
            return Arrays.stream(values()).filter(d -> d.wire.equals(wire)).findFirst();
        }

        /** Every name a caller may write, in declaration order. */
        public static List<String> wireNames() {
            return Arrays.stream(values()).map(Direction::wire).toList();
        }
    }
}
