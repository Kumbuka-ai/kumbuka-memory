/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryView;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Projections of an entry for probes that need one without a database. */
final class Views {

    /** The permissions of the {@code owner} role, as the catalogue names them. */
    private static final Set<String> OWNER = Set.of("memory.entry.read", "memory.entry.create",
        "memory.entry.update", "memory.entry.withdraw", "memory.entry.tag");

    /** The permission of the {@code memory-reader} role. */
    private static final Set<String> READER = Set.of("memory.entry.read");

    private Views() {
    }

    /**
     * An entry in force in an open scope, projected for a caller who may
     * write -- one holding every permission of the service -- or who may only
     * read.
     */
    static EntryView entry(boolean mayWrite) {
        Instant at = Instant.parse("2026-10-06T00:00:00Z");
        return new EntryView(new EntryAddress("kumbuka", "convention", "branch-names"),
            "kumbuka", "convention.branch-names", "convention", "a text", null, "published",
            false, at, at, at.toString(), true, mayWrite ? OWNER : READER, List.of());
    }
}
