/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryPermission;
import ai.kumbuka.security.contract.CallFacts;

import java.util.Optional;
import java.util.Set;

/**
 * The code a call refused by the permission check is answered with.
 *
 * <p>The check has decided already, before the method ran, and it decided
 * no: the call does not hold the permission the method declares, or it may
 * address no scope at all. What is left is which of the service's codes says
 * so, and that is chosen here, once, for both adapters, from the same facts the
 * check read. Nothing here admits a call or refuses one that was admitted.
 *
 * <ul>
 *   <li>A call with no facts, with no permission of this service, or with no
 *       scope it may address sees no scope of this service, and a call that
 *       names one is answered {@code NOT_FOUND} (contract C8.7, the status
 *       classes).</li>
 *   <li>A call whose one permission here is to read, refused a write, is told
 *       the one permission it holds in the scope is memory.entry.read:
 *       {@code SCOPE_READ_ONLY}.</li>
 *   <li>Every other call holds some permission of this service and not the one
 *       the method declares: {@code ROLE_INSUFFICIENT}.</li>
 * </ul>
 *
 * <p>A scope outside the call's set is no case of this class: the check answers
 * it as a scope that does not exist, and writes its reason to the log and
 * nowhere else.
 */
public final class PermissionRefusal {

    private PermissionRefusal() {
        // One mapping and nothing to instantiate.
    }

    /** The refusal of a call the permission check refused, with the facts it read. */
    public static MemoryException of(Optional<CallFacts> facts) {
        if (facts.isEmpty() || facts.get().permissions().isEmpty()
                || facts.get().scopes().isEmpty()) {
            return new MemoryException(MemoryException.Reason.SCOPE_UNRESOLVED);
        }
        Set<String> held = facts.get().permissions();
        return held.equals(Set.of(MemoryPermission.READ))
            ? new MemoryException(MemoryException.Reason.SCOPE_READ_ONLY)
            : new MemoryException(MemoryException.Reason.ROLE_INSUFFICIENT);
    }
}
