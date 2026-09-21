package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Actor;
import ai.kumbuka.memory.domain.DigestService;
import ai.kumbuka.memory.domain.DigestView;
import ai.kumbuka.memory.domain.Draft;
import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.Listing;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.domain.Patch;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The six verbs, once, for every protocol that reaches them.
 *
 * <h2>Why this layer exists at all</h2>
 *
 * Only one protocol reaches it today — REST — and there will be no MCP adapter
 * in this service: the router is the assistant-facing entry point and a
 * per-service adapter is a later question. So this is not a layer earning its
 * keep by serving two callers. What it earns is that the address grammar, the
 * predicate vocabulary and the next-step table are not in the resource class,
 * where they would be indistinguishable from HTTP concerns and would move with
 * the next change to one.
 *
 * <h2>What belongs here and what does not</h2>
 *
 * Here: which verbs exist, what they take, what a caller may write as an
 * address, which predicates a query carries, and which calls an answer offers
 * next. Not here: whether a particular caller may make one — that is the
 * platform's read contract, asked by the domain — and nothing about HTTP.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    /**
     * The predicates {@code query} carries, and the whole of them.
     *
     * <p>An unknown one is refused by name rather than dropped. A dropped
     * filter answers with the full set and looks exactly like a correct narrow
     * one, which is the failure a caller cannot see and cannot ask about.
     */
    private static final Set<String> PREDICATES =
        Set.of("type", "text", "after", "page_size");

    @Inject MemoryService memory;
    @Inject DigestService digests;

    // ======================================================================
    // The verbs
    // ======================================================================

    /**
     * Lays down a new entry in a scope, under a selector.
     *
     * <p>The address is truncated at the selector and the key completes it.
     * The key is a value of the entry — it is what the table stores and what
     * {@code read} answers with — so it arrives with the other values and not
     * as a path segment, and the selector it carries has to be the one the
     * path named.
     */
    @Transactional
    public Answer create(Actor actor, String scope, String selector, NewEntry values) {
        EntryAddress address = AddressParser.ofKey(scope, values.key());
        AddressParser.requireAgreement(selectorOf(selector), address);
        AddressParser.requireWritableSelector(address);

        return answerFor(memory.create(actor,
            address, new Draft(values.type(), values.content(), values.reference())));
    }

    /** The entry at a canonical address. */
    @Transactional
    public Answer read(Actor actor, AddressParser.Parts parts) {
        return answerFor(memory.read(actor, AddressParser.parse(parts)));
    }

    /**
     * The entry a technical address names.
     *
     * <p>The answer carries the canonical address all the same — the uuid goes
     * in and never comes back out, which is what "no uuid in any answer" means
     * where a uuid is a legitimate thing to send.
     */
    @Transactional
    public Answer readTechnical(Actor actor, UUID logicalId) {
        return answerFor(memory.readByLogicalId(actor, logicalId));
    }

    /** Changes an entry's content, type or reference. */
    @Transactional
    public Answer update(Actor actor, AddressParser.Parts parts, String conflictToken,
                         Patch patch) {
        EntryAddress address = AddressParser.parse(parts);
        AddressParser.requireWritableSelector(address);
        return answerFor(memory.update(actor, address, conflictToken, patch));
    }

    /** Takes an entry out of force, by whatever means this edition withdraws. */
    @Transactional
    public MemoryService.Withdrawn withdraw(Actor actor, AddressParser.Parts parts,
                                            String conflictToken) {
        EntryAddress address = AddressParser.parse(parts);
        AddressParser.requireWritableSelector(address);
        return memory.withdraw(actor, address, conflictToken);
    }

    /**
     * One page of a scope, optionally narrowed to a selector.
     *
     * @param selector the selector, or null for every selector of the scope
     * @param asked    the query parameters exactly as they arrived, so that an
     *                 unknown one can be refused instead of ignored
     */
    @Transactional
    public Listing query(Actor actor, String scope, String selector,
                         Map<String, String> asked) {
        if (selector != null) {
            // Checked even though nothing is addressed by it: a caller that
            // mistyped a selector would otherwise get an empty page, which
            // reads as "there is nothing of that kind here".
            AddressParser.parse(scope, selector, "x");
        }
        return memory.query(actor, scope, selector, filterOf(asked));
    }

    /** Everything in force in a scope, whole. */
    @Transactional
    public DigestView digest(Actor actor, String scope, List<String> types) {
        return digests.digest(actor, scope, types);
    }

    // ======================================================================
    // The next steps
    // ======================================================================

    /**
     * The calls this caller may make on this entry, from the state it is in.
     *
     * <p>Computed from the same answer that permits and refuses them: the
     * write right the platform's read contract gave for the entry's scope,
     * carried on the projection. A second table would drift from the one that
     * decides what succeeds, and a listed call that is then refused is exactly
     * what the rule forbids (DEC-0040).
     *
     * <p>{@code read} is always here, because an answer about an entry is
     * proof that this caller may read it. So the list is never empty, and
     * {@code waiting_for} — the member for an answer with no next step and no
     * finished object — never arises in this service: an entry waits for
     * nothing and is never in a state that only somebody else can move.
     */
    public static List<NextStep> nextFor(EntryView entry) {
        String address = entry.address().canonical();
        List<NextStep> next = new ArrayList<>();
        next.add(new NextStep("read " + address,
            "reads the entry again and hands out a fresh conflict token"));

        if (entry.writable()) {
            next.add(new NextStep("update " + address,
                "changes the content, the type or the reference; needs the conflict token"));
            next.add(new NextStep("withdraw " + address,
                "takes the entry out of force; needs the conflict token"));
        }
        return List.copyOf(next);
    }

    // ======================================================================
    // The predicates
    // ======================================================================

    private static QueryFilter filterOf(Map<String, String> asked) {
        List<String> unknown = asked.keySet().stream()
            .filter(name -> !PREDICATES.contains(name))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw MemoryException.offending(MemoryException.Reason.PREDICATE_UNKNOWN,
                "this verb carries the predicates " + PREDICATES.stream().sorted().toList()
                    + " and no others. An unknown one is refused rather than ignored: a "
                    + "filter that is silently dropped answers with the whole set and "
                    + "looks exactly like a correct narrow one.",
                unknown);
        }

        return new QueryFilter(
            typeOf(asked.get("type")),
            blankToNull(asked.get("text")),
            blankToNull(asked.get("after")),
            pageSizeOf(asked.get("page_size")));
    }

    private static EntryType typeOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return null;
        }
        return EntryType.of(asked).orElseThrow(() -> MemoryException.offending(
            MemoryException.Reason.TYPE_UNKNOWN,
            "'" + asked + "' is not a kind of entry this service carries. The six are "
                + String.join(", ", EntryType.wireNames()) + ".",
            List.of(asked)));
    }

    private static int pageSizeOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return QueryFilter.DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(asked.trim());
        } catch (NumberFormatException notANumber) {
            throw new MemoryException(MemoryException.Reason.PAGE_SIZE_REJECTED,
                "'" + asked + "' is not a page size. It is a whole number between 1 and "
                    + QueryFilter.MAX_PAGE_SIZE + ".");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The selector as a path segment, refused where it is absent.
     *
     * <p>{@code create} is addressed at a collection, and a collection here
     * always names a selector: an entry has to stand under one.
     */
    private static String selectorOf(String selector) {
        return Optional.ofNullable(selector)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new MemoryException(MemoryException.Reason.SELECTOR_ABSENT,
                "an entry is laid down under a selector, so the address it is posted to "
                    + "names one: 'memory://<scope>/<selector>'."));
    }

    // ======================================================================
    // What a verb answers
    // ======================================================================

    private static Answer answerFor(EntryView entry) {
        return new Answer(entry, nextFor(entry));
    }

    /**
     * One answer about one entry.
     *
     * <p>The entry and the steps open to this caller, kept apart: the steps
     * are computed per caller and are not values of the entry, so they travel
     * beside it here and beside {@code fields} on the wire.
     */
    public record Answer(EntryView entry, List<NextStep> next) {
    }

    /**
     * The values {@code create} takes.
     *
     * <p>The key is among them and the scope is not. The key is stored on the
     * entry; the scope is where the entry stands and arrives as part of the
     * address.
     */
    public record NewEntry(String key, String type, String content, String reference) {
    }
}
