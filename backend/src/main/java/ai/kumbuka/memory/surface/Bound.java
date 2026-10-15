/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.PageRequest;

/**
 * A limit or a default the service declares, as a text may name it.
 *
 * <p>One of the five types a placeholder of a message bundle may take
 * (code-conventions/service-construction.md). The bounds are the constants
 * below and nothing else: a number a caller sent -- a page size, the length
 * of a content -- is never one.
 */
public final class Bound {

    /** The most characters an entry's content holds. */
    public static final Bound CONTENT_LIMIT = new Bound(MemoryService.CONTENT_LIMIT);

    /** The smallest page a listing serves. */
    public static final Bound PAGE_SIZE_MIN = new Bound(1);

    /** The largest page a listing serves. */
    public static final Bound PAGE_SIZE_MAX = new Bound(PageRequest.MAX_PAGE_SIZE);

    /** The page size of a listing that names none. */
    public static final Bound PAGE_SIZE_DEFAULT = new Bound(PageRequest.DEFAULT_PAGE_SIZE);

    private final int value;

    private Bound(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
