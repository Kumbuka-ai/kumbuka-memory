package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.platform.Access;
import ai.kumbuka.memory.platform.ScopeDirectory;
import ai.kumbuka.memory.repository.MemoryRepository;
import ai.kumbuka.memory.repository.StoredInstant;
import ai.kumbuka.memory.tenancy.TenantBound;
import ai.kumbuka.memory.tenancy.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.Base64;
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
     * The longest an entry may be, restated from {@code memory_content_len}
     * in V1.
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

    @Inject ScopeDirectory scopes;
    @Inject MemoryRepository entries;
    @Inject TenantContext tenants;
    @Inject Withdrawal withdrawal;

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

        String type = requireKnownType(draft.type());
        requireContent(draft.content());
        requireCleanReference(draft.reference());

        entries.findOccupant(scope.scopeId(), address.key(), scope.isPrivate(),
                actor.subject())
            .ifPresent(occupant -> {
                throw occupied(address);
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
        entry.content = draft.content();
        entry.reference = draft.reference();
        entry.source = SOURCE_SERVICE;

        return EntryView.of(entries.insert(entry), scope.slug(), true);
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
     */
    @Transactional
    public EntryView update(Actor actor, EntryAddress address, String conflictToken,
                            Patch patch) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.WRITE);
        Memory entry = entries.findInScope(scope.scopeId(), address.key(), actor.subject())
            .orElseThrow(MemoryService::absent);

        requireCurrentToken(conflictToken, entry, scope);
        requireSomethingToDo(patch, address);

        String type = patch.type().map(MemoryService::requireKnownType).orElse(entry.type);
        String content = patch.content().orElse(entry.content);
        String reference = patch.reference().orElse(entry.reference);
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
        entry.updatedAt = StoredInstant.now();

        return EntryView.of(entries.update(entry), scope.slug(), true);
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
     */
    @Transactional
    public Withdrawn withdraw(Actor actor, EntryAddress address, String conflictToken) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), address.scope(), Access.WRITE);
        Memory entry = entries.findInScope(scope.scopeId(), address.key(), actor.subject())
            .orElseThrow(MemoryService::absent);

        requireCurrentToken(conflictToken, entry, scope);

        Withdrawal.Outcome outcome = withdrawal.withdraw(entry);

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
     */
    @Transactional
    public Listing query(Actor actor, String scopeSlug, String selector, QueryFilter filter) {
        ScopeDirectory.ScopeAccess scope =
            scopes.resolve(actor.subject(), scopeSlug, Access.READ);

        MemoryRepository.Page page = entries.page(new MemoryRepository.PageRequest(
            scope.scopeId(), selector, filter.type(), filter.text(),
            decodeCursor(filter.after()), filter.pageSize(), actor.subject()));

        boolean writable = writable(scope);
        List<EntryView> views = page.entries().stream()
            .map(entry -> EntryView.of(entry, scope.slug(), writable))
            .toList();

        String cursor = page.more() && !views.isEmpty()
            ? encodeCursor(views.get(views.size() - 1).key())
            : null;

        return new Listing(views, page.total(), page.more(), cursor, filter.pageSize(),
            page.unaddressable());
    }

    // ======================================================================
    // The checks, each in one place
    // ======================================================================

    private static String requireKnownType(String type) {
        return EntryType.of(type)
            .orElseThrow(() -> MemoryException.offending(
                MemoryException.Reason.TYPE_UNKNOWN,
                "'" + type + "' is not a kind of entry this service carries. The six are "
                    + String.join(", ", EntryType.wireNames()) + ". The kind decides where "
                    + "the entry appears in a digest, which is why it is a fixed "
                    + "vocabulary rather than a label.",
                List.of(String.valueOf(type))))
            .wire();
    }

    private static void requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new MemoryException(MemoryException.Reason.CONTENT_ABSENT,
                "an entry is its content, so there is nothing to lay down without it. "
                    + "Send the entry's text under 'fields.content'.");
        }
        if (content.length() > CONTENT_LIMIT) {
            throw new MemoryException(MemoryException.Reason.CONTENT_OVERSIZE,
                "an entry carries at most " + CONTENT_LIMIT + " characters and this one "
                    + "carries " + content.length() + ". An entry is a curated statement "
                    + "meant to be read whole in a digest; what does not fit belongs in "
                    + "the document the 'reference' points at.");
        }
    }

    private static void requireCleanReference(String reference) {
        if (reference == null) {
            return;
        }
        if (REFERENCE_USERINFO.matcher(reference).find()
            || REFERENCE_SECRET_PARAM.matcher(reference).find()) {
            // The value is NOT echoed: it is the thing that carries the
            // credential, and a refusal that quoted it would copy the secret
            // into every log and transcript the answer reaches.
            throw new MemoryException(MemoryException.Reason.REFERENCE_CREDENTIAL_BEARING,
                "the provenance pointer carries a credential — either user information "
                    + "before an '@' in its authority, or one of the secret-bearing query "
                    + "parameters. A reference is read by everyone who can read the entry, "
                    + "so it may not be a way to store one. The value is not repeated "
                    + "here, for the same reason. Point at the resource without the "
                    + "credential.");
        }
    }

    private static void requireSomethingToDo(Patch patch, EntryAddress address) {
        if (patch.isEmpty()) {
            throw new MemoryException(MemoryException.Reason.UPDATE_EMPTY,
                "this update names no field to change. The fields an entry admits are "
                    + "'type', 'content' and 'reference'; scope and key are fixed for its "
                    + "life. Read " + address.canonical() + " to see what stands there.");
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
    private void requireCurrentToken(String presented, Memory entry,
                                     ScopeDirectory.ScopeAccess scope) {
        if (presented == null || presented.isBlank()) {
            throw new MemoryException(MemoryException.Reason.CONFLICT_TOKEN_MISSING,
                "this write needs the conflict token of the entry it changes. It is the "
                    + "value the last read handed out, and it travels in the 'If-Match' "
                    + "header. Read the entry and send back the token it answers with.");
        }

        String current = EntryView.tokenOf(entry);
        if (!current.equals(presented)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(MemoryException.ADDRESS,
                EntryAddress.ofKey(scope.slug(), entry.key).canonical());
            data.put("conflict_token", current);
            data.put("fields", EntryView.of(entry, scope.slug(), writable(scope)));
            throw new MemoryException(MemoryException.Reason.CONFLICT_TOKEN_STALE,
                "the entry has changed since the token presented was handed out. The "
                    + "refusal carries the entry as it stands now, with its current "
                    + "token, so the write can be re-formed against that rather than "
                    + "against what was read before. Nothing was changed.", data);
        }
    }

    private static MemoryException occupied(EntryAddress address) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(MemoryException.ADDRESS, address.canonical());
        return new MemoryException(MemoryException.Reason.ALREADY_EXISTS,
            "an entry already stands at " + address.canonical() + ". This verb only ever "
                + "lays down a new one — it never overwrites — so the entry that is there "
                + "is untouched. Send the change to that address with 'update', or choose "
                + "another key.", data);
    }

    /**
     * The one not-found this service answers, built in one place.
     *
     * <p>Every caller of it — an absent row, a private row of another author,
     * a row whose scope this caller may not enter — throws this same instance
     * shape, so the three cannot be told apart by anything downstream.
     */
    private static MemoryException absent() {
        return new MemoryException(MemoryException.Reason.ENTRY_ABSENT,
            "there is nothing at this address for this caller.");
    }

    /** Whether this caller may write in a scope, which is what decides `next`. */
    private static boolean writable(ScopeDirectory.ScopeAccess scope) {
        return !scope.locked() && scope.canWrite();
    }

    // ======================================================================
    // The cursor
    // ======================================================================

    /**
     * The cursor is the last key of a page, encoded so it is not mistaken for
     * a value to construct.
     *
     * <p>Base64url of the key and nothing more. It is not a secret and is not
     * treated as one — what the encoding buys is that a caller reads it as an
     * opaque token handed back, rather than as a field it may compose itself
     * and then depend on the order of.
     */
    private static String encodeCursor(String key) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new MemoryException(MemoryException.Reason.CURSOR_MALFORMED,
                "this is not a cursor this verb handed out. A cursor is taken from the "
                    + "'cursor' member of a previous page and passed back unchanged; it "
                    + "is not composed by the caller. Start again without one to get the "
                    + "first page.");
        }
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
