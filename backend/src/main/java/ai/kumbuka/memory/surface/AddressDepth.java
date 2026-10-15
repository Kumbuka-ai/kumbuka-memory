/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

/**
 * How much of an address a verb names, in the router's four stations
 * ({@code VerbCatalog.Depth}): {@code memory://scope/selector/id} stops at
 * each in turn.
 */
public enum AddressDepth {
    SCHEME,
    SCOPE,
    COLLECTION,
    ITEM
}
