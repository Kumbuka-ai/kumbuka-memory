package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * promised and would report a total that counts rows the caller may not see.
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

    /** The rows this edition considers to be in force. */
    private static final String IN_FORCE = "m.isHead = true AND m.isDeleted = false";

    /** A private row is its author's alone; a shared row is everyone's in the scope. */
    private static final String VISIBLE =
        "(m.isPrivate = false OR m.ownerSubject = :caller)";

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
        return only(em.createQuery(
                "SELECT m FROM Memory m WHERE m.scopeId = :scope AND m.key = :key "
                    + "AND " + IN_FORCE + " AND " + VISIBLE, Memory.class)
            .setParameter("scope", scopeId)
            .setParameter("key", key)
            .setParameter("caller", caller)
            .getResultList());
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
        String ownerClause = isPrivate ? " AND m.ownerSubject = :owner" : "";
        var query = em.createQuery(
                "SELECT m FROM Memory m WHERE m.scopeId = :scope AND m.key = :key "
                    + "AND " + IN_FORCE + ownerClause, Memory.class)
            .setParameter("scope", scopeId)
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
                "SELECT m FROM Memory m WHERE m.logicalId = :id "
                    + "AND " + IN_FORCE + " AND " + VISIBLE, Memory.class)
            .setParameter("id", logicalId)
            .setParameter("caller", caller)
            .getResultList());
    }

    /** Writes a new entry and returns it with its server-derived fields filled in. */
    @Transactional
    public Memory insert(Memory entry) {
        em.persist(entry);
        // Flushed here rather than at commit so that the two partial unique
        // indexes speak while the call is still in the verb that can turn
        // their answer into a refusal. After the boundary the same violation
        // surfaces from the transaction manager, far from the caller and
        // outside the typed refusal model.
        em.flush();
        return entry;
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
     * substring of the content.
     *
     * <p>The selector is matched as a key prefix — {@code selector.} — because
     * the selector is not stored: it is the part of the key before the first
     * dot, and a prefix match on the key with the dot included is exactly that
     * and cannot also match a longer selector beginning with the same letters.
     *
     * <p>One row beyond the page is fetched rather than counted, so that "there
     * is more" is known without a second statement. The row is dropped before
     * the page is returned.
     */
    @Transactional
    public Page page(PageRequest request) {
        StringBuilder where = new StringBuilder(
            "m.scopeId = :scope AND " + IN_FORCE + " AND " + VISIBLE
                + " AND m.key IS NOT NULL AND LOCATE('.', m.key) > 1");
        Map<String, Object> bound = new java.util.LinkedHashMap<>();
        bound.put("scope", request.scopeId());
        bound.put("caller", request.caller());

        if (request.selector() != null) {
            where.append(" AND m.key LIKE :prefix");
            bound.put("prefix", request.selector() + ".%");
        }
        if (request.type() != null) {
            where.append(" AND m.type = :type");
            bound.put("type", request.type().wire());
        }
        if (request.text() != null) {
            where.append(" AND LOWER(m.content) LIKE :text");
            bound.put("text", "%" + request.text().toLowerCase(Locale.ROOT) + "%");
        }
        if (request.after() != null) {
            where.append(" AND m.key > :after");
            bound.put("after", request.after());
        }

        var rows = em.createQuery(
            "SELECT m FROM Memory m WHERE " + where + " ORDER BY m.key ASC", Memory.class);
        bound.forEach(rows::setParameter);
        List<Memory> found = new ArrayList<>(
            rows.setMaxResults(request.pageSize() + 1).getResultList());

        boolean more = found.size() > request.pageSize();
        if (more) {
            found.remove(found.size() - 1);
        }

        // The total is the whole matching set, cursor clause excluded: a total
        // that shrank as the caller paged would be a different number every
        // page and could not be what "how many are there" means.
        StringBuilder countWhere = new StringBuilder(where);
        int cursorClause = countWhere.indexOf(" AND m.key > :after");
        if (cursorClause >= 0) {
            countWhere.delete(cursorClause, countWhere.length());
        }
        var counted = em.createQuery(
            "SELECT COUNT(m) FROM Memory m WHERE " + countWhere, Long.class);
        bound.forEach((name, value) -> {
            if (!"after".equals(name)) {
                counted.setParameter(name, value);
            }
        });

        return new Page(found, counted.getSingleResult(), more,
            unaddressableIn(request.scopeId(), request.caller()));
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
                "SELECT COUNT(m) FROM Memory m WHERE m.scopeId = :scope AND " + IN_FORCE
                    + " AND " + VISIBLE
                    + " AND (m.key IS NULL OR LOCATE('.', m.key) <= 1)", Long.class)
            .setParameter("scope", scopeId)
            .setParameter("caller", caller)
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
                "SELECT m FROM Memory m WHERE m.scopeId IN :scopes AND m.type IN :types "
                    + "AND " + IN_FORCE + " AND " + VISIBLE
                    + " AND m.key IS NOT NULL AND LOCATE('.', m.key) > 1 "
                    + "ORDER BY m.createdAt ASC, m.key ASC", Memory.class)
            .setParameter("scopes", scopeIds)
            .setParameter("types", types.stream().map(EntryType::wire).toList())
            .setParameter("caller", caller)
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
                    + "FROM Memory m WHERE m.scopeId IN :scopes AND " + IN_FORCE
                    + " AND " + VISIBLE
                    + " AND m.key IS NOT NULL AND LOCATE('.', m.key) > 1 "
                    + "GROUP BY m.type", Object[].class)
            .setParameter("scopes", scopeIds)
            .setParameter("caller", caller)
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
     * <p>A record rather than seven parameters: the rule sheet's parameter
     * count is four, and a query that grows a predicate should grow a field
     * here rather than another argument at every call site.
     */
    public record PageRequest(UUID scopeId, String selector, EntryType type, String text,
                              String after, int pageSize, String caller) {
    }

    /** One page, with everything a caller needs to know it is not the whole set. */
    public record Page(List<Memory> entries, long total, boolean more, long unaddressable) {
    }

    /** How many entries of one type there are, and how long they are together. */
    public record TypeTally(long count, long characters) {

        /** The empty tally, for a type with no entries. Never null. */
        public static final TypeTally NONE = new TypeTally(0, 0);
    }
}
