/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.domain.Withdrawal;
import ai.kumbuka.memory.repository.MemoryRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;

/**
 * A withdrawal that keeps a record, standing in for the one an enterprise
 * edition would supply.
 *
 * <p>The point of this class is what it does NOT require. It is an
 * {@code @Alternative} in another source tree, enabled through a test profile
 * — which is the same mechanism a module outside this repository would use —
 * and nothing in {@code kumbuka-memory} is edited to make it take effect.
 *
 * <p>It carries no {@code @Priority}, and the omission is load-bearing. A
 * {@code @Priority} on an {@code @Alternative} enables it application-wide,
 * with no profile and no declaration: written that way first, this class
 * silently replaced the community edition's withdrawal in EVERY suite, and
 * the probe that was meant to demonstrate a seam was demonstrating a global
 * override instead. Without it the bean is inert until a profile names it,
 * which is the property being asserted. The
 * verb, its check order, its refusals and the shape of its answer are the
 * ones the community edition has; only what happens to the row differs.
 *
 * <p>It is deliberately not a complete tombstone design. Whether a retired
 * entry keeps its address, what a later read of one answers, and how an audit
 * record is written are all the enterprise edition's to decide. What is
 * asserted here is only that the seam holds: the row survives the withdrawal,
 * the answer says so, and the caller is told what is actually open next
 * rather than what the community edition's outcome would have implied.
 */
@Alternative
@ApplicationScoped
@TenantBound
public class TombstoneWithdrawal implements Withdrawal {

    @Inject MemoryRepository entries;

    @Override
    @Transactional
    public Outcome withdraw(Memory entry) {
        entry.isDeleted = true;
        entry.changedAt(Instant.now());
        entries.update(entry);
        return Outcome.TOMBSTONED;
    }
}
