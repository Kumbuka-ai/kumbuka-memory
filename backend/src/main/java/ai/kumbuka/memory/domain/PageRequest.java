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
 * Which page of a listing a caller asked for: where to continue, in which
 * order, and how many.
 *
 * <p>Apart from {@link QueryFilter} because it is a different question. The
 * filter says which entries the listing is made of; this says how the listing
 * is walked. The two are bound together only in the cursor, which carries the
 * filter's fingerprint and the order but not the size: a caller may read the
 * next page in another size, and the position is the same position.
 *
 * @param after     the cursor a previous page handed out, or null for the first
 * @param sort      the order the entries are read in
 * @param direction which way that order runs
 * @param pageSize  how many entries this page carries at most
 */
public record PageRequest(String after, SortKey sort, SortKey.Direction direction,
                          int pageSize) {

    /** The order a caller gets without asking: by key. */
    public static final SortKey DEFAULT_SORT = SortKey.ID;

    /** The direction a caller gets without asking: ascending. */
    public static final SortKey.Direction DEFAULT_DIRECTION = SortKey.Direction.ASC;

    /** The page size a caller gets without asking. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * The largest page this verb serves.
     *
     * <p>A limit and not a cap: a request for more is refused and named, never
     * quietly reduced. A silently reduced page is what makes a caller believe
     * it has seen everything.
     */
    public static final int MAX_PAGE_SIZE = 200;

    /** The argument a page-size refusal names. */
    public static final String PAGE_SIZE = "page_size";

    public PageRequest {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw pageSizeRejected();
        }
    }

    /**
     * The one refusal of a page size, whatever was wrong with it.
     *
     * <p>Out of range, not a number, not a whole one: each is
     * {@code PAGE_SIZE_REJECTED} (ADR-0049), and it names the argument rather
     * than the value. Built here so that every path that judges a page size —
     * the range above, the parse of a query parameter, the kind of an
     * assistant's argument — refuses it with the same reason and the same
     * offender, and none of them repeats the value sent (DEC-0042).
     */
    public static MemoryException pageSizeRejected() {
        return MemoryException.offending(MemoryException.Reason.PAGE_SIZE_REJECTED,
            "'page_size' is a whole number from 1 to " + MAX_PAGE_SIZE + ". The request is "
                + "refused rather than reduced to the limit: a page silently made smaller "
                + "than the one asked for is how a caller comes to believe it has seen "
                + "everything. Send a whole number from 1 to " + MAX_PAGE_SIZE + " and page "
                + "with the 'next_cursor' the answer carries.",
            List.of(PAGE_SIZE));
    }
}
