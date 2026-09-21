package ai.kumbuka.memory.platform;

import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.repository.ScopeAccessRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a scope against the platform's published read contract.
 *
 * <p>This service holds no scope table of its own and never reads the
 * platform's base tables. It holds {@code SELECT} on exactly one view and
 * nothing else, and the view answers one question — may this subject enter
 * this scope — without publishing the membership that produces the answer.
 * Existence in the result IS the permission.
 *
 * <h2>The session contract</h2>
 *
 * Two settings, both bound <strong>transaction-locally</strong>:
 * {@code app.tenant_id} and {@code app.subject}. Transaction-local is not a
 * detail. A session-wide {@code SET} survives the connection's return to the
 * pool, so the next caller on that connection inherits the previous caller's
 * subject — a leak that appears under load, on a warm pool, and never in a
 * test.
 *
 * <h2>Why an empty result is an error here</h2>
 *
 * Under row-level security a missing transaction boundary produces zero rows,
 * and zero rows reads exactly like "no such scope". That resemblance is the
 * trap: the plausible repair for "no such scope" is to widen a privilege or
 * to fall back on a local table, and both would be repairs to a symptom whose
 * cause was a forgotten binding. So the binding is checked first and
 * separately, and its absence is a different typed error from an
 * unresolvable scope. Neither is ever an empty return.
 *
 * <h2>This service serves all three kinds</h2>
 *
 * Unlike its siblings it refuses no kind. A private scope is a per-tenant
 * container for exactly this service's content, a global scope is the
 * tenant-wide one, and a project scope is the ordinary case. Which entries
 * inside a private scope a caller sees is decided one level down, on
 * {@code owner_subject}, and not here.
 */
@ApplicationScoped
@TenantBound
public class ScopeDirectory {

    /** Bound by the same convention as every logger here: no title, no body, no
     *  metadata text, no token, and no actor. A slug is a scope name and an
     *  address; the subject that asked for it is the audit log's business. */
    private static final Logger LOG = Logger.getLogger(ScopeDirectory.class);

    /** The kind whose entries are private to their author, as the platform spells it. */
    public static final String KIND_PRIVATE = "private";

    /** The tenant-wide kind, as the platform spells it. */
    public static final String KIND_GLOBAL = "global";

    @Inject ScopeAccessRepository scopes;

    /**
     * The scope a caller named, or a typed refusal.
     *
     * @param subject the calling subject, as derived from the token
     * @param slug    the scope name the caller used
     * @param access  what the call is about to do in that scope
     */
    @Transactional
    public ScopeAccess resolve(String subject, String slug, Access access) {
        bindSubject(subject);
        requireSessionBound();

        Optional<ScopeAccessRepository.ScopeAccessRow> row = scopes.findBySlug(slug);

        if (row.isEmpty()) {
            // Reached only with both settings bound, so this genuinely means
            // "no such scope for this subject" and not "nothing was bound".
            LOG.warnf("scope '%s' unresolved: %s", slug,
                MemoryException.Reason.SCOPE_UNRESOLVED);
            throw new MemoryException(MemoryException.Reason.SCOPE_UNRESOLVED,
                "no scope '" + slug + "' is open to this subject. The directory answers "
                    + "for the bound subject only, and existence in its answer is the "
                    + "permission — so this is a refusal, not a missing row to be "
                    + "worked around.");
        }

        ScopeAccess resolved = accessOf(row.get());
        requireWritable(resolved, access);
        LOG.debugf("resolved scope '%s'", slug);
        return resolved;
    }

    /**
     * The scope a stored row belongs to, or empty when this caller may not see it.
     *
     * <p>Empty and not a refusal, and the asymmetry with {@link #resolve} is
     * the point. This is reached from the technical address, where the caller
     * named an entry and not a scope: a refusal here would say "that entry is
     * in a scope you cannot enter", which tells the caller the entry exists.
     * The caller of this method answers the single not-found instead.
     */
    @Transactional
    public Optional<ScopeAccess> visibleScopeOf(String subject, UUID scopeId) {
        bindSubject(subject);
        requireSessionBound();
        return scopes.findById(scopeId).map(ScopeDirectory::accessOf);
    }

    /**
     * The tenant's global scope, where this caller can see one.
     *
     * <p>A list rather than an optional, because the contract does not promise
     * that there is exactly one: {@code uq_scope_one_private} constrains the
     * private kind and nothing constrains the global one. Answering the first
     * of several would be a guess; the caller decides what to do with none,
     * one, or more.
     */
    @Transactional
    public List<ScopeAccess> globalScopes(String subject) {
        bindSubject(subject);
        requireSessionBound();
        return scopes.findByKind(KIND_GLOBAL).stream().map(ScopeDirectory::accessOf).toList();
    }

