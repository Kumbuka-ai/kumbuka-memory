/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Every reason this service can refuse with, each with its message and its
 * remedy (DEC-0043).
 *
 * <h2>The texts are in the bundles, and this table says which belongs where</h2>
 *
 * A reason's message is the text of {@link RefusalMessages} and its remedy the
 * text of {@link RefusalRemedies}, both under the reason's code in lower camel
 * case. What this table holds is the mapping from a reason to those two
 * methods, with the facts each is filled with. A sentence produced inside the
 * service never reaches a caller, in whole or in part: the domain's refusals
 * carry no sentence at all, and a fact is one of the placeholder types the
 * declaration builds -- a call, an argument, a position, a bound -- never a
 * value the caller sent (DEC-0043 R18).
 *
 * <h2>Steps are named in the vocabulary of the surface</h2>
 *
 * A remedy never names a call literally. It names a step -- {@code {read}},
 * {@code {update}} -- and the step is filled with the call's {@link VerbName}.
 *
 * <h2>Complete, or the service does not start</h2>
 *
 * {@link #requireComplete} walks every reason the service can raise -- the
 * domain's, the generic surface's and this surface's own -- and refuses when
 * one has no entry here, or when an entry's texts cannot be built. A reason
 * not in the table could only be worded by improvising, and an improvised
 * refusal is the thing the table exists to prevent; so the service refuses to
 * start instead.
 */
public final class ReasonCatalogue {

    /** The one code the not-found class carries on every surface. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** The bundle names, which are the names of their files under messages/. */
    public static final String MESSAGE_BUNDLE = "refusal";
    public static final String REMEDY_BUNDLE = "remedy";

    private static final Map<String, Reason> DECLARED = declare();

    private ReasonCatalogue() {
    }

    /** Where a reason can be returned. */
    public enum Reach {

        /** On the assistant surface, and on the generic one wherever it arises there. */
        BOTH,

        /**
         * On the generic surface only. Declared all the same, so that the
         * catalogue covers every reason the service can raise; arriving on the
         * assistant surface it is a defect, and is answered as one.
         */
        GENERIC
    }

    /**
     * What a refusal's texts are filled with. Every member is a placeholder
     * type the declaration builds; a member a text needs and the refusal did
     * not supply refuses to build, because a half-filled message reads as
     * finished, which is worse than none.
     *
     * <p>A member is absent where the refusal does not concern it; its
     * accessor refuses to answer then, for the reason above.
     *
     * @param call      the call refused; absent for a refusal of the transport
     * @param name      the argument it concerns, where it concerns one
     * @param path      the position of the value it concerns, where it concerns one
     * @param arguments the names the call declares, where an undeclared one arrived
     */
    public record Facts(@Nullable VerbName call, @Nullable ArgumentName name,
                        @Nullable ArgumentPath path, @Nullable List<ArgumentName> arguments) {

        public static Facts of(VerbName call) {
            return new Facts(call, null, null, null);
        }

        /** The facts of a transport refusal, raised before a call is known. */
        public static Facts none() {
            return new Facts(null, null, null, null);
        }

        public Facts naming(ArgumentName argument) {
            return new Facts(call, argument, path, arguments);
        }

        public Facts at(ArgumentPath position) {
            return new Facts(call, name, position, arguments);
        }

        public Facts accepting(List<ArgumentName> declared) {
            return new Facts(call, name, path, List.copyOf(declared));
        }

        @Override
        public VerbName call() {
            return required(call, "call");
        }

        /** The call refused, where the request had become one. */
        public Optional<VerbName> callIfKnown() {
            return Optional.ofNullable(call);
        }

        @Override
        public ArgumentName name() {
            return required(name, "name");
        }

        @Override
        public ArgumentPath path() {
            return required(path, "path");
        }

        @Override
        public List<ArgumentName> arguments() {
            return required(arguments, "arguments");
        }

        private static <T> T required(@Nullable T fact, String what) {
            if (fact == null) {
                throw new IllegalStateException("a refusal text needs the fact " + what
                    + ", and the refusal supplied none");
            }
            return fact;
        }
    }

    /**
     * One declared reason.
     *
     * @param code    the code on the wire
     * @param steps   the calls the remedy names, in order; listed in
     *                {@code data.next} where the refusal concerns an entry
     *                the caller may see
     * @param repeat  whether the remedy is to make the same call again,
     *                corrected
     * @param reach   where the reason can be returned
     * @param message what went wrong, from {@link RefusalMessages}
     * @param remedy  what the caller does next, from {@link RefusalRemedies};
     *                the message ends with it
     * @param further the keys of the further messages {@code message} chooses
     *                from, beside the reason's own key, declared with it
     */
    public record Reason(String code, List<AssistantVerb> steps, boolean repeat, Reach reach,
                         Function<Facts, String> message, Function<Facts, String> remedy,
                         List<String> further) {

        public Reason {
            steps = List.copyOf(steps);
            further = List.copyOf(further);
        }

        public Reason(String code, List<AssistantVerb> steps, boolean repeat, Reach reach,
                      Function<Facts, String> message, Function<Facts, String> remedy) {
            this(code, steps, repeat, reach, message, remedy, List.of());
        }

        /** The key both bundles carry this reason's texts under. */
        public String key() {
            return keyOf(code);
        }

        /** The whole message: what went wrong, then what to do. */
        public String text(Facts facts) {
            return message.apply(facts) + " " + remedy.apply(facts);
        }

        /** The message as its source writes it, placeholders standing. */
        public String messageTemplate() {
            return Texts.template(MESSAGE_BUNDLE, key());
        }

        /** The remedy as its source writes it, placeholders standing. */
        public String remedyTemplate() {
            return Texts.template(REMEDY_BUNDLE, key());
        }
    }

    /** {@code CONTENT_OVERSIZE} as {@code contentOversize}. */
    public static String keyOf(String code) {
        String[] parts = code.toLowerCase(Locale.ROOT).split("_");
        StringBuilder key = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return key.toString();
    }

    /**
     * The catalogue.
     *
     * <p>Every remedy is the act to perform, phrased as what to do. A caller
     * that is a model carries out an instruction more reliably than it
     * inverts a prohibition (DEC-0042, DEC-0043 R21).
     */
    private static Map<String, Reason> declare() {
        Map<String, Reason> declared = new LinkedHashMap<>();
        VerbName read = AssistantVerb.READ.verbName();
        VerbName query = AssistantVerb.QUERY.verbName();
        VerbName digest = AssistantVerb.DIGEST.verbName();
        VerbName update = AssistantVerb.UPDATE.verbName();

        put(declared, NOT_FOUND, f -> m().notFound(), f -> r().notFound());
        put(declared, "SCOPE_LOCKED", f -> m().scopeLocked(f.call()),
            f -> r().scopeLocked(read, query, digest),
            AssistantVerb.READ, AssistantVerb.QUERY, AssistantVerb.DIGEST);
        put(declared, "SCOPE_READ_ONLY", f -> m().scopeReadOnly(f.call()),
            f -> r().scopeReadOnly(read, query, digest),
            AssistantVerb.READ, AssistantVerb.QUERY, AssistantVerb.DIGEST);
        repeat(declared, "ADDRESS_MALFORMED", f -> m().addressMalformed(f.call()),
            f -> r().addressMalformed());
        repeat(declared, "KEY_MALFORMED", f -> m().keyMalformed(f.call()),
            f -> r().keyMalformed());
        repeat(declared, "SELECTOR_ABSENT", f -> m().selectorAbsent(f.call()),
            f -> r().selectorAbsent());
        repeat(declared, "SELECTOR_RESERVED", f -> m().selectorReserved(),
            f -> r().selectorReserved());
        repeat(declared, "SELECTOR_MISMATCHED", f -> m().selectorMismatched(f.call()),
            f -> r().selectorMismatched());
        put(declared, "ALREADY_EXISTS", f -> m().alreadyExists(f.call()),
            f -> r().alreadyExists(read, update),
            AssistantVerb.READ, AssistantVerb.UPDATE);
        put(declared, "CONFLICT_TOKEN_MISSING", f -> m().conflictTokenMissing(f.call()),
            f -> r().conflictTokenMissing(read), AssistantVerb.READ);
        put(declared, "CONFLICT_TOKEN_STALE", f -> m().conflictTokenStale(f.call()),
            f -> r().conflictTokenStale(read), AssistantVerb.READ);
        repeat(declared, "UPDATE_EMPTY", f -> m().updateEmpty(f.call()),
            f -> r().updateEmpty());
        repeat(declared, "TYPE_UNKNOWN", f -> m().typeUnknown(f.call()),
            f -> r().typeUnknown());
        repeat(declared, "CONTENT_ABSENT", f -> m().contentAbsent(f.call()),
            f -> r().contentAbsent());
        repeat(declared, "CONTENT_OVERSIZE", f -> m().contentOversize(Bound.CONTENT_LIMIT),
            f -> r().contentOversize(Bound.CONTENT_LIMIT));
        repeat(declared, "REFERENCE_CREDENTIAL_BEARING", f -> m().referenceCredentialBearing(),
            f -> r().referenceCredentialBearing());
        repeat(declared, "PAGE_SIZE_REJECTED", f -> m().pageSizeRejected(f.call()),
            f -> r().pageSizeRejected(Bound.PAGE_SIZE_MIN, Bound.PAGE_SIZE_MAX));
        put(declared, "CURSOR_MALFORMED", f -> m().cursorMalformed(query),
            f -> r().cursorMalformed(query), AssistantVerb.QUERY);
        put(declared, "CURSOR_FOREIGN", f -> m().cursorForeign(query),
            f -> r().cursorForeign(query), AssistantVerb.QUERY);
        repeat(declared, "SORT_UNDECLARED", f -> m().sortUndeclared(f.call()),
            f -> r().sortUndeclared());
        put(declared, "ACTOR_UNKNOWN", f -> m().actorUnknown(), f -> r().actorUnknown());
        repeat(declared, "ARGUMENT_UNKNOWN", f -> m().argumentUnknown(f.call(), f.arguments()),
            f -> r().argumentUnknown());
        repeat(declared, "ARGUMENT_MISSING", f -> m().argumentMissing(f.call(), f.name()),
            f -> r().argumentMissing(f.name()));
        repeat(declared, "ARGUMENT_INVALID", f -> m().argumentInvalid(f.call(), f.path()),
            f -> r().argumentInvalid(f.path()));
        // A request that fails before it became a call has no call to name.
        declared.put("UNEXPECTED_FAILURE", new Reason("UNEXPECTED_FAILURE", List.of(), false,
            Reach.BOTH, f -> f.callIfKnown().map(m()::unexpectedFailure)
                .orElseGet(m()::unexpectedFailureOfRequest),
            f -> r().unexpectedFailure(), List.of("unexpectedFailureOfRequest")));

        // --- the generic surface's own -------------------------------------
        // A field fixed for the life of an entry is no argument of any call on
        // the assistant surface, so naming one there is ARGUMENT_UNKNOWN.
        generic(declared, "FIELD_IMMUTABLE", f -> m().fieldImmutable(),
            f -> r().fieldImmutable());
        // The transport reasons are raised before a verb is known, so their
        // texts name none. The surface header is refused before the route is
        // matched, and only the REST surface reads it (C14.4, C14.5).
        generic(declared, "HEADER_INVALID", f -> m().headerInvalid(),
            f -> r().headerInvalid());
        generic(declared, "PAYLOAD_MALFORMED", f -> m().payloadMalformed(),
            f -> r().payloadMalformed());
        generic(declared, "VERB_NOT_CARRIED", f -> m().verbNotCarried(),
            f -> r().verbNotCarried());
        generic(declared, "ADDRESS_TRUNCATED", f -> m().addressTruncated(),
            f -> r().addressTruncated());
        generic(declared, "SESSION_NOT_BOUND", f -> m().sessionNotBound(f.call()),
            f -> r().sessionNotBound());

        return Map.copyOf(declared);
    }

    private static RefusalMessages m() {
        return Texts.refusals();
    }

    private static RefusalRemedies r() {
        return Texts.remedies();
    }

    private static void put(Map<String, Reason> into, String code,
                            Function<Facts, String> message, Function<Facts, String> remedy,
                            AssistantVerb... steps) {
        into.put(code, new Reason(code, List.of(steps), false, Reach.BOTH, message, remedy));
    }

    private static void repeat(Map<String, Reason> into, String code,
                               Function<Facts, String> message, Function<Facts, String> remedy) {
        into.put(code, new Reason(code, List.of(), true, Reach.BOTH, message, remedy));
    }

    private static void generic(Map<String, Reason> into, String code,
                                Function<Facts, String> message,
                                Function<Facts, String> remedy) {
        into.put(code, new Reason(code, List.of(), false, Reach.GENERIC, message, remedy));
    }

    // ======================================================================
    // Reading the catalogue
    // ======================================================================

    /** The catalogue as the service carries it, keyed by code. */
    public static Map<String, Reason> byCode() {
        return DECLARED;
    }

    /** The declared reasons, sorted by code so that the served declaration is stable. */
    public static List<Reason> declared() {
        return DECLARED.values().stream()
            .sorted(Comparator.comparing(Reason::code))
            .toList();
    }

    /** The entry for a code; a code with none is a defect. */
    public static Reason of(String code) {
        Reason reason = DECLARED.get(code);
        if (reason == null) {
            throw new IllegalStateException(code + " is not declared in the reason catalogue");
        }
        return reason;
    }

    /**
     * The code a raised reason is answered with.
     *
     * <p>Identity for every reason but two. An address with nothing behind it
     * and a scope this caller may not enter are different facts in the domain
     * and one code on the wire (DEC-0042), because a caller able to tell them
     * apart could map what it may not see.
     */
    public static String wireCode(String raised) {
        return MemoryException.Reason.ENTRY_ABSENT.name().equals(raised)
            || MemoryException.Reason.SCOPE_UNRESOLVED.name().equals(raised)
            ? NOT_FOUND
            : raised;
    }

    /**
     * Every reason the service can raise, on either surface.
     *
     * <p>Read off the three types that raise them rather than listed here: a
     * list kept beside the enums is a list that stops being complete the day
     * somebody adds a constant, and stops silently.
     */
    public static List<String> raisable() {
        List<String> raised = new ArrayList<>();
        Arrays.stream(MemoryException.Reason.values()).map(Enum::name).forEach(raised::add);
        Arrays.stream(SurfaceException.Reason.values()).map(Enum::name).forEach(raised::add);
        Arrays.stream(CallException.Reason.values()).map(Enum::name).forEach(raised::add);
        return List.copyOf(raised);
    }

    /**
     * Refuses a catalogue that leaves a raisable reason undeclared, or declares
     * one whose message or remedy does not build.
     *
     * <p>Each entry is built once with facts of the declaration's own, so that
     * a text the build accepted and an entry that asks it for a fact it never
     * receives are found here, at start, and not by the first caller refused.
     *
     * @throws IllegalStateException naming every reason that is not covered
     */
    public static void requireComplete(Map<String, Reason> catalogue) {
        List<String> undeclared = raisable().stream()
            .filter(raised -> !catalogue.containsKey(wireCode(raised)))
            .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                "the reason catalogue does not declare " + undeclared + ". A reason the "
                    + "service can raise and the catalogue does not word could only be "
                    + "answered by improvising, so the service does not start.");
        }
        AssistantVerb sample = AssistantVerb.QUERY;
        ArgumentName name = sample.topNames().get(0);
        Facts facts = Facts.of(sample.verbName()).naming(name).at(ArgumentPath.of(name))
            .accepting(sample.topNames());
        for (Reason reason : catalogue.values()) {
            String message;
            String remedy;
            try {
                message = reason.message().apply(facts);
                remedy = reason.remedy().apply(facts);
            } catch (RuntimeException unbuildable) {
                throw new IllegalStateException(reason.code() + " is declared with a text "
                    + "that does not build", unbuildable);
            }
            if (message == null || message.isBlank() || remedy == null || remedy.isBlank()) {
                throw new IllegalStateException(
                    reason.code() + " is declared without a message or without a remedy.");
            }
        }
    }

    // ======================================================================
    // Building a message
    // ======================================================================

    /**
     * The message for a reason: its message and its remedy, filled with the
     * facts of this refusal and nothing else.
     *
     * @throws IllegalStateException when a text needs a fact the refusal did
     *         not supply
     */
    public static String message(Reason reason, Facts facts) {
        return reason.text(facts);
    }
}
