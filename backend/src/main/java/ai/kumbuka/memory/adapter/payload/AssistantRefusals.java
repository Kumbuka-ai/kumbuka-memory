/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.surface.ArgumentName;
import ai.kumbuka.memory.surface.ArgumentPath;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.AssistantVerb.Names;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import ai.kumbuka.memory.surface.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every refusal of the assistant surface, in the envelope of DEC-0042, and the
 * message of every refusal of the generic one.
 *
 * <h2>What a message is made of</h2>
 *
 * The reason's message and remedy from the bundles the catalogue names, filled
 * with facts of the placeholder types the declaration builds: the call, the
 * argument a refusal concerns, its position, the names the call declares, and
 * the service's bounds. Never a value the caller sent: a refusal is logged and
 * a value can be content (DEC-0042, DEC-0043 R18). A refusal raised in the
 * domain carries no sentence at all, only its reason and the names of the
 * arguments it concerns, and a name is turned into a placeholder only by
 * finding it in the call's declaration.
 *
 * <h2>What {@code data} carries</h2>
 *
 * {@code attempted} on every refusal. On a refusal of a call's arguments also
 * {@code offenders}, naming the arguments or their positions and never their
 * values (DEC-0042, DEC-0043 R19); on a refusal of an undeclared name, or of a
 * value outside a fixed set, {@code accepts}, the names or values declared.
 * Where the refusal concerns an entry this caller may see, also its
 * {@code state} and {@code next}: the calls the remedy names, each kept only
 * if it would succeed for this caller, judged from the same write right that
 * decides an answer's {@code next}. A stale conflict token additionally
 * carries the entry as this caller's own read would answer it (DEC-0041).
 * {@code NOT_FOUND} carries {@code attempted} and nothing else, so that its
 * three causes cannot be told apart.
 *
 * <h2>Two ways in</h2>
 *
 * The service's own MCP adapter, and the generic surface called with
 * {@code Kumbuka-Surface: assistant} by a hop that publishes these calls as
 * assistant tools. Both build the refusal here, so the two cannot come to
 * word one differently.
 */
public final class AssistantRefusals {

    private static final String ATTEMPTED = "attempted";
    private static final String STATE = "state";
    private static final String NEXT = "next";
    private static final String REPORT = "report";

    /** An offender as the domain names it: an argument, or one of its elements. */
    private static final Pattern OFFENDER = Pattern.compile("([a-z_]+)(?:\\[(\\d+)])?");

    private AssistantRefusals() {
    }

    // ======================================================================
    // The three sources of a refusal
    // ======================================================================

    /** A refusal the argument check made. */
    public static Payloads.Refusal of(AssistantVerb verb, CallException e,
                                      Optional<EntryView> seen) {
        ReasonCatalogue.Reason reason = ReasonCatalogue.of(e.reason().name());
        ReasonCatalogue.Facts facts = ReasonCatalogue.Facts.of(verb.verbName())
            .accepting(e.accepts());
        facts = e.name().map(facts::naming).orElse(facts);
        facts = e.path().map(facts::at).orElse(facts);
        Payloads.Refusal refusal = envelope(verb, reason, reason.text(facts), seen);
        e.path().ifPresent(path ->
            refusal.data().put(MemoryException.OFFENDERS, List.of(path.value())));
        if (e.reason() == CallException.Reason.ARGUMENT_UNKNOWN) {
            refusal.data().put(MemoryException.ACCEPTS,
                e.accepts().stream().map(ArgumentName::value).toList());
        }
        return refusal;
    }

    /**
     * A refusal the service made.
     *
     * @param reason the catalogue entry for its code, already known to be one
     *               this surface can return
     */
    public static Payloads.Refusal of(AssistantVerb verb, MemoryException e,
                                      ReasonCatalogue.Reason reason, Optional<EntryView> seen) {
        if (ReasonCatalogue.NOT_FOUND.equals(reason.code())) {
            return new Payloads.Refusal(reason.code(), notFound(),
                Map.of(ATTEMPTED, verb.call()));
        }
        Payloads.Refusal refusal = envelope(verb, reason, messageFor(verb, e, reason), seen);
        if (e.data().get("fields") instanceof EntryView current) {
            refusal.data().put(MemoryException.ADDRESS, current.address().canonical());
            refusal.data().put(Names.CONFLICT_TOKEN, current.conflictToken());
            refusal.data().put(AssistantVerb.FIELDS, Payloads.EntryFields.of(current));
        }
        if (e.data().get(MemoryException.OFFENDERS) instanceof List<?> offenders) {
            refusal.data().put(MemoryException.OFFENDERS, List.copyOf(offenders));
        }
        if (e.data().get(MemoryException.ACCEPTS) instanceof List<?> accepts) {
            refusal.data().put(MemoryException.ACCEPTS, List.copyOf(accepts));
        }
        return refusal;
    }

