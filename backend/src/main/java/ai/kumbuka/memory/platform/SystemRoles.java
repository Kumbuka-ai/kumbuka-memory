/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.platform;

import ai.kumbuka.memory.domain.MemoryPermission;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The community edition's mapping from the platform's system roles to this
 * service's permissions: code, not configuration.
 *
 * <p>The community edition knows the fixed system roles and nothing else: no
 * role of a tenant's own, no role per scope, no personal role. A role it maps
 * grants its permissions tenant-wide; which scopes a call may address is the
 * other half of the facts, read from the platform's scope view. Every role this
 * mapping does not name grants nothing here -- the roles of the other services
 * and the administration role among them -- so a token that carries them
 * reaches no permission of this service through them.
 *
 * <p>The table is the platform's permission catalogue, and a test holds it
 * against the catalogue's own table ({@code PermissionCatalogueTest}). A
 * change here is a change of the catalogue first.
 */
public final class SystemRoles {

    private static final Map<String, Set<String>> PERMISSIONS = mapping();

    private SystemRoles() {
    }

    private static Map<String, Set<String>> mapping() {
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        roles.put("owner", Set.of(MemoryPermission.READ, MemoryPermission.CREATE,
            MemoryPermission.UPDATE, MemoryPermission.WITHDRAW, MemoryPermission.TAG));
        roles.put("memory-reader", Set.of(MemoryPermission.READ));
        roles.put("memory-author", Set.of(MemoryPermission.CREATE, MemoryPermission.UPDATE,
            MemoryPermission.TAG));
        roles.put("memory-curator", Set.of(MemoryPermission.WITHDRAW));
        return Map.copyOf(roles);
    }

    /** The roles this mapping grants a permission for. */
    public static Set<String> mapped() {
        return PERMISSIONS.keySet();
    }

    /**
     * The permissions a set of roles grants: the union over the roles this
     * mapping names. A role it does not name contributes nothing.
     */
    public static Set<String> permissionsOf(Collection<String> roles) {
        Set<String> granted = new TreeSet<>();
        for (String role : roles) {
            granted.addAll(PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return Set.copyOf(granted);
    }
}
