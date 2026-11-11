/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.platform;

import ai.kumbuka.memory.repository.TagAccessRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves tags against the platform's published vocabulary.
 *
 * <p>The vocabulary belongs to the platform: a tag is declared there, per
 * scope, and withdrawn there. This service declares none and holds no copy;
 * it reads {@code platform.tag_access} and nothing else, the way
 * {@link ScopeDirectory} reads {@code platform.scope_access}.
 *
 * <h2>Called after the scope, never before</h2>
 *
 * The view filters on the same two session settings the scope directory
 * binds, so every method here is reached in a transaction whose scope was
 * resolved first. That is the check order anyway: a token is the scope's
 * vocabulary, and the vocabulary is consulted once the scope is known to be
 * visible (C1.3).
 */
@ApplicationScoped
@TenantBound
public class TagDirectory {

    /** The status of a tag that takes no new assignment, as the platform spells it. */
    public static final String STATUS_RETIRED = "withdrawn";

    @Inject TagAccessRepository tags;

    /** The tag a token names in one scope, of any status, or empty. */
    @Transactional
    public Optional<Tag> inScope(UUID scopeId, String token) {
        return tags.findByToken(scopeId, token).stream().findFirst().map(TagDirectory::tagOf);
    }

    /**
     * The token behind each id, as far as this caller may see it. An id whose
     * tag is not visible is absent from the map.
     */
    @Transactional
    public Map<UUID, String> tokensOf(Collection<UUID> tagIds) {
        Map<UUID, String> tokens = new LinkedHashMap<>();
        tags.findByIds(tagIds).forEach(row -> tokens.put(row.tagId(), row.token()));
        return tokens;
    }

    /**
     * The ids a set of tokens names across a set of scopes, of any status.
     * A token no scope declares names nothing and is no error.
     */
    @Transactional
    public List<UUID> idsOf(Collection<UUID> scopeIds, Collection<String> tokens) {
        return tags.findByTokens(scopeIds, tokens).stream()
            .map(TagAccessRepository.TagAccessRow::tagId)
            .distinct()
            .toList();
    }

    private static Tag tagOf(TagAccessRepository.TagAccessRow row) {
        return new Tag(row.tagId(), row.token(), row.status());
    }

    /**
     * One tag of a scope's vocabulary.
     *
     * @param status {@code declared}, or {@link #STATUS_RETIRED}
     */
    public record Tag(UUID id, String token, String status) {

        /** Whether the platform has withdrawn the tag: it takes no new assignment. */
        public boolean isRetired() {
            return STATUS_RETIRED.equals(status);
        }
    }
}
