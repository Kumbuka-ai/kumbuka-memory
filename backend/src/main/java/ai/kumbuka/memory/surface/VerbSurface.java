/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Actor;
import ai.kumbuka.memory.domain.DigestService;
import ai.kumbuka.memory.domain.DigestView;
import ai.kumbuka.memory.domain.Draft;
import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.domain.Page;
import ai.kumbuka.memory.domain.PageCursor;
import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.domain.Patch;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.domain.SortKey;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The six verbs, once, for every protocol that reaches them.
 *
 * <h2>Why this layer exists at all</h2>
 *
 * Two protocols reach it: REST, which the router calls, and MCP, which an
 * assistant calls directly where there is no router in front of the service.
 * Both call these methods and no others, so a verb behaves the same whichever
 * surface it was called through. What the layer also earns is that the
 * address grammar, the predicate vocabulary and the next-step table are not in
 * a resource class, where they would be indistinguishable from the concerns of
 * one protocol and would move with the next change to it.
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
     * The arguments {@code query} takes beside its address, and the whole of
     * them.
     *
     * <p>An unknown one is refused by name rather than dropped. A dropped
     * filter answers with the full set and looks exactly like a correct narrow
     * one, which is the failure a caller cannot see and cannot ask about. It is
     * {@code ARGUMENT_UNKNOWN} and not {@code PREDICATE_UNKNOWN}: this service
     * declares no filter fields per scope, so a name it does not know is an
     * argument the call does not have, decidable without a scope (DEC-0042).
     */
    private static final String DIRECTION = "direction";

    private static final List<String> ARGUMENTS = List.of("type", "text", PageCursor.AFTER,
        "sort", DIRECTION, PageRequest.PAGE_SIZE);

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
    public Page query(Actor actor, String scope, String selector,
                      Map<String, String> asked) {
        if (selector != null) {
            // Checked even though nothing is addressed by it: a caller that
            // mistyped a selector would otherwise get an empty page, which
            // reads as "there is nothing of that kind here".
            AddressParser.parse(scope, selector, "x");
        }
        refuseUnknown(asked);
        return memory.query(actor, scope, selector, filterOf(asked), pageOf(asked));
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
            "Read the entry again to get its current state and a fresh conflict token."));

        if (entry.writable()) {
            next.add(new NextStep("update " + address,
                "Change the content, the type or the reference, sending the conflict token."));
            next.add(new NextStep("withdraw " + address,
                "Take the entry out of force, sending the conflict token."));
        }
        return List.copyOf(next);
    }

    // ======================================================================
    // The predicates
    // ======================================================================

    /**
     * Refuses a name the query does not declare, answering with the names it
     * does declare and never with the one that arrived (DEC-0043 R19).
     */
    private static void refuseUnknown(Map<String, String> asked) {
        if (!ARGUMENTS.containsAll(asked.keySet())) {
            throw MemoryException.undeclared(
                "an argument outside the declared ones. An unknown one is refused rather "
                    + "than ignored: a filter that is silently dropped answers with the "
                    + "whole set and looks exactly like a correct narrow one.",
                ARGUMENTS);
        }
    }

    private static QueryFilter filterOf(Map<String, String> asked) {
        return new QueryFilter(typeOf(asked.get("type")), blankToNull(asked.get("text")));
    }

    private static PageRequest pageOf(Map<String, String> asked) {
        return new PageRequest(
            blankToNull(asked.get(PageCursor.AFTER)),
            sortOf(asked.get("sort")),
            directionOf(asked.get(DIRECTION)),
            pageSizeOf(asked.get(PageRequest.PAGE_SIZE)));
    }

    /**
     * An order, refused by name when this verb does not declare it.
     *
     * <p>Two orders, and no field beyond them, on either surface. An order
     * over an undeclared field is refused rather than replaced by the
     * default: a listing silently read in another order is one a caller
     * continues believing it knows where it stands.
     */
    private static SortKey sortOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return PageRequest.DEFAULT_SORT;
        }
        return SortKey.of(asked).orElseThrow(() -> MemoryException.offending(
            MemoryException.Reason.SORT_UNDECLARED,
            "'sort' is one of " + SortKey.wireNames() + "; an order over a creation moment "
                + "always carries the key as its second key. Send one of them.",
            List.of("sort")));
    }

    /**
     * A direction, refused when it is neither of the two.
     *
     * <p>{@code ARGUMENT_INVALID} rather than {@code SORT_UNDECLARED}: the
     * two directions are a fixed value set of the argument, not an order a
     * scope declares (ADR-0049).
     */
    private static SortKey.Direction directionOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return PageRequest.DEFAULT_DIRECTION;
        }
        return SortKey.Direction.of(asked).orElseThrow(() -> MemoryException.offending(
            MemoryException.Reason.ARGUMENT_INVALID,
            "'direction' is one of " + SortKey.Direction.wireNames() + ". Send one of them.",
            List.of(DIRECTION)));
    }

    private static EntryType typeOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return null;
        }
        return EntryType.of(asked).orElseThrow(() -> MemoryException.offending(
            MemoryException.Reason.TYPE_UNKNOWN,
            "'type' is one of the six kinds of entry this service carries: "
                + String.join(", ", EntryType.wireNames()) + ". Send one of them.",
            List.of("type")));
    }

    private static int pageSizeOf(String asked) {
        if (asked == null || asked.isBlank()) {
            return PageRequest.DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(asked.trim());
        } catch (NumberFormatException notANumber) {
            throw PageRequest.pageSizeRejected();
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
