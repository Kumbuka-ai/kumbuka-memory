/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.domain.PageCursor;
import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.domain.SortKey;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every statement this service runs against its own two tables.
 *
 * <h2>What "in force" means, and why every statement says it</h2>
 *
 * The table is shaped for a history it does not yet keep: {@code is_head} and
 * {@code is_deleted} exist so that a later edition can append versions and
 * tombstone rows without a migration. In this edition an edit mutates the head
 * and a withdrawal deletes, so every row is a head and none is deleted — and
 * every statement here still carries both predicates. They are what the two
 * partial unique indexes are defined on, so a statement that omitted them
 * would read rows the uniqueness rule does not cover and would go wrong the
 * first time the edition changes rather than the first time it is run.
 *
 * <h2>The private predicate is here and not above it</h2>
 *
 * {@code is_private} is the scope's kind denormalised onto the row, and
 * {@code owner_subject} is the author. A private entry is its author's alone.
 * That filter is applied in the statement rather than after it, because a read
 * that fetched and then discarded would answer a page short of what it
 * promised.
 *
 * <h2>No Panache, and no native SQL</h2>
 *
 * The model is expressible in JPQL down to the aggregate the digest needs, so
 * nothing here carries the enumerated native exception. The one statement that
 * would have needed it — reading the digest's type selection out of a
 * {@code text[]} — lives in its own repository beside this one.
 */
@ApplicationScoped
@TenantBound
public class MemoryRepository {

    /**
     * The pieces every statement here is built from.
     *
     * <p>Named rather than repeated, and not only because repetition is
     * counted somewhere: {@code IN_FORCE} and {@code VISIBLE} are the two
     * predicates that must be on EVERY read, and a fragment spelled out at
     * each call site is one that can be left off at one of them without
     * anything looking wrong. The parameter names are constants for the same
     * reason — a binding and its placeholder that drift apart fail at run time
     * with a message about a parameter rather than about a query.
     */
    private static final String AND = " AND ";
    private static final String SCOPE = "scope";
    private static final String CALLER = "caller";

    /** The rows this edition considers to be in force. */
    private static final String IN_FORCE = "m.isHead = true AND m.isDeleted = false";

    /** The character that makes the next one in a {@code LIKE} pattern stand for itself. */
    static final char LIKE_ESCAPE = '!';

    /** A private row is its author's alone; a shared row is everyone's in the scope. */
    private static final String VISIBLE =
        "(m.isPrivate = false OR m.ownerSubject = :" + CALLER + ")";

    /** What every read of an addressable entry requires, in one place. */
    private static final String IN_FORCE_AND_VISIBLE = IN_FORCE + AND + VISIBLE;

    /** An entry whose key carries a selector, and can therefore be addressed. */
    private static final String ADDRESSABLE = "m.key IS NOT NULL AND LOCATE('.', m.key) > 1";

    /**
     * The two unique indexes of V1 that hold one entry in force per key: per
     * shared scope, and per private scope and author.
     */
    private static final Set<String> KEY_UNIQUENESS =
        Set.of("uq_memory_shared_key", "uq_memory_private_key");

    @Inject EntityManager em;

    /**
     * The entry in force at a key, as this caller sees it.
     *
     * <p>Answers for the canonical address. The uniqueness rule differs by
     * scope kind — one live head per key in a shared scope, one per key and
     * author in a private one — and the visibility predicate collapses the
     * second case to at most one row for this caller, so a single result is
     * correct for both without the statement having to know which it is in.
     */
    @Transactional
    public Optional<Memory> findInScope(UUID scopeId, String key, String caller) {
        return inScope(scopeId, key, caller, LockModeType.NONE);
    }

    /**
     * The entry in force at a key, as this caller sees it, locked for the
     * caller's transaction.
     *
     * <p>A write that depends on the entry as it stands — {@code update} and
     * {@code withdraw}, which compare a token before they write — reads it
     * here, so two such writes on one entry decide one after the other and
     * the second decides on what the first wrote (C10.2, C11.1). The entry
     * answered is the row as it stands under the lock: see {@link #underLock}.
     */
    @Transactional
    public Optional<Memory> lockInScope(UUID scopeId, String key, String caller) {
        return inScope(scopeId, key, caller, LockModeType.PESSIMISTIC_WRITE);
    }

