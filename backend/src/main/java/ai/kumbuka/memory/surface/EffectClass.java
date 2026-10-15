/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

/**
 * The platform's closed set of effect classes, as the declaration names a
 * verb's (ADR-0039).
 *
 * <p>The names are the router's, character for character
 * ({@code router/src/main/java/ai/kumbuka/router/verb/VerbCatalog.java},
 * {@code VerbCatalog.EffectClass}, {@code Kumbuka-ai/platform} at
 * {@code origin/main}, read 2026-10-15), so that the router reads a declared
 * class without a mapping of its own. The set is closed there: a class the
 * router does not know is a load error of the verb. It is held whole here,
 * including the classes this service declares no verb with, so that a later
 * verb is declared in the router's vocabulary and not in a new one.
 */
public enum EffectClass {
    ITEM_READ,
    COLLECTION_READ,
    SCHEME_READ,
    SCOPE_READ,
    SCOPE_COMPOUND,
    CREATE,
    FIELD_WRITE,
    GRAPH_ADD_OR_REMOVE,
    ITEM_TRANSITION,
    ITEM_READ_CALL,
    ITEM_DELETE,
    COLLECTION_COMPOUND
}
