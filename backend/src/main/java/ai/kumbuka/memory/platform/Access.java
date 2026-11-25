/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.platform;

/**
 * What a call is about to do in a scope.
 *
 * <p>Two values and not a boolean. The directory refuses a write on the
 * scope's own state, the lock, and refuses a read for no reason of the scope,
 * so the parameter decides which checks run at all — a boolean flag argument
 * at that call site would be a value whose meaning the reader has to look up,
 * and the rule sheet names boolean flag arguments as the thing to avoid.
 * Whether the caller may read or write at all is not among them: that is the
 * permission its method declares, decided before the directory is asked.
 */
public enum Access {
    /** Reading: the scope is resolved and nothing about its state refuses. */
    READ,
    /** Writing: {@code create}, {@code update}, {@code withdraw}, the tag verbs; a lock refuses. */
    WRITE
}
