/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

/**
 * The name of a call of this service, {@code memory_<verb>}, as a text may
 * name it.
 *
 * <p>One of the five types a placeholder of a message bundle may take
 * (code-conventions/service-construction.md). Built only by
 * {@link AssistantVerb} from its own declaration, so a name a request carried
 * can never become one: the constructor is not reachable from outside this
 * package, and nothing in this package builds one from a request.
 */
public final class VerbName {

    private final String name;

    VerbName(String name) {
        this.name = name;
    }

    /** The name as the text writes it. */
    @Override
    public String toString() {
        return name;
    }
}
