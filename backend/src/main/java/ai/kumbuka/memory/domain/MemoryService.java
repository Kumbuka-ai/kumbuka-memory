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
import ai.kumbuka.memory.repository.MemoryRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import ai.kumbuka.memory.tenancy.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The five verbs that act on an entry, and the order they check in.
 *
 * <h2>The check order, and why it is written once</h2>
 *
 * <ol>
 *   <li><b>Grammar</b> — already done, at the surface. It is decidable without
 *       knowing a scope, which is the only reason it is allowed to run before
 *       the scope is resolved.</li>
 *   <li><b>The scope</b> — resolved against the platform's read contract, for
 *       the access the call is about to make. A scope this caller cannot see
 *       is the single not-found; a scope it can see but not write to is the
 *       lock or the missing write right, which reveal nothing because the
 *       caller already has the scope.</li>
 *   <li><b>Visibility of the entry</b> — a private entry belongs to its author
 *       and is the single not-found for anybody else. Decided BEFORE the
 *       conflict token (DEC-0041), so that a token refusal can never tell a
 *       caller that something exists.</li>
 *   <li><b>The conflict token</b> — missing, or no longer the current one.</li>
 *   <li><b>The values</b> — type, content, reference.</li>
 * </ol>
 *
 * <p>The order lives here and not in the adapters. Two adapters checking in
 * two orders is the drift the protocol-neutral layer exists to prevent, and
 * the order is precisely the part that would drift.
 */
@ApplicationScoped
@TenantBound
public class MemoryService {

    /**
     * The longest an entry may be, restated from {@code ck_memory_content_len}
     * (V1, named so by V9).
     *
     * <p>Checked here as well as there. The table's constraint is the backstop
     * that also holds against SQL which never came through a verb; this is the
     * one that produces a refusal a caller can read instead of a constraint
     * violation at flush time.
     */
    public static final int CONTENT_LIMIT = 1500;

    /** The two shapes of credential V1 refuses in a provenance pointer. */
    private static final Pattern REFERENCE_USERINFO =
        Pattern.compile("^[a-z][a-z0-9+.-]*://[^/@\\s]*@", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_SECRET_PARAM =
        Pattern.compile("[?&](token|password|passwd|secret|api[_-]?key|access[_-]?token)=",
            Pattern.CASE_INSENSITIVE);

    /** The channel every write over this surface was made through. */
    private static final String SOURCE_SERVICE = "mcp";

    /** The argument a type travels in, and the one its refusal names. */
    private static final String TYPE = "type";

    /** One INFO line per completed write, naming the verb and the entry (C13.3). */
    private static final Logger LOG = Logger.getLogger(MemoryService.class);

    @Inject ScopeDirectory scopes;
    @Inject MemoryRepository entries;
    @Inject TenantContext tenants;
    @Inject Withdrawal withdrawal;
    @Inject Clock clock;

    // ======================================================================
    // create
    // ======================================================================

    /**
     * Lays down a new entry, and only ever a new one.
     *
     * <p>No upsert and no {@code existed} flag: a write onto an occupied
     * address is refused with the address of what stands there, so a caller
     * that meant to change it has the address to send the change to. The
     * behaviour the core has today — silently rewriting whatever was there —
     * is what this replaces.
     */
    @Transactional
    public EntryView create(Actor actor, EntryAddress address, Draft draft) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.WRITE);
        refuseFixed(draft.fixed());

        String type = requireKnownType(draft.type());
        String content = requireContent(draft.content());
        requireCleanReference(draft.reference());

        entries.findOccupant(scope.scopeId(), address.key(), scope.isPrivate(),
                actor.subject())
            .ifPresent(occupant -> {
                throw occupied();
            });

        Memory entry = new Memory();
        entry.logicalId = UUID.randomUUID();
        entry.version = 1;
        entry.tenantId = tenants.current().toString();
        entry.scopeId = scope.scopeId();
        entry.isPrivate = scope.isPrivate();
        entry.ownerSubject = actor.subject();
        entry.type = type;
        entry.key = address.key();
        entry.content = content;
        entry.reference = draft.reference();
        entry.source = SOURCE_SERVICE;
        entry.laidDownAt(clock.instant());

