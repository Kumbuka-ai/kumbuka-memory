/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.AssistantVerb.Names;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every refusal of the assistant surface, in the envelope of DEC-0042.
 *
 * <h2>What a message is made of</h2>
 *
 * The reason's pattern and remedy from the catalogue, and values taken from
 * three places only: the call itself (the tool name and the names of its
 * arguments), the structured data a domain refusal carries (an offending
 * argument's name), and the service's published limits and lists. Never a
 * value the caller sent: a refusal is logged and a value can be content
 * (DEC-0042). Never the domain refusal's message either. That sentence was
 * written for the generic surface, names calls in that surface's vocabulary,
 * and may carry what this surface keeps out; so it is not read here at all.
 *
 * <h2>What {@code data} carries</h2>
 *
 * {@code attempted} on every refusal. On a refusal of a call's arguments also
 * {@code offenders}, naming the arguments and never their values (DEC-0042).
 * Where the refusal concerns an entry this
 * caller may see, also its {@code state} and {@code next}: the calls the
 * remedy names, each kept only if it would succeed for this caller, judged
 * from the same write right that decides an answer's {@code next}. A stale
 * conflict token additionally carries the entry as this caller's own read
 * would answer it (DEC-0041). {@code NOT_FOUND} carries {@code attempted}
 * and nothing else, so that its three causes cannot be told apart.
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

    private AssistantRefusals() {
    }

    // ======================================================================
    // The three sources of a refusal
    // ======================================================================

    /** A refusal the argument check made. */
    public static Payloads.Refusal of(AssistantVerb verb, CallException e,
                                      Optional<EntryView> seen) {
        ReasonCatalogue.Reason reason = ReasonCatalogue.of(e.reason().name());
        Map<String, String> values = new LinkedHashMap<>(e.values());
        values.put(ReasonCatalogue.CALL, verb.call());
        Payloads.Refusal refusal =
            envelope(verb, reason, ReasonCatalogue.message(reason, values), seen);
        String name = e.values().get(ReasonCatalogue.NAME);
        if (name != null && e.reason() != CallException.Reason.UNEXPECTED_FAILURE) {
            refusal.data().put(MemoryException.OFFENDERS, List.of(name));
        }
        if (e.reason() == CallException.Reason.ARGUMENT_UNKNOWN) {
            refusal.data().put(MemoryException.ACCEPTS, e.accepts());
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
            return new Payloads.Refusal(reason.code(), ReasonCatalogue.NOT_FOUND_MESSAGE,
                Map.of(ATTEMPTED, verb.call()));
        }
        String message = messageFor(verb, e, reason);
        Payloads.Refusal refusal = envelope(verb, reason, message, seen);
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
     * reason's pattern and remedy, filled from what the service holds.
     *
     * <p>The generic surface answers with this message too. One wording per
     * reason means the two surfaces cannot come to say different things, and
     * the domain's own sentence never reaches a caller on either.
     */
    public static String messageFor(AssistantVerb verb, MemoryException e,
                                    ReasonCatalogue.Reason reason) {
        return ReasonCatalogue.message(reason, valuesFor(verb, e));
    }

    /**
     * The message of a refusal of the transport. Raised before a verb is
     * known, so its pattern names none and is filled with nothing.
     */
    public static String messageFor(ReasonCatalogue.Reason reason) {
        return ReasonCatalogue.message(reason, Map.of());
    }

    /**
     * A header that names the surface with a value it cannot take.
     *
     * <p>No {@code data}: the header is not an argument of the call, so there
     * is no argument for {@code offenders} to name, and the value sent is the
     * caller's input and is repeated nowhere (DEC-0042).
     */
    public static Payloads.Refusal headerRefused(AssistantVerb verb, String header,
                                                 String takes) {
        ReasonCatalogue.Reason reason =
            ReasonCatalogue.of(MemoryException.Reason.ARGUMENT_INVALID.name());
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.CALL, verb.call());
        values.put(ReasonCatalogue.NAME, "the header " + header);
        values.put(ReasonCatalogue.WHY, "it takes the value " + takes + ", sent once");
        return new Payloads.Refusal(reason.code(), ReasonCatalogue.message(reason, values),
            new LinkedHashMap<>());
    }

    /** A failure nobody foresaw, already logged under {@code reportId}. */
    public static Payloads.Refusal unexpected(AssistantVerb verb, String reportId) {
        ReasonCatalogue.Reason reason =
            ReasonCatalogue.of(CallException.Reason.UNEXPECTED_FAILURE.name());
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.CALL, verb.call());
        values.put(ReasonCatalogue.REPORT, reportId);
        Payloads.Refusal refusal = new Payloads.Refusal(reason.code(),
            ReasonCatalogue.message(reason, values), new LinkedHashMap<>());
        refusal.data().put(ATTEMPTED, verb.call());
        refusal.data().put("report", reportId);
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
    // The values a domain refusal's pattern is filled with
    // ======================================================================

    /**
     * Every placeholder the reason's pattern can name, filled from what the
     * service itself holds: the call, the offending argument's name, and the
     * service's limits and lists.
     *
     * <p>Filled generously rather than per reason: a value the pattern does
     * not name is simply not used, while a value it names and is missing makes
     * the message refuse to build — which is the failure worth having.
     */
    private static Map<String, String> valuesFor(AssistantVerb verb, MemoryException e) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.CALL, verb.call());

        Object offenders = e.data().get(MemoryException.OFFENDERS);
        String firstOffender = offenders instanceof List<?> list && !list.isEmpty()
            ? String.valueOf(list.get(0)) : null;
        putIfPresent(values, ReasonCatalogue.FIELD, firstOffender);
        putIfPresent(values, ReasonCatalogue.NAME, firstOffender);
        putIfPresent(values, ReasonCatalogue.WHY, whyOf(verb, firstOffender));
        values.put(ReasonCatalogue.ARGUMENTS, String.join(", ", verb.topNames()));

        values.put(ReasonCatalogue.TYPES, String.join(", ", EntryType.wireNames()));
        values.put(ReasonCatalogue.LIMIT, String.valueOf(MemoryService.CONTENT_LIMIT));
        values.put(ReasonCatalogue.MAX, String.valueOf(PageRequest.MAX_PAGE_SIZE));
        if (e.data().get(MemoryException.ACCEPTS) instanceof List<?> accepts) {
            values.put(ReasonCatalogue.ARGUMENTS, String.join(", ",
                accepts.stream().map(String::valueOf).toList()));
        }
        return values;
    }

    /**
     * What an argument has to be, said from its declaration: the values it
     * takes where it declares a set of them. Null where the declaration says
     * nothing a refusal could repeat.
     */
    private static String whyOf(AssistantVerb verb, String name) {
        return verb.topArguments().stream()
            .filter(argument -> argument.name().equals(name))
            .map(argument -> argument.keywords().get("enum"))
            .filter(List.class::isInstance)
            .map(values -> "it is one of " + String.join(", ",
                ((List<?>) values).stream().map(String::valueOf).toList()))
            .findFirst()
            .orElse(null);
    }

    public static boolean takesAddress(AssistantVerb verb) {
        return verb.topArguments().stream().anyMatch(a -> Names.ADDRESS.equals(a.name()));
    }

    private static void putIfPresent(Map<String, String> values, String name, String value) {
        if (value != null) {
            values.put(name, value);
        }
    }
}
