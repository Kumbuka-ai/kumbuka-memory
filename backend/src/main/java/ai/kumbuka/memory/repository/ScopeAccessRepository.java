package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The statements the platform read contract is reached through.
 *
 * <p>The relation is not this service's. {@code platform.scope_access} is a
 * view published by the platform, on which this service holds {@code SELECT}
 * and nothing else, and the directory above translates its answer into the
 * typed refusals a caller sees. What lives here is only the fact that the
 * access happens through JPA, which is what the persistence boundary is about:
 * the layer is defined by the mechanism, not by who owns the table.
 *
 * <h2>Native, and why every statement here is</h2>
 *
 * There is no entity for the view and there should not be one — an entity
 * would make it look like a table this service maps and could write. The two
 * session settings are {@code set_config} and {@code current_setting}, which
 * have no JPQL expression at all. This is the enumerated native case the rule
 * set provides for, and the reason is written here rather than assumed.
 */
@ApplicationScoped
@TenantBound
public class ScopeAccessRepository {

    /**
     * The seven columns, in the order the view publishes them (V24), and the
     * one statement they are read through.
     *
     * <p>The whole prefix rather than the column list alone: the three reads
     * below differ only in their predicate, and a shared prefix is what makes
     * that visible at a glance. A column added to the view reaches all three
     * by one edit here, which is the arrangement that kept this file to one
     * change when the contract grew from four columns to seven.
     */
    private static final String COLUMNS =
        "scope_id, tenant_id, slug, archived, kind, locked, can_write";

    private static final String SELECT_ACCESS =
        "SELECT " + COLUMNS + " FROM platform.scope_access";

    @Inject EntityManager em;

    /**
     * The access row for a slug, as the bound subject sees it, or empty.
     *
     * <p>Empty is returned rather than refused: whether "the subject may not
     * see it" is a refusal or an ordinary absence is the directory's
     * statement, and it needs the session check that precedes this call to
     * know which.
     */
    @Transactional
    public Optional<ScopeAccessRow> findBySlug(String slug) {
        return first(em.createNativeQuery(SELECT_ACCESS + " WHERE slug = :slug")
            .setParameter("slug", slug)
            .getResultList());
    }

    /**
     * The access row for a scope id, as the bound subject sees it, or empty.
     *
     * <p>Needed by the technical address, which names an entry and not a
     * scope: the row is found first and its scope is resolved afterwards, so
     * that the answer can carry the complete canonical address rather than the
     * uuid the caller handed in.
     */
    @Transactional
    public Optional<ScopeAccessRow> findById(UUID scopeId) {
        return first(em.createNativeQuery(SELECT_ACCESS + " WHERE scope_id = :id")
            .setParameter("id", scopeId)
            .getResultList());
    }

    /**
     * Every scope this subject may enter.
     *
     * <p>Read for the digest of a scope that includes the global one: the
     * global scope's id is not knowable from a slug the caller named, and
     * asking the contract is the only way to learn it that does not build a
     * second scope table here.
     */
    @Transactional
    public List<ScopeAccessRow> findByKind(String kind) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                SELECT_ACCESS + " WHERE kind = :kind ORDER BY slug")
            .setParameter("kind", kind)
            .getResultList();
        return rows.stream().map(ScopeAccessRepository::rowOf).toList();
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    @Transactional
    public void bindSubject(String subject) {
        em.createNativeQuery("SELECT set_config('app.subject', :v, true)")
            .setParameter("v", subject)
            .getSingleResult();
    }

    /** The bound tenant of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundTenant() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.tenant_id', true), '')").getSingleResult();
    }

    /** The bound subject of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundSubject() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.subject', true), '')").getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private static Optional<ScopeAccessRow> first(List<?> rows) {
        List<Object[]> typed = (List<Object[]>) rows;
        return typed.isEmpty() ? Optional.empty() : Optional.of(rowOf(typed.get(0)));
    }

    private static ScopeAccessRow rowOf(Object[] row) {
        return new ScopeAccessRow(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Boolean) row[3],
            (String) row[4],
            (Boolean) row[5],
            (Boolean) row[6]);
    }

    /**
     * One row of the read contract, as it comes off the view.
     *
     * <p>Distinct from the directory's own type on purpose. The two carry the
     * same seven values today; keeping them apart is what lets the published
     * shape of the view change without the type the domain reads changing with
     * it. That is not hypothetical: the view grew from four columns to seven
     * between two releases of the platform.
     *
     * <p>{@code kind} is {@code project}, {@code private} or {@code global} —
     * the view no longer filters to the first, so a service decides for itself
     * which kinds it serves; this one serves all three. {@code locked} is the
     * content lock, published beside {@code archived} because the two are
     * different refusals: archived is retired, locked is frozen.
     * {@code canWrite} is the calling subject's write right OVER A SERVICE
     * CHANNEL, which is the only channel this service is.
     */
    public record ScopeAccessRow(UUID scopeId, UUID tenantId, String slug, boolean archived,
                                 String kind, boolean locked, boolean canWrite) {
    }
}
