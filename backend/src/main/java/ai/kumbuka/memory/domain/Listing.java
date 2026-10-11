/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import java.util.List;

/**
 * One page of a query, and the one member that says whether more follows.
 *
 * <p>{@code nextCursor} is the continuation and the only one. Present, it is
 * what to pass as {@code after} for the page that follows; absent, this page
 * is the last. The page used to say the same thing three times — a flag, a
 * total and a cursor — and a caller reading the flag could disagree with one
 * reading the cursor the first time the two were computed apart.
 *
 * @param entries       the page, in the order the query asked for
 * @param nextCursor    what to pass as {@code after} for the next page, or
 *                      null when this page is the last
 * @param pageSize      how many entries this page was allowed to carry
 * @param unaddressable how many entries of the scope this surface cannot show
 */
public record Listing(List<EntryView> entries, String nextCursor, int pageSize,
                      long unaddressable) {
}