        // The occupant check above answers the ordinary case. A second create
        // on the same key that passed it at the same time meets the unique
        // index at the insert instead; the repository reports that as an
        // absent insert, and it is the same occupied address.
        Memory laidDown = entries.insert(entry).orElseThrow(MemoryService::occupied);
        LOG.infof("create %s", technical(laidDown));
        return EntryView.of(laidDown, scope.slug(), true);
    }

    // ======================================================================
    // read
    // ======================================================================

    /** The entry at a canonical address, or the single not-found. */
    @Transactional
    public EntryView read(Actor actor, EntryAddress address) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.READ);
        Memory entry = entries.findInScope(scope.scopeId(), address.key(), actor.subject())
            .orElseThrow(MemoryService::absent);
        return EntryView.of(entry, scope.slug(), writable(scope));
    }

    /**
     * The entry a technical address names, or the single not-found.
     *
     * <p>No scope is pre-checked, which is what the contract asks for: the row
     * is found first and its scope resolved afterwards. Every way this can
     * fail answers the same not-found — a foreign tenant's row is not found
     * at all, because the policy and the ORM filter both bind; a private row
     * of another author is filtered by the statement; and a row in a scope
     * this caller cannot enter is found and then refused, with the same bytes
     * as the other two.
     */
    @Transactional
    public EntryView readByLogicalId(Actor actor, UUID logicalId) {
        Memory entry = entries.findByLogicalId(logicalId, actor.subject())
            .orElseThrow(MemoryService::absent);
        ScopeDirectory.ScopeAccess scope =
            scopes.visibleScopeOf(actor.subject(), entry.scopeId)
                .orElseThrow(MemoryService::absent);
        return EntryView.of(entry, scope.slug(), writable(scope));
    }

    // ======================================================================
    // update
    // ======================================================================

    /**
     * Changes an entry's content, type or reference.
     *
     * <p>A patch whose values already equal the entry's writes nothing,
     * allocates no new token and leaves the change time alone (DEC-0041). The
     * answer is the entry as it stands, with the token the caller already had
     * — so a retry that arrives twice is not a second version.
     *
     * <p>The entry is read under {@code PESSIMISTIC_WRITE} and refreshed under
     * the lock, and only then is the token compared (C10.2, C11.1). The read,
     * the comparison and the write stand in this one transaction, which the
     * adapter's transaction joins, so a second writer with the same token
     * waits at the read and then compares against what the first wrote.
     */
    @Transactional
    public EntryView update(Actor actor, EntryAddress address, @Nullable String conflictToken,
                            Patch patch) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.WRITE);
        refuseFixed(patch.fixed());
        Memory entry = entries.lockInScope(scope.scopeId(), address.key(), actor.subject())
            .orElseThrow(MemoryService::absent);

        requireCurrentToken(conflictToken, entry, scope);
        requireSomethingToDo(patch);

        String type = patch.type().map(MemoryService::requireKnownType).orElse(entry.type);
        String content = patch.content().orElse(entry.content);
        @Nullable String reference = patch.reference().orElse(entry.reference);
        requireContent(content);
        requireCleanReference(reference);

        boolean changed = !type.equals(entry.type)
            || !content.equals(entry.content)
            || !java.util.Objects.equals(reference, entry.reference);

        if (!changed) {
            return EntryView.of(entry, scope.slug(), true);
        }

        entry.type = type;
        entry.content = content;
        entry.reference = reference;
        entry.updatedBy = actor.subject();
        entry.updatedSource = SOURCE_SERVICE;
        entry.changedAt(clock.instant());

        Memory updated = entries.update(entry);
        LOG.infof("update %s", technical(updated));
        return EntryView.of(updated, scope.slug(), true);
    }

    // ======================================================================
    // withdraw
    // ======================================================================

    /**
     * Takes an entry out of force, by whatever means this edition withdraws.
     *
     * <p>The act itself is not decided here. What is decided here is
     * everything around it — that the scope permits a write, that the entry is
     * this caller's to see, that the token is current — and the edition's
     * implementation is handed an entry that has passed all of it.
     *
     * <p>Read under {@code PESSIMISTIC_WRITE} and refreshed under the lock,
     * as {@code update} is, so that a withdrawal never removes an entry that
     * changed after its caller read the token.
     */
    @Transactional
    public Withdrawn withdraw(Actor actor, EntryAddress address,
                              @Nullable String conflictToken) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.WRITE);
        Memory entry = entries.lockInScope(scope.scopeId(), address.key(), actor.subject())
            .orElseThrow(MemoryService::absent);

        requireCurrentToken(conflictToken, entry, scope);

        String withdrawn = technical(entry);
        Withdrawal.Outcome outcome = withdrawal.withdraw(entry);
        LOG.infof("withdraw %s", withdrawn);

        // Measured after the act, not inferred from it. What the answer says
        // is open next has to come from what is actually true — a listed call
        // that is then refused is exactly what DEC-0040 forbids — and whether
        // an address is free afterwards is the edition's business, not this
        // method's to predict from an enum constant.
        boolean released = entries
            .findOccupant(scope.scopeId(), address.key(), scope.isPrivate(),
                actor.subject())
            .isEmpty();

        return new Withdrawn(address, outcome, released);
    }

    // ======================================================================
    // query
    // ======================================================================

    /**
     * One page of a scope, optionally narrowed to a selector.
     *
     * <p>The selector is optional and its absence is not a wildcard bolted on
     * afterwards: a query without one reads the whole scope, which is what a
     * caller that does not yet know the estate's selectors needs in order to
     * find out what they are.
     *
     * <p>A cursor is held against the query it is handed to only after the
     * scope is resolved. Its form is decidable without a scope, its binding is
     * not: it names the scope by a mark of its technical identity, which only
     * the resolution yields, and a scope this caller may not enter is the
     * single not-found before anything is said about a cursor.
     */
    @Transactional
    public Page query(Actor actor, String scopeSlug, @Nullable String selector,
                      @Nullable String type, @Nullable String text, PageRequest request) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), scopeSlug, Access.READ);
        // The type is the service's vocabulary, so it is checked once the
        // scope is known to be visible: an invisible scope answers NOT_FOUND
        // whatever type was asked for, as C1.3 orders the checks.
        QueryFilter filter = new QueryFilter(
            type == null || type.isBlank() ? null : knownType(type),
            text == null || text.isBlank() ? null : text);

        PageCursor.Binding binding = new PageCursor.Binding(PageCursor.mark(scope.scopeId()), selector,
            PageCursor.fingerprint(filter.type(), filter.text()), request.sort(),
            request.direction());
        String asked = request.after();
        @Nullable PageCursor after = asked == null ? null : PageCursor.decode(asked);
        if (after != null && !after.binding().equals(binding)) {
            throw foreign();
        }

        MemoryRepository.Rows page = entries.page(new MemoryRepository.Keyset(
            scope.scopeId(), selector, actor.subject(), filter, request, after));

        boolean writable = writable(scope);
        List<EntryView> views = page.entries().stream()
            .map(entry -> EntryView.of(entry, scope.slug(), writable))
            .toList();

        @Nullable String next = null;
        if (page.more() && !page.entries().isEmpty()) {
            Memory last = page.entries().get(page.entries().size() - 1);
            next = new PageCursor(binding, last.key, last.createdAt).encode();
        }
        return new Page(views, next, page.unaddressable());
    }

    // ======================================================================
    // The checks, each in one place
    // ======================================================================

    /**
     * Refuses a write that names a field fixed for the life of an entry,
     * naming the fields and never their values. Decided once the scope is
     * resolved, so that an invisible scope answers NOT_FOUND whatever else the
     * call names (C1.3).
     */
    private static void refuseFixed(List<String> fixed) {
        if (!fixed.isEmpty()) {
            throw MemoryException.offending(MemoryException.Reason.FIELD_IMMUTABLE, fixed);
        }
    }

    /**
     * The technical address of an entry, the one an INFO line names (C13.3).
     * Minted by this service and no value its caller wrote: the canonical
     * address carries the caller's key, and whether that may stand in a log
     * line is not decided.
     */
    private static String technical(Memory entry) {
        return "memory://" + entry.logicalId;
    }

    private static String requireKnownType(@Nullable String type) {
        return knownType(type).wire();
    }

    private static EntryType knownType(@Nullable String type) {
        return EntryType.of(type)
            .orElseThrow(() -> MemoryException.offending(MemoryException.Reason.TYPE_UNKNOWN,
                List.of(TYPE), EntryType.wireNames()));
    }

    private static String requireContent(@Nullable String content) {
        if (content == null || content.isBlank()) {
            throw new MemoryException(MemoryException.Reason.CONTENT_ABSENT);
        }
        if (content.length() > CONTENT_LIMIT) {
            throw new MemoryException(MemoryException.Reason.CONTENT_OVERSIZE);
        }
        return content;
    }

    private static void requireCleanReference(@Nullable String reference) {
        if (reference == null) {
            return;
        }
        if (REFERENCE_USERINFO.matcher(reference).find()
            || REFERENCE_SECRET_PARAM.matcher(reference).find()) {
            // The value is NOT echoed: it is the thing that carries the
            // credential, and a refusal that quoted it would copy the secret
            // into every log and transcript the answer reaches.
            throw new MemoryException(MemoryException.Reason.REFERENCE_CREDENTIAL_BEARING);
        }
    }

    private static void requireSomethingToDo(Patch patch) {
        if (patch.isEmpty()) {
            throw new MemoryException(MemoryException.Reason.UPDATE_EMPTY);
        }
    }

    /**
     * The token check, and the refusal that carries the whole entry.
     *
     * <p>A stale token is answered with the entry as this caller's own
     * {@code read} would answer it (DEC-0041) — the same projection, produced
     * by the same code, so that a caller which lost a race can re-form its
     * write from the refusal instead of reading again.
     */
    private void requireCurrentToken(@Nullable String presented, Memory entry,
                                     ScopeDirectory.ScopeAccess scope) {
        if (presented == null || presented.isBlank()) {
            throw new MemoryException(MemoryException.Reason.CONFLICT_TOKEN_MISSING);
        }

        String current = EntryView.tokenOf(entry);
        if (!current.equals(presented)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(MemoryException.ADDRESS,
                EntryAddress.ofKey(scope.slug(), entry.key).canonical());
            data.put("conflict_token", current);
            data.put("fields", EntryView.of(entry, scope.slug(), writable(scope)));
            throw new MemoryException(MemoryException.Reason.CONFLICT_TOKEN_STALE, data);
        }
    }

    /**
     * An occupied address, named by neither its address nor its key: the
     * caller sent both and holds them, and a refusal repeats no value the
     * caller sent (DEC-0042).
     */
    private static MemoryException occupied() {
        return new MemoryException(MemoryException.Reason.ALREADY_EXISTS);
    }

    /**
     * The one not-found this service answers, built in one place.
     *
     * <p>Every caller of it — an absent row, a private row of another author,
     * a row whose scope this caller may not enter — throws this same instance
     * shape, so the three cannot be told apart by anything downstream.
     */
    private static MemoryException absent() {
        return new MemoryException(MemoryException.Reason.ENTRY_ABSENT);
    }

    /** Whether this caller may write in a scope, which is what decides `next`. */
    private static boolean writable(ScopeDirectory.ScopeAccess scope) {
        return !scope.locked() && scope.canWrite();
    }

    /**
     * A cursor handed out for another query.
     *
     * <p>Refused rather than applied. Applied, the position of one listing
     * continues another one: measured on the cursor this replaced, a cursor
     * from one selector answered zero entries on another and read exactly
     * like the end of a listing.
     */
    private static MemoryException foreign() {
        return MemoryException.offending(MemoryException.Reason.CURSOR_FOREIGN,
            List.of(PageCursor.AFTER));
    }

    /**
     * What a withdrawal did.
     *
     * @param address         the entry's address, still complete, so that the
     *                        answer names what was withdrawn rather than
     *                        merely confirming
     * @param outcome         whether the row is gone or retired, which is the
     *                        edition's answer and not this service's
     * @param addressReleased whether anything still stands at the address,
     *                        read back after the act rather than inferred
     *                        from the outcome
     */
    public record Withdrawn(EntryAddress address, Withdrawal.Outcome outcome,
                            boolean addressReleased) {
    }
}
