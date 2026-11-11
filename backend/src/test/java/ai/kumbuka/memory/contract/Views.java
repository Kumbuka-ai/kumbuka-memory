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

/** Projections of an entry for probes that need one without a database. */
final class Views {

    private Views() {
    }

    /** An entry in force, projected for a caller who may or may not write. */
    static EntryView entry(boolean writable) {
        Instant at = Instant.parse("2026-10-06T00:00:00Z");
        return new EntryView(new EntryAddress("kumbuka", "convention", "branch-names"),
            "kumbuka", "convention.branch-names", "convention", "a text", null, "published",
            false, at, at, at.toString(), writable, List.of());
    }
}
