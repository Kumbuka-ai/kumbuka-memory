/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.platform.Access;
import ai.kumbuka.memory.platform.ScopeDirectory;
import ai.kumbuka.memory.repository.DigestPreferenceRepository;
import ai.kumbuka.memory.repository.MemoryRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The digest: everything in force in a scope, whole.
 *
 * <h2>Why this verb has a service of its own</h2>
 *
 * It answers a different question from the other five. They act on one entry
 * at an address; this one assembles a reading of a whole scope, against a
 * selection that is estate state rather than a value of any call, across more
 * than one scope where the estate says so. Folding it into the entry verbs
 * would put three concerns that change for three different reasons behind one
 * class.
 *
 * <h2>It does not cut, and that is the whole contract</h2>
 *
 * The core this replaces caps each type at twenty entries and sorts by change
 * time descending, so an estate that passed twenty decisions silently stopped
 * seeing its oldest ones — and nothing in the answer said so. There is no
 * limit here, in the statement or around it, and the answer carries the size
 * so that a caller can decide what it costs rather than being decided for.
 */
@ApplicationScoped
@TenantBound
public class DigestService {

    /** The argument the chosen types travel in, and the one their refusals name. */
    private static final String TYPES = "types";

    @Inject ScopeDirectory scopes;
    @Inject MemoryRepository entries;
    @Inject DigestPreferenceRepository preferences;

