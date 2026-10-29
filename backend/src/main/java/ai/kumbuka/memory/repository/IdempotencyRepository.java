/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.SpentKey;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The ledger of spent idempotency keys, {@code memory.idempotency_key} (V10).
 *
 * <p>Three statements: the lock of one caller's key in a scope, the row that
 * key holds, and a new row. No delete: an expired row is overwritten in
 * place, which is all the grant of V10 allows.
 */
@ApplicationScoped
@TenantBound
public class IdempotencyRepository {

    private static final String SCOPE = "scope";
    private static final String CALLER = "caller";
    private static final String KEY = "key";

    @Inject EntityManager em;

    /**
     * Takes the lock of one caller's key in a scope, held until the caller's
     * transaction ends, and waits while another transaction holds it.
     *
     * <p>A transaction-bound advisory lock of Postgres over the three values
     * the unique key of {@code idempotency_key} carries. The row lock of
     * {@link #lockKey} cannot serialise the first two calls under a new key:
     * neither finds a row to lock, and each would lay down an entry. Under
     * this lock the second reads the key only once the first has committed,
     * and so reads the first's row as a repeat does.
     *
     * <p>The lock is taken over the three parts, each written with its length
     * in front ({@link #keyLockOf}), so two triples that differ in any part
     * never name one lock, whatever characters a key or a subject carries.
     * Two different keys whose texts hash alike wait on each other and decide
     * nothing for each other.
     */
    @Transactional
    public void lockKeyUse(UUID scopeId, String callerSubject, String key) {
        em.createNativeQuery("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(
                    CAST(:lock AS text), 0))) AS held
                """)
            .setParameter("lock", keyLockOf(scopeId, callerSubject, key))
            .getSingleResult();
    }

    /**
     * The text the lock of one caller's key in a scope is taken over: each
     * part as its length, a colon and the part.
     */
    static String keyLockOf(UUID scopeId, String callerSubject, String key) {
        StringBuilder text = new StringBuilder();
        for (String part : new String[] {scopeId.toString(), callerSubject, key}) {
            text.append(part.length()).append(':').append(part);
        }
        return text.toString();
    }

    /**
     * The row a caller's key holds in a scope, locked for update and as it
     * stands under the lock.
     */
    @Transactional
    public Optional<SpentKey> lockKey(UUID scopeId, String callerSubject, String key) {
        return em.createQuery("""
                SELECT k FROM SpentKey k
                WHERE k.scopeId = :scope AND k.callerSubject = :caller AND k.key = :key
                """, SpentKey.class)
            .setParameter(SCOPE, scopeId)
            .setParameter(CALLER, callerSubject)
            .setParameter(KEY, key)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /** Remembers a key spent for the first time. */
    @Transactional
    public SpentKey insert(SpentKey spent) {
        em.persist(spent);
        em.flush();
        return spent;
    }

    /**
     * The entity as its row stands now that this transaction holds the lock:
     * where the persistence context already held it, the query answers that
     * earlier object, and the refresh reads the locked row again.
     */
    private SpentKey underLock(SpentKey spent) {
        em.refresh(spent);
        return spent;
    }
}