    private static ScopeAccess accessOf(ScopeAccessRepository.ScopeAccessRow row) {
        return new ScopeAccess(row.scopeId(), row.tenantId(), row.slug(), row.archived(),
            row.kind(), row.locked(), row.canWrite());
    }

    /**
     * Refuses a write the platform does not permit, and keeps the two reasons
     * for that apart.
     *
     * <p><strong>The lock is checked first, and the order is load-bearing.</strong>
     * The view derives {@code can_write} as {@code NOT locked AND …}, so a
     * locked scope always arrives with the write right already false. Checking
     * the write right first would therefore answer every locked scope with
     * {@code SCOPE_READ_ONLY} and leave {@code SCOPE_LOCKED} unreachable — a
     * code that exists, is declared, and can never be produced. The two are
     * different things to a caller: a lock is lifted by whoever locked the
     * scope, a missing write right is lifted by whoever administers the
     * membership, and telling somebody to go to the wrong one of those is
     * worse than telling them nothing.
     *
     * <p>Reading stays permitted in both cases. Neither condition is about
     * seeing the scope — visibility is the directory's answer, and this
     * caller already has it.
     */
    private void requireWritable(ScopeAccess scope, Access access) {
        if (access != Access.WRITE) {
            return;
        }

        if (scope.locked()) {
            LOG.warnf("write into a locked scope refused: %s",
                MemoryException.Reason.SCOPE_LOCKED);
            throw new MemoryException(MemoryException.Reason.SCOPE_LOCKED,
                "the scope '" + scope.slug() + "' is locked, so it refuses every write "
                    + "over a service channel whatever the caller's role. Reading it is "
                    + "unaffected. The lock is the scope's own state and is lifted where "
                    + "it was set, not by presenting a different token here.");
        }

        if (!scope.canWrite()) {
            LOG.warnf("write without the write right refused: %s",
                MemoryException.Reason.SCOPE_READ_ONLY);
            throw new MemoryException(MemoryException.Reason.SCOPE_READ_ONLY,
                "this caller may read the scope '" + scope.slug() + "' but not write to "
                    + "it over a service channel. The write right is the platform's "
                    + "answer about the membership, not this service's about the entry — "
                    + "so no verb here reaches the effect, and the remedy is the "
                    + "membership.");
        }
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    private void bindSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new MemoryException(MemoryException.Reason.SESSION_NOT_BOUND,
                "there is no subject to bind to app.subject. The directory answers for a "
                    + "subject, so resolving without one would be asking a question with "
                    + "no asker — and the answer would be zero rows, which reads as "
                    + "'no such scope'.");
        }
        scopes.bindSubject(subject);
    }

    /**
     * Fails loudly when either setting is unbound.
     *
     * <p>This runs BEFORE the query rather than interpreting its result,
     * because after the fact the two cases are indistinguishable: both produce
     * zero rows. Checking first is what lets the refusal name the actual cause,
     * and naming the cause is what stops the next person from repairing the
     * wrong thing.
     */
    private void requireSessionBound() {
        Object tenant = scopes.boundTenant();
        Object subject = scopes.boundSubject();

        if (tenant == null || subject == null) {
            LOG.warnf("directory call with unbound session: %s",
                MemoryException.Reason.SESSION_NOT_BOUND);
            throw new MemoryException(MemoryException.Reason.SESSION_NOT_BOUND,
                ("the session contract is not bound (app.tenant_id=%s, app.subject=%s), so "
                    + "the directory would return zero rows for every scope. That reads as "
                    + "'no such scope' and invites a repair to the privileges — which is "
                    + "why this fails here instead of returning nothing.")
                    .formatted(tenant == null ? "unset" : "set",
                               subject == null ? "unset" : "set"));
        }
    }

    /**
     * One row of the read contract: the access answer, never the membership
     * behind it.
     *
     * <p>{@code archived} is published rather than filtered, deliberately: a
     * write into a retired scope must be refusable with a specific error
     * rather than with "not found", and a directory that hid archived scopes
     * could not tell the two apart. No verb of this service refuses on it yet.
     */
    public record ScopeAccess(UUID scopeId, UUID tenantId, String slug, boolean archived,
                              String kind, boolean locked, boolean canWrite) {

        /** Whether entries in this scope are private to their author. */
        public boolean isPrivate() {
            return KIND_PRIVATE.equals(kind);
        }
    }
}