    /**
     * The digest of a scope.
     *
     * @param actor     who is asking; private entries of others are not here
     * @param scopeSlug the scope to read
     * @param override  the types to carry content for, or null to use the
     *                  estate's selection
     */
    @Transactional
    public DigestView digest(Actor actor, String scopeSlug, @Nullable List<String> override) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), scopeSlug, Access.READ);

        DigestPreferenceRepository.Selection selection = preferences.forScope(scope.scopeId())
            .orElseThrow(DigestService::selectionMissing);

        List<EntryType> carried = override == null
            ? knownTypesOf(selection.types())
            : chosen(override);

        List<ScopeAndSlug> covered = coveredScopes(actor, scope, selection.includeGlobal());
        List<UUID> scopeIds = covered.stream().map(ScopeAndSlug::id).toList();

        // Read once and passed on. The summary, the entry count and the token
        // total are three readings of one aggregate; three statements would be
        // three chances for them to disagree with each other inside one answer.
        Map<String, MemoryRepository.TypeTally> tallies =
            entries.tallyByType(scopeIds, actor.subject());

        return new DigestView(
            scope.slug(),
            covered.stream().map(ScopeAndSlug::slug).toList(),
            carried.stream().map(EntryType::wire).toList(),
            summaryOf(tallies, carried),
            tallies.values().stream().mapToLong(MemoryRepository.TypeTally::count).sum(),
            DigestView.tokensFor(tallies.values().stream()
                .mapToLong(MemoryRepository.TypeTally::characters).sum()),
            sectionsOf(scopeIds, carried, covered, actor),
            unaddressableIn(covered, actor));
    }

    // ------------------------------------------------------------------
    // The scopes a digest covers
    // ------------------------------------------------------------------

    /**
     * The named scope, and the tenant's global scope where the estate says so.
     *
     * <p>A global scope is not added to a digest OF the global scope: the
     * result would carry every entry twice, and deduplicating afterwards would
     * be repairing something that need not happen.
     */
    private List<ScopeAndSlug> coveredScopes(Actor actor, ScopeDirectory.ScopeAccess scope,
                                             boolean includeGlobal) {
        List<ScopeAndSlug> covered = new ArrayList<>();
        covered.add(new ScopeAndSlug(scope.scopeId(), scope.slug()));

        if (includeGlobal && !ScopeDirectory.KIND_GLOBAL.equals(scope.kind())) {
            scopes.globalScopes(actor.subject()).stream()
                .filter(global -> !global.scopeId().equals(scope.scopeId()))
                .forEach(global -> covered.add(
                    new ScopeAndSlug(global.scopeId(), global.slug())));
        }
        return covered;
    }

    // ------------------------------------------------------------------
    // The summary: all six, always
    // ------------------------------------------------------------------

    private static List<DigestView.TypeTally> summaryOf(
            Map<String, MemoryRepository.TypeTally> tallies, List<EntryType> carried) {
        List<DigestView.TypeTally> summary = new ArrayList<>();
        for (EntryType type : EntryType.inDigestOrder()) {
            MemoryRepository.TypeTally tally = tallies.getOrDefault(type.wire(),
                MemoryRepository.TypeTally.NONE);
            summary.add(new DigestView.TypeTally(type.wire(), tally.count(),
                DigestView.tokensFor(tally.characters()), carried.contains(type)));
        }
        return summary;
    }

    private long unaddressableIn(List<ScopeAndSlug> covered, Actor actor) {
        return covered.stream()
            .mapToLong(scope -> entries.unaddressableIn(scope.id(), actor.subject()))
            .sum();
    }

    // ------------------------------------------------------------------
    // The content
    // ------------------------------------------------------------------

    /**
     * The sections, in the digest's order, each loaded whole.
     *
     * <p>Grouped in memory from one ordered statement rather than fetched per
     * type. The statement orders by the date an entry was laid down, and
     * grouping preserves that inside each type, so the two orderings the
     * contract asks for come out of one read.
     */
    private List<DigestView.Section> sectionsOf(List<UUID> scopeIds, List<EntryType> carried,
                                                List<ScopeAndSlug> covered, Actor actor) {
        Map<UUID, String> slugs = new LinkedHashMap<>();
        covered.forEach(scope -> slugs.put(scope.id(), scope.slug()));

        Map<String, List<DigestView.Entry>> byType = new LinkedHashMap<>();
        carried.forEach(type -> byType.put(type.wire(), new ArrayList<>()));

        for (Memory entry : entries.loadForDigest(scopeIds, carried, actor.subject())) {
            loaded(byType, entry.type).add(new DigestView.Entry(
                EntryAddress.ofKey(loaded(slugs, entry.scopeId), entry.key),
                entry.key,
                entry.content));
        }

        return EntryType.inDigestOrder().stream()
            .filter(carried::contains)
            .map(type -> new DigestView.Section(type.wire(), loaded(byType, type.wire())))
            .toList();
    }

    /**
     * The value the statement's own bounds guarantee: it reads only the carried
     * types in the covered scopes, and both maps are keyed by exactly those.
     */
    private static <K, V> V loaded(Map<K, V> map, K key) {
        V value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("the digest statement read outside its bounds");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // The selection
    // ------------------------------------------------------------------

    /**
     * The types a caller asked for, refused by name where one is not a type.
     *
     * <p>Refused and not filtered. A caller that misspelled a type and got a
     * digest without it would read an empty section as "there are none", which
     * is a different and wrong answer.
     */
    private static List<EntryType> chosen(List<String> asked) {
        if (asked.isEmpty()) {
            throw MemoryException.offending(MemoryException.Reason.TYPE_UNKNOWN, List.of(TYPES),
                EntryType.wireNames());
        }
        // Named by position, as the argument's path, and never by the value
        // sent (DEC-0043 R19): which of several values was wrong is what the
        // caller needs, and the value is its own input.
        List<String> unknown = new java.util.ArrayList<>();
        for (int at = 0; at < asked.size(); at++) {
            String type = asked.get(at);
            if (type == null || EntryType.of(type).isEmpty()) {
                unknown.add(TYPES + "[" + at + "]");
            }
        }
        if (!unknown.isEmpty()) {
            throw MemoryException.offending(MemoryException.Reason.TYPE_UNKNOWN, unknown,
                EntryType.wireNames());
        }
        return asked.stream().map(t -> EntryType.of(t).orElseThrow()).toList();
    }

    /**
     * The stored selection, as types.
     *
     * <p>Unknown names in the stored row are a defect of the estate's own
     * configuration rather than of a call, and the check constraint on the
     * table makes one unreachable through ordinary means — so this maps what
     * it recognises and would leave a stranger out. It cannot: the constraint
     * is checked, so the mapping is total, and the assertion says so rather
     * than leaving a silent filter behind.
     */
    private static List<EntryType> knownTypesOf(List<String> stored) {
        List<String> unknown = stored.stream().filter(t -> EntryType.of(t).isEmpty()).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                "memory.digest_preference.types carries " + unknown + ", which is not a "
                    + "kind of entry this service knows. ck_digest_preference_types_known "
                    + "should have refused the row, so either the constraint or this "
                    + "vocabulary has moved without the other.");
        }
        return stored.stream().map(t -> EntryType.of(t).orElseThrow()).toList();
    }

    private static IllegalStateException selectionMissing() {
        return new IllegalStateException(
            "no digest selection is stored, not even the estate's default. V4 seeds that "
                + "row and verifies its own seed, so reaching this means the row was "
                + "removed afterwards or the migration's verification was bypassed.");
    }

    /** A covered scope and the name a digest entry's address carries for it. */
    private record ScopeAndSlug(UUID id, String slug) {
    }
}