    private Optional<Memory> inScope(UUID scopeId, String key, String caller,
                                     LockModeType mode) {
        Optional<Memory> found = only(em.createQuery(
                "SELECT m FROM Memory m WHERE m.scopeId = :" + SCOPE
                    + " AND m.key = :key" + AND + IN_FORCE_AND_VISIBLE, Memory.class)
            .setParameter(SCOPE, scopeId)
            .setParameter("key", key)
            .setParameter(CALLER, caller)
            .setLockMode(mode)
            .getResultList());
        return mode == LockModeType.NONE ? found : found.map(this::underLock);
    }

    /**
     * The entity as its row stands now that this transaction holds the lock.
     * Every locking read of this repository answers through here, which
     * {@code LockedReadRefreshTest} holds.
     *
     * <p>A locking query takes the lock on the current row, but where the
     * transaction already holds the entity — an earlier read without a lock
     * put it there — the persistence context answers that earlier object and
     * not what the query found, and the token compared would be the one read
     * before the lock. The refresh reads the locked row again, at the cost of
     * one more read per locked row.
     */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
    }

    /**
     * Whether any entry in force stands at a key in a scope, whoever wrote it.
     *
     * <p>Deliberately blind to the caller, and only ever asked by {@code create}.
     * In a shared scope the key is unique per scope, so a caller that could not
     * see a conflicting row would be told to go ahead and would then meet the
     * unique index as a constraint violation — a 500 where a speaking refusal
     * belongs. In a private scope the author is part of the uniqueness rule, so
     * the author is passed and the question narrows to that author's own rows.
     */
    @Transactional
    public Optional<Memory> findOccupant(UUID scopeId, String key, boolean isPrivate,
                                         String author) {
        String ownerClause = isPrivate ? AND + "m.ownerSubject = :owner" : "";
        var query = em.createQuery(
                "SELECT m FROM Memory m WHERE m.scopeId = :" + SCOPE
                    + " AND m.key = :key" + AND + IN_FORCE + ownerClause, Memory.class)
            .setParameter(SCOPE, scopeId)
            .setParameter("key", key);
        if (isPrivate) {
            query.setParameter("owner", author);
        }
        return only(query.getResultList());
    }

    /**
     * The entry in force under a logical id, as this caller sees it.
     *
     * <p>Answers for the technical address. No scope is named and none is
     * checked here: the row carries its scope and the caller of this method
     * resolves it afterwards, which is the order the contract prescribes.
     * Tenancy still binds — the policy and the ORM filter both apply — so a
     * foreign tenant's id finds nothing rather than finding a row to refuse.
     */
    @Transactional
    public Optional<Memory> findByLogicalId(UUID logicalId, String caller) {
        return only(em.createQuery(
                "SELECT m FROM Memory m WHERE m.logicalId = :id"
                    + AND + IN_FORCE_AND_VISIBLE, Memory.class)
            .setParameter("id", logicalId)
            .setParameter(CALLER, caller)
            .getResultList());
    }

    /**
     * Writes a new entry and returns it with its server-derived fields filled
     * in, or nothing where one of the two partial unique indexes refused it.
     *
     * <p>Nothing means another entry in force took the key between the
     * caller's occupant check and this insert: two creates on one key at
     * once, both past the check, the second waiting at the index until the
     * first commits. That is an occupied address and not a failure, and the
     * caller answers it as one. Any other violation stays what it is.
     *
     * <p>The transaction is lost either way: the database aborts it at the
     * refused statement, so the caller ends the call rather than going on.
     */
    @Transactional
    public Optional<Memory> insert(Memory entry) {
        em.persist(entry);
        // Flushed here rather than at commit so that the two partial unique
        // indexes speak while the call is still in the verb that can turn
        // their answer into a refusal. After the boundary the same violation
        // surfaces from the transaction manager, far from the caller and
        // outside the typed refusal model.
        try {
            em.flush();
        } catch (RuntimeException refused) {
            if (isOccupied(refused)) {
                return Optional.empty();
            }
            throw refused;
        }
        return Optional.of(entry);
    }

    /** Whether a failed write was refused by one of {@link #KEY_UNIQUENESS}. */
    private static boolean isOccupied(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && KEY_UNIQUENESS.contains(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** Writes the changed fields of an entry that is already attached. */
    @Transactional
    public Memory update(Memory entry) {
        Memory merged = em.merge(entry);
        em.flush();
        return merged;
    }

    /**
     * Removes an entry from the table.
     *
     * <p>Hard, and reachable only through the community edition's withdrawal.
     * The repository offers the mechanism; whether a withdrawal uses it is the
     * edition's decision and lives behind the seam in the domain.
     */
    @Transactional
    public void delete(Memory entry) {
        em.remove(em.contains(entry) ? entry : em.merge(entry));
        em.flush();
    }

    /**
     * One page of a scope, optionally narrowed to a selector, a type and a
     * substring of the content, in the order the request names.
     *
     * <p>The selector is matched on the generated {@code selector} column (V6),
     * by equality. It used to be a {@code LIKE} on the key with the selector
     * as prefix, and a selector may carry {@code _}, which {@code LIKE} reads
     * as "any one character": selector {@code a_b} listed the entries of
     * {@code a-b}. An equality has no characters of its own.
     *
     * <p>The text is still a {@code LIKE}, because a substring is what it
     * means, and the caller's text is escaped first so that {@code %} and
     * {@code _} in it stand for themselves.
     *
     * <p>Keyset, never offset: the page continues after the cursor's sort
     * values, so an entry laid down or withdrawn between two pages moves no
     * other entry from one page to the next. The key is the last sort key in
     * every order, which makes the order total and the continuation exact.
     *
     * <p>One row beyond the page is fetched rather than counted, so that
     * "there is more" is known without a second statement. The row is dropped
     * before the page is returned.
     */
    @Transactional
    public Rows page(Keyset request) {
        QueryFilter filter = request.filter();
        PageRequest asked = request.page();
        StringBuilder where = new StringBuilder(
            "m.scopeId = :" + SCOPE + AND + IN_FORCE_AND_VISIBLE + AND + ADDRESSABLE);
        Map<String, Object> bound = new java.util.LinkedHashMap<>();
        bound.put(SCOPE, request.scopeId());
        bound.put(CALLER, request.caller());

        if (request.selector() != null) {
            where.append(AND).append("m.selector = :selector");
            bound.put("selector", request.selector());
        }
        if (filter.type() != null) {
            where.append(AND).append("m.type = :type");
            bound.put("type", filter.type().wire());
        }
        if (filter.text() != null) {
            where.append(AND).append("LOWER(m.content) LIKE :text ESCAPE '")
                .append(LIKE_ESCAPE).append('\'');
            bound.put("text", "%" + likeLiteral(filter.text().toLowerCase(Locale.ROOT)) + "%");
        }
        if (request.after() != null) {
            where.append(AND).append(keysetAfter(asked.sort(), asked.direction()));
            bound.put("afterKey", request.after().key());
            if (asked.sort() == SortKey.CREATED_AT) {
                bound.put("afterAt", request.after().createdAt());
            }
        }

        var rows = em.createQuery("SELECT m FROM Memory m WHERE " + where
            + " ORDER BY " + orderBy(asked.sort(), asked.direction()), Memory.class);
        bound.forEach(rows::setParameter);
        List<Memory> found = new ArrayList<>(
            rows.setMaxResults(asked.pageSize() + 1).getResultList());

        boolean more = found.size() > asked.pageSize();
        if (more) {
            found.remove(found.size() - 1);
        }
        return new Rows(found, more, unaddressableIn(request.scopeId(), request.caller()));
    }

    /** The order of a listing, the key always last so that no two rows tie. */
    private static String orderBy(SortKey sort, SortKey.Direction direction) {
        String way = direction == SortKey.Direction.ASC ? " ASC" : " DESC";
        return switch (sort) {
            case ID -> "m.key" + way;
            case CREATED_AT -> "m.createdAt" + way + ", m.key" + way;
        };
    }

    /**
     * The rows strictly after the cursor's position, in the listing's order.
     *
     * <p>A row comparison and not the disjunction it is equivalent to. The
     * planner seeks an index to a row comparison over its trailing columns
     * and starts the walk at the cursor; the disjunction it can only apply as
     * a filter, so every page walked from the first row of the listing and
     * discarded everything before the cursor (measured in PageListingPlanIT).
     */
    private static String keysetAfter(SortKey sort, SortKey.Direction direction) {
        String beyond = direction == SortKey.Direction.ASC ? " > " : " < ";
        return switch (sort) {
            case ID -> "m.key" + beyond + ":afterKey";
            case CREATED_AT -> "(m.createdAt, m.key)" + beyond + "(:afterAt, :afterKey)";
        };
    }

    /**
     * A text made to stand for itself inside a {@code LIKE} pattern.
     *
     * <p>The escape character is doubled first, then the two wildcards are
     * escaped; in the other order the escape added before a wildcard would
     * itself be doubled.
     */
    static String likeLiteral(String text) {
        String escape = String.valueOf(LIKE_ESCAPE);
        return text.replace(escape, escape + escape)
            .replace("%", escape + "%")
            .replace("_", escape + "_");
    }

    /**
     * How many entries of a scope carry a key this surface cannot address.
     *
     * <p>Reported, never hidden. An entry whose key has no dot has no selector
     * and therefore no address, so it cannot appear in a listing that promises
     * every address it carries can be handed back to a read. Dropping it
     * silently would make a listing lie about its own completeness; counting it
     * says "there are this many here that this surface cannot show you", which
     * is a fact somebody can act on.
     *
     * <p>{@code create} refuses such a key, so this service never writes one.
     * A non-zero count is therefore a statement about data that arrived from
     * somewhere else.
     */
    @Transactional
    public long unaddressableIn(UUID scopeId, String caller) {
        return em.createQuery(
                "SELECT COUNT(m) FROM Memory m WHERE m.scopeId = :" + SCOPE
                    + AND + IN_FORCE_AND_VISIBLE
                    + AND + "(m.key IS NULL OR LOCATE('.', m.key) <= 1)", Long.class)
            .setParameter(SCOPE, scopeId)
            .setParameter(CALLER, caller)
            .getSingleResult();
    }

    /**
     * Every entry of the named types across the named scopes, in the digest's
     * order and with no limit whatever.
     *
     * <p>No {@code setMaxResults} and no page: the digest is the one read that
     * must not cut, and a limit here would be a cut that no caller could see.
     * The ordering is by type in the digest's order and, inside a type, by the
     * date the entry was laid down — oldest first, because a digest reads as a
     * record of how the estate came to be what it is.
     */
    @Transactional
    public List<Memory> loadForDigest(List<UUID> scopeIds, List<EntryType> types,
                                      String caller) {
        if (scopeIds.isEmpty() || types.isEmpty()) {
            return List.of();
        }
        return em.createQuery(
                "SELECT m FROM Memory m WHERE m.scopeId IN :scopes AND m.type IN :types"
                    + AND + IN_FORCE_AND_VISIBLE + AND + ADDRESSABLE
                    + " ORDER BY m.createdAt ASC, m.key ASC", Memory.class)
            .setParameter("scopes", scopeIds)
            .setParameter("types", types.stream().map(EntryType::wire).toList())
            .setParameter(CALLER, caller)
            .getResultList();
    }

    /**
     * Count and content length per type across the named scopes.
     *
     * <p>Answered for all six types whatever the selection is, which is what
     * lets a digest say "there are eleven open questions here that this digest
     * does not carry". The length is summed in the database rather than over
     * fetched rows, so the summary costs one statement and not the whole table.
     */
    @Transactional
    public Map<String, TypeTally> tallyByType(List<UUID> scopeIds, String caller) {
        if (scopeIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createQuery(
                "SELECT m.type, COUNT(m), COALESCE(SUM(LENGTH(m.content)), 0) "
                    + "FROM Memory m WHERE m.scopeId IN :scopes"
                    + AND + IN_FORCE_AND_VISIBLE + AND + ADDRESSABLE
                    + " GROUP BY m.type", Object[].class)
            .setParameter("scopes", scopeIds)
            .setParameter(CALLER, caller)
            .getResultList();

        Map<String, TypeTally> tallies = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            tallies.put((String) row[0],
                new TypeTally(((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return tallies;
    }

    private static Optional<Memory> only(List<Memory> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * What a page was asked for.
     *
     * <p>A record rather than a parameter list: the rule sheet's parameter
     * count is four, and a query that grows a predicate grows a field of the
     * filter rather than another argument at every call site.
     *
     * @param scopeId  the resolved scope
     * @param selector the selector, or null for the whole scope
     * @param caller   the subject whose private entries are visible
     * @param filter   type and text
     * @param page     order, direction and page size
     * @param after    the position to continue after, or null for the first page
     */
    public record Keyset(UUID scopeId, @Nullable String selector, String caller,
                         QueryFilter filter, PageRequest page, @Nullable PageCursor after) {
    }

    /** The rows of one page, whether more follows them, and what this surface cannot show. */
    public record Rows(List<Memory> entries, boolean more, long unaddressable) {
    }

    /** How many entries of one type there are, and how long they are together. */
    public record TypeTally(long count, long characters) {

        /** The empty tally, for a type with no entries. Never null. */
        public static final TypeTally NONE = new TypeTally(0, 0);
    }
}