    /**
     * The message of a refusal the service made, on either surface: the
     * reason's message and remedy, filled with facts of the declaration's own.
     *
     * <p>The generic surface answers with this message too. One wording per
     * reason means the two surfaces cannot come to say different things.
     */
    public static String messageFor(AssistantVerb verb, MemoryException e,
                                    ReasonCatalogue.Reason reason) {
        ReasonCatalogue.Facts facts = ReasonCatalogue.Facts.of(verb.verbName());
        if (e.data().get(MemoryException.OFFENDERS) instanceof List<?> offenders
                && !offenders.isEmpty()) {
            Optional<ArgumentPath> path = pathOf(verb, String.valueOf(offenders.get(0)));
            if (path.isPresent()) {
                facts = facts.at(path.get());
            }
        }
        if (e.reason() == MemoryException.Reason.ARGUMENT_UNKNOWN
                && e.data().get(MemoryException.ACCEPTS) instanceof List<?> accepts) {
            facts = facts.accepting(accepts.stream()
                .map(name -> declared(verb, String.valueOf(name)))
                .toList());
        }
        return reason.text(facts);
    }

    /**
     * The message of a refusal of the transport. Raised before a verb is
     * known, so its texts name none.
     */
    public static String messageFor(ReasonCatalogue.Reason reason) {
        return reason.text(ReasonCatalogue.Facts.none());
    }

    /** The one message the not-found class carries, on every surface. */
    public static String notFound() {
        return ReasonCatalogue.of(ReasonCatalogue.NOT_FOUND)
            .text(ReasonCatalogue.Facts.none());
    }

    /**
     * A header that names the surface with a value it cannot take.
     *
     * <p>No {@code data}: the header is not an argument of the call, so there
     * is no argument for {@code offenders} to name, and the value sent is the
     * caller's input and is repeated nowhere (DEC-0042).
     */
    public static Payloads.Refusal headerRefused() {
        return new Payloads.Refusal(MemoryException.Reason.ARGUMENT_INVALID.name(),
            Texts.refusals().surfaceHeader() + " " + Texts.remedies().surfaceHeader(),
            new LinkedHashMap<>());
    }

    /**
     * A failure nobody foresaw, already logged under {@code reportId}. The
     * reference travels under {@code data.report}, and the message points at
     * it: a reference is no placeholder a text may take.
     */
    public static Payloads.Refusal unexpected(AssistantVerb verb, String reportId) {
        ReasonCatalogue.Reason reason =
            ReasonCatalogue.of(CallException.Reason.UNEXPECTED_FAILURE.name());
        Payloads.Refusal refusal = new Payloads.Refusal(reason.code(),
            reason.text(ReasonCatalogue.Facts.of(verb.verbName())), new LinkedHashMap<>());
        refusal.data().put(ATTEMPTED, verb.call());
        refusal.data().put(REPORT, reportId);
        return refusal;
    }

    // ======================================================================
    // The envelope
    // ======================================================================

    private static Payloads.Refusal envelope(AssistantVerb verb, ReasonCatalogue.Reason reason,
                                             String message, Optional<EntryView> seen) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, verb.call());
        seen.ifPresent(entry -> {
            data.put(STATE, entry.state());
            data.put(NEXT, Payloads.NextPayload.of(remedyOn(verb, reason, entry)));
        });
        return new Payloads.Refusal(reason.code(), message, data);
    }

    /** The calls the remedy names that this caller can make on this entry. */
    private static List<NextStep> remedyOn(AssistantVerb attempted,
                                           ReasonCatalogue.Reason reason, EntryView entry) {
        List<AssistantVerb> named = new ArrayList<>(reason.steps());
        if (reason.repeat()) {
            named.add(attempted);
        }
        return named.stream()
            .filter(verb -> SurfaceDeclaration.openOn(verb, entry))
            .map(SurfaceDeclaration::step)
            .toList();
    }

    // ======================================================================
    // From the domain's names to the declaration's
    // ======================================================================

    /**
     * The position an offender names, built from the call's declaration; empty
     * where the call declares no argument by that name. The domain spells an
     * offender as the argument's name, or as the name and an element's
     * position; neither half is ever a value the caller sent.
     */
    private static Optional<ArgumentPath> pathOf(AssistantVerb verb, String offender) {
        Matcher spelled = OFFENDER.matcher(offender);
        if (!spelled.matches()) {
            return Optional.empty();
        }
        return verb.declaredName(spelled.group(1)).map(name -> spelled.group(2) == null
            ? ArgumentPath.of(name)
            : ArgumentPath.at(name, Integer.parseInt(spelled.group(2))));
    }

    /** A name the call declares, as the declaration holds it; any other is a defect. */
    private static ArgumentName declared(AssistantVerb verb, String spelled) {
        return verb.declaredName(spelled).orElseThrow(() -> new IllegalStateException(
            "the domain lists a name " + verb.call() + " does not declare"));
    }

    public static boolean takesAddress(AssistantVerb verb) {
        return verb.topArguments().stream().anyMatch(a -> Names.ADDRESS.equals(a.name()));
    }
}
