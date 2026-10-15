/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

/**
 * The position of a value inside a call, as the argument's path:
 * {@code types[2]}, {@code fields.content}.
 *
 * <p>One of the five types a placeholder of a message bundle may take
 * (code-conventions/service-construction.md). Where a caller needs to know
 * which of several values was wrong, a text names the position and never the
 * value (DEC-0043 R19). A path is made of declared names and of positions the
 * service counted itself, so nothing the caller wrote can stand in one.
 */
public final class ArgumentPath {

    private final String path;

    private ArgumentPath(String path) {
        this.path = path;
    }

    /** The argument itself. */
    public static ArgumentPath of(ArgumentName argument) {
        return new ArgumentPath(argument.value());
    }

    /** One element of a list argument, by its position from zero. */
    public static ArgumentPath at(ArgumentName argument, int position) {
        return new ArgumentPath(argument.value() + "[" + position + "]");
    }

    /** The path as a text and as {@code offenders} write it. */
    public String value() {
        return path;
    }

    @Override
    public String toString() {
        return path;
    }
}
