/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

/**
 * The permissions the methods of this service declare, in the platform's
 * catalogue spelling {@code service.object.act}.
 *
 * <p>Constants rather than an enum, because a declaration names its permission
 * in an annotation, and an annotation takes a constant. Five, one per kind of
 * act on an entry: reading (read, query, digest), laying one down, changing
 * one, withdrawing one, and tagging one.
 *
 * <p>What a permission is held by is not decided here. Which roles carry
 * which of these is the role mapping of the edition
 * ({@code platform.SystemRoles} in the open core), and whether a call holds
 * the permission its method declares is decided once, by the permission
 * check, before the method runs.
 */
public final class MemoryPermission {

    /** {@code read}, {@code query} and {@code digest}. */
    public static final String READ = "memory.entry.read";

    /** {@code create}. */
    public static final String CREATE = "memory.entry.create";

    /** {@code update}. */
    public static final String UPDATE = "memory.entry.update";

    /** {@code withdraw}. */
    public static final String WITHDRAW = "memory.entry.withdraw";

    /** {@code tag_attach} and {@code tag_detach}. */
    public static final String TAG = "memory.entry.tag";

    /**
     * What a declaration without a scope argument addresses, written after its
     * permission: the tenant. The one such method is the read by technical
     * address, whose scope is known only once its entry is loaded.
     */
    public static final String ON_THE_TENANT = ":tenant";

    private MemoryPermission() {
    }
}
