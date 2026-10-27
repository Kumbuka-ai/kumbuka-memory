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
 * What a {@code query} was narrowed to.
 *
 * <p>A record rather than a parameter list: the predicates are the thing that
 * grows, and a verb whose signature grew with them would have every caller
 * edited for each one. How the narrowed listing is walked is
 * {@link PageRequest}'s.
 *
 * @param type the kind of entry, or null for every kind
 * @param text a substring of the content, case-insensitively, or null
 */
public record QueryFilter(@Nullable EntryType type, @Nullable String text) {
}
