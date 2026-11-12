/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.EntryTag;
import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The statements this service runs against {@code memory.entry_tag} (V11) on
 * their own: the assignments of given entries read, written and removed.
 *
 * <p>Not every read of the table is here. A page and a digest narrowed by
 * tags read it inside their own statement, through the fragment
 * {@code CARRIES_ONE_OF} of {@link MemoryRepository}, which {@code page},
 * {@code loadForDigest} and {@code tallyByType} there append.
 *
 * <p>JPQL throughout. The tokens behind the ids are the platform's and are
 * read through {@link TagAccessRepository}; nothing here joins across the
 * schema boundary.
 *
 * <p>An entry is always named by its durable key, the pair
 * {@code (logical_id, version)}, which is what the foreign key of V11 holds.
 */
@ApplicationScoped
@TenantBound
public class EntryTagRepository {

    private static final String ENTRY = "entry";
    private static final String VERSION = "version";
    private static final String TAG = "tag";

    /** The rows of one entry. */
    private static final String OF_ENTRY =
        "t.entryLogicalId = :" + ENTRY + " AND t.entryVersion = :" + VERSION;

    @Inject EntityManager em;

    /** The ids of the tags an entry carries, in no particular order. */
    @Transactional
    public List<UUID> tagIdsOf(Memory entry) {
        return em.createQuery("SELECT t.tagId FROM EntryTag t WHERE " + OF_ENTRY, UUID.class)
            .setParameter(ENTRY, entry.logicalId)
            .setParameter(VERSION, entry.version)
            .getResultList();
    }

    /**
     * The ids of the tags each of several entries carries, by logical id. An
     * entry that carries none is absent from the map. One statement for a
     * whole page, rather than one per entry.
     */
    @Transactional
    public Map<UUID, List<UUID>> tagIdsOf(Collection<Memory> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> versions = new LinkedHashMap<>();
        entries.forEach(entry -> versions.put(entry.logicalId, entry.version));
        List<Object[]> rows = em.createQuery(
                "SELECT t.entryLogicalId, t.entryVersion, t.tagId FROM EntryTag t "
                    + "WHERE t.entryLogicalId IN :ids", Object[].class)
            .setParameter("ids", versions.keySet())
            .getResultList();
        // The rows of the listed version only: the pair is the entry's key.
        Map<UUID, List<UUID>> byEntry = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID entry = (UUID) row[0];
            if (Integer.valueOf(((Number) row[1]).intValue()).equals(versions.get(entry))) {
                byEntry.computeIfAbsent(entry, id -> new ArrayList<>()).add((UUID) row[2]);
            }
        }
        return byEntry;
    }

    /** Whether an entry carries a tag. */
    @Transactional
    public boolean carries(Memory entry, UUID tagId) {
        return !em.createQuery("SELECT t.tagId FROM EntryTag t WHERE " + OF_ENTRY
                + " AND t.tagId = :" + TAG, UUID.class)
            .setParameter(ENTRY, entry.logicalId)
            .setParameter(VERSION, entry.version)
            .setParameter(TAG, tagId)
            .getResultList().isEmpty();
    }

    /** Writes an assignment, flushed here so that a refusal of the database speaks in the verb. */
    @Transactional
    public void insert(EntryTag assignment) {
        em.persist(assignment);
        em.flush();
    }

    /** Removes an assignment; how many rows went, which is zero or one. */
    @Transactional
    public int delete(Memory entry, UUID tagId) {
        return em.createQuery("DELETE FROM EntryTag t WHERE " + OF_ENTRY
                + " AND t.tagId = :" + TAG)
            .setParameter(ENTRY, entry.logicalId)
            .setParameter(VERSION, entry.version)
            .setParameter(TAG, tagId)
            .executeUpdate();
    }
}
