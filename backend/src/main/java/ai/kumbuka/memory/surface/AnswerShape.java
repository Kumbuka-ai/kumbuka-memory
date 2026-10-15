/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import java.util.List;

/**
 * The shape of a verb's answer, by the name the router's catalogue gives it
 * ({@code VerbCatalog.ResponseShape}) and the members it carries.
 *
 * <p>The members are those of the wire records in {@code adapter.payload},
 * which this package may not reach; a test holds the two against each other,
 * so that a member added to an answer and left out here turns red.
 */
public enum AnswerShape {

    /** One entry: the answer of read and update. */
    ENTRY(Members.ENTRY),

    /** One entry at 201 with Location: the answer of create. */
    ENTRY_CREATED(Members.ENTRY),

    /** A page of entries, at both depths of query. */
    ENTRY_LISTING(List.of("objects", "next_cursor", "unaddressable")),

    /** What a withdrawal did, and whether the address is free again. */
    WITHDRAWN(List.of(Members.ADDRESS, "outcome", "address_released", Members.NEXT)),

    /** Everything in force in a scope, grouped by type. */
    DIGEST_VIEW(List.of(Members.ADDRESS, "scopes", "selected_types", "summary", "total_entries",
        "total_token_estimate", "sections", "unaddressable", "truncated"));

    private final List<String> members;

    /** The members several answers share. */
    private static final class Members {

        static final String ADDRESS = "address";
        static final String NEXT = "next";
        static final List<String> ENTRY = List.of(ADDRESS, "fields", "conflict_token", NEXT);

        private Members() {
        }
    }

    AnswerShape(List<String> members) {
        this.members = List.copyOf(members);
    }

    public List<String> members() {
        return members;
    }
}
