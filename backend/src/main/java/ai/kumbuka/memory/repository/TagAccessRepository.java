/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The statements the platform's tag vocabulary is read through.
 *
 * <p>The relation is not this service's. {@code platform.tag_access} is a view
 * the platform publishes, on which this service holds {@code SELECT} and
 * nothing else; the base table {@code platform.tag} it reads is out of this
 * service's reach. The view joins the tags to {@code platform.scope_access},
 * so it answers only tags of scopes the bound tenant and subject may see: the
 * session settings the directory binds when it resolves a scope are the ones
 * this view filters on, and every caller of this class has resolved a scope
 * in the same transaction first.
 *
 * <h2>Native, and why every statement here is</h2>
 *
 * For the reason {@link ScopeAccessRepository} gives: there is no entity for
 * the view and there should not be one, because an entity would make it look
 * like a table this service maps and could write.
 */
@ApplicationScoped
@TenantBound
public class TagAccessRepository {

    /** The four columns this service reads, in the order the contract publishes them. */
    private static final String SELECT_TAG =
        "SELECT tag_id, scope_id, token, status FROM platform.tag_access";

    @Inject EntityManager em;

    /**
     * The tag a token names in one scope, of any status, or nothing. The
     * contract holds a token unique per scope, so there is at most one.
     */
    @Transactional
    public List<TagAccessRow> findByToken(UUID scopeId, String token) {
        return rowsOf(em.createNativeQuery(SELECT_TAG
                + " WHERE scope_id = :scope AND token = :token")
            .setParameter("scope", scopeId)
            .setParameter("token", token)
            .getResultList());
    }

    /** The tags behind a set of ids, as far as this caller may see them. */
    @Transactional
    public List<TagAccessRow> findByIds(Collection<UUID> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return rowsOf(em.createNativeQuery(SELECT_TAG + " WHERE tag_id IN (:ids)")
            .setParameter("ids", tagIds)
            .getResultList());
    }

    /** The tags a set of tokens names across a set of scopes, of any status. */
    @Transactional
    public List<TagAccessRow> findByTokens(Collection<UUID> scopeIds, Collection<String> tokens) {
        if (scopeIds.isEmpty() || tokens.isEmpty()) {
            return List.of();
        }
        return rowsOf(em.createNativeQuery(SELECT_TAG
                + " WHERE scope_id IN (:scopes) AND token IN (:tokens)")
            .setParameter("scopes", scopeIds)
            .setParameter("tokens", tokens)
            .getResultList());
    }

    @SuppressWarnings("unchecked")
    private static List<TagAccessRow> rowsOf(List<?> rows) {
        return ((List<Object[]>) rows).stream()
            .map(row -> new TagAccessRow((UUID) row[0], (UUID) row[1], (String) row[2],
                (String) row[3]))
            .toList();
    }

    /**
     * One row of the vocabulary, as it comes off the view. Distinct from the
     * directory's own type for the reason {@link ScopeAccessRepository.ScopeAccessRow}
     * gives.
     *
     * @param status {@code declared} or {@code withdrawn}
     */
    public record TagAccessRow(UUID tagId, UUID scopeId, String token, String status) {
    }
}
