/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

/**
 * The name of a declared argument, as a text may name it.
 *
 * <p>One of the five types a placeholder of a message bundle may take
 * (code-conventions/service-construction.md). Built only where an argument is
 * declared -- {@link Argument} and {@link AssistantVerb} -- and handed out by
 * the declaration, so a name that arrived undeclared can never become one.
 */
public final class ArgumentName {

    private final String name;

    ArgumentName(String name) {
        this.name = name;
    }

    /** The name as the caller writes it, which is also how a text writes it. */
    public String value() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArgumentName that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
