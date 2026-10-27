/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException.Reason;
import ai.kumbuka.memory.domain.NextStep;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The service as one declaration: its calls, its reasons, and the next steps
 * an answer offers (ADR-0039).
 *
 * <p>Served whole on both surfaces -- {@code GET /api} on the
 * generic one, which is where the router reads it, and
 * {@code GET /mcp/declaration} -- so that what the service carries can be read
 * without calling it, and checked at start, so that a declaration nobody could
 * serve is never served.
 *
 * <h2>What it carries per verb</h2>
 *
 * Its identity {@code memory_<verb>}; its effect class, in the router's own
 * names ({@link EffectClass}), with the address depth it acts at and any
 * further reach; its arguments with type, requiredness, default, the values a
 * fixed set holds with a description each, and bounds; the shape of its
 * answer; the reasons a refusal of it may name; and its texts, taken from the
 * message bundles. The reasons themselves are declared once, beside the calls,
 * with their message and remedy as the bundles write them.
 *
 * <h2>Versioned by its content</h2>
 *
 * {@code version} is a SHA-256 over everything else the declaration carries,
 * so that any change -- a text, an argument, a reason -- yields a new version
 * and an unchanged declaration keeps its own, which is what a reader that
 * records which version it served can rely on.
 */
public final class SurfaceDeclaration {

    private static final String DESCRIPTION = "description";

    /** Changes when the shape of {@link #asMap()} changes, not when its content does. */
    public static final String SHAPE_VERSION = "2";

    private SurfaceDeclaration() {
    }

    public static List<AssistantVerb> calls() {
        return List.of(AssistantVerb.values());
    }

    // ======================================================================
    // The next steps
    // ======================================================================

    /**
     * The calls this caller can make successfully on this entry (contract
     * section 6).
     *
     * <p>Read off the write right the platform's read contract gave for the
     * entry's scope at the moment of the answer, carried on the projection —
     * the same answer that permits and refuses the calls. {@code memory_read}
     * is always here, because an answer about an entry is proof that this
     * caller may read it.
     */
    public static List<NextStep> nextFor(EntryView entry) {
        List<NextStep> next = new ArrayList<>();
        next.add(step(AssistantVerb.READ));
        if (entry.writable()) {
            next.add(step(AssistantVerb.UPDATE));
            next.add(step(AssistantVerb.WITHDRAW));
        }
        return List.copyOf(next);
    }

    /**
     * The one call open after a withdrawal.
     *
     * @param released whether the address is free again, read back after the
     *                 act by the domain rather than inferred from the outcome
     */
    public static List<NextStep> nextAfter(boolean released) {
        return List.of(released
            ? new NextStep(AssistantVerb.CREATE.call(), Texts.next().afterRelease())
            : new NextStep(AssistantVerb.READ.call(), Texts.next().afterRetirement()));
    }

    /**
     * Whether a call would succeed for the caller an entry was projected for,
     * judged from the same write right {@link #nextFor} reads.
     */
    public static boolean openOn(AssistantVerb verb, EntryView entry) {
        return switch (verb) {
            case READ, QUERY, DIGEST -> true;
            case UPDATE, WITHDRAW -> entry.writable();
            // The entry stands, so its address is taken.
            case CREATE -> false;
        };
    }

    public static NextStep step(AssistantVerb verb) {
        return new NextStep(verb.call(), verb.does());
    }

    // ======================================================================
    // The served form
    // ======================================================================

    public static Map<String, Object> asMap() {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("shape_version", SHAPE_VERSION);
        declaration.put("service", "memory");
        declaration.put("scheme", "memory");
        declaration.put("address_form", "memory://<scope>/<selector>/<id>");
        declaration.put("calls", callsAsMaps());
        declaration.put("reasons", reasonsAsMaps());
        declaration.put("answered_as", collapsed());
        Map<String, Object> versioned = new LinkedHashMap<>();
        versioned.put("version", versionOf(declaration));
        versioned.putAll(declaration);
        return versioned;
    }

    private static List<Map<String, Object>> callsAsMaps() {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (AssistantVerb verb : AssistantVerb.values()) {
            Form form = formOf(verb);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("identity", verb.call());
            call.put("call", verb.call());
            call.put("effect_class", form.effect().name());
            call.put("depth", form.depth().name());
            EffectClass atScopeDepth = form.scopeDepth();
            if (atScopeDepth != null) {
                call.put("at_scope_depth", atScopeDepth.name());
            }
            if (form.technicalAddress()) {
                call.put("technical_address", true);
            }
            call.put(DESCRIPTION, verb.description());
            call.put("does", verb.does());
            List<Map<String, Object>> arguments = new ArrayList<>();
            if (verb.writesFields()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("name", AssistantVerb.FIELDS);
                fields.put("type", "object");
                fields.put("required", true);
                fields.put("placement", "top");
                fields.put(DESCRIPTION, AssistantVerb.fieldsDescription());
                arguments.add(fields);
            }
            verb.arguments().stream().map(SurfaceDeclaration::argumentAsMap)
                .forEach(arguments::add);
            call.put("arguments", arguments);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("shape", form.answer().name());
            answer.put("members", form.answer().members());
            call.put("answer", answer);
            call.put("refusals", form.refusals());
            calls.add(call);
        }
        return calls;
    }

    private static Map<String, Object> argumentAsMap(Argument argument) {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("name", argument.name());
        declared.put("type", argument.type());
        declared.put("required", argument.required());
        declared.put("placement", argument.placement().name().toLowerCase(Locale.ROOT));
        declared.put(DESCRIPTION, argument.description());
        Object fallback = argument.keywords().get("default");
        if (fallback != null) {
            declared.put("default", fallback);
        }
        List<String> values = valuesOf(argument);
        if (!values.isEmpty()) {
            List<Map<String, Object>> described = new ArrayList<>();
            for (String value : values) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("value", value);
                one.put(DESCRIPTION, valueDescription(argument.name(), value));
                described.add(one);
            }
            declared.put("values", described);
        }
        for (String bound : List.of("minimum", "maximum", "minItems")) {
            if (argument.keywords().containsKey(bound)) {
                declared.put(bound, argument.keywords().get(bound));
            }
        }
        return declared;
    }

    /** The fixed set an argument's values come from, or of its elements for a list. */
    @SuppressWarnings("unchecked")
    private static List<String> valuesOf(Argument argument) {
        Object values = argument.keywords().get("enum");
        if (values == null && argument.keywords().get("items") instanceof Map<?, ?> items) {
            values = items.get("enum");
        }
        return values instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** What one value of a fixed set means, from its bundle ({@link ValueDescriptions}). */
    private static String valueDescription(String argument, String value) {
        ValueDescriptions texts = Texts.values();
        return switch (argument + "=" + value) {
            case "sort=id" -> texts.sortId();
            case "sort=created_at" -> texts.sortCreatedAt();
            case "direction=asc" -> texts.directionAsc();
            case "direction=desc" -> texts.directionDesc();
            default -> switch (value) {
                case "constraint" -> texts.typeConstraint();
                case "decision" -> texts.typeDecision();
                case "convention" -> texts.typeConvention();
                case "glossary" -> texts.typeGlossary();
                case "status" -> texts.typeStatus();
                case "open_question" -> texts.typeOpenQuestion();
                default -> throw new IllegalStateException(argument + " declares a value "
                    + "that has no description in the value bundle");
            };
        };
    }

    private static List<Map<String, Object>> reasonsAsMaps() {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("reason", reason.code());
            declared.put("message_pattern",
                reason.messageTemplate() + " " + reason.remedyTemplate());
            declared.put("remedy", reason.remedyTemplate());
            declared.put("steps", reason.steps().stream().map(AssistantVerb::call).toList());
            declared.put("surface", reason.reach() == ReasonCatalogue.Reach.BOTH
                ? "both" : "generic");
            reasons.add(declared);
        }
        return reasons;
    }

    // ======================================================================
    // The form of each verb
    // ======================================================================

    /** The reasons every call can be refused with, whatever it does. */
    private static final List<String> EVERY_CALL = List.of("NOT_FOUND", "ACTOR_UNKNOWN",
        "ARGUMENT_UNKNOWN", "ARGUMENT_MISSING", "ARGUMENT_INVALID", "UNEXPECTED_FAILURE",
        "SESSION_NOT_BOUND", "VERB_NOT_CARRIED", "ADDRESS_TRUNCATED");

    /**
     * What the router needs of a verb beside its arguments: its effect class
     * and depth, the further address forms it reaches, the shape of its
     * answer, and the reasons it can be refused with.
     *
     * @param scopeDepth       the class it acts with on a scope address, where
     *                         it reaches one beyond its own depth
     * @param technicalAddress whether {@code memory://<uuid>} addresses an
     *                         entry for it
     */
    public record Form(EffectClass effect, AddressDepth depth, @Nullable EffectClass scopeDepth,
                       boolean technicalAddress, AnswerShape answer, List<String> refusals) {
    }

    public static Form formOf(AssistantVerb verb) {
        return switch (verb) {
            case CREATE -> new Form(EffectClass.CREATE, AddressDepth.COLLECTION, null, false,
                AnswerShape.ENTRY_CREATED, refusals(WRITE, ADDRESSED, List.of(
                    Reason.SELECTOR_ABSENT, Reason.SELECTOR_MISMATCHED, Reason.ALREADY_EXISTS),
                    VALUES, BODY));
            case READ -> new Form(EffectClass.ITEM_READ, AddressDepth.ITEM, null, true,
                AnswerShape.ENTRY, refusals(ADDRESSED));
            case UPDATE -> new Form(EffectClass.FIELD_WRITE, AddressDepth.ITEM, null, false,
                AnswerShape.ENTRY, refusals(WRITE, ADDRESSED, TOKEN, List.of(Reason.UPDATE_EMPTY),
                    VALUES, BODY));
            case WITHDRAW -> new Form(EffectClass.ITEM_TRANSITION, AddressDepth.ITEM, null,
                false, AnswerShape.WITHDRAWN, refusals(WRITE, ADDRESSED, TOKEN));
            case QUERY -> new Form(EffectClass.COLLECTION_READ, AddressDepth.COLLECTION,
                EffectClass.SCOPE_READ, false, AnswerShape.ENTRY_LISTING,
                refusals(ADDRESSED, List.of(Reason.TYPE_UNKNOWN, Reason.PAGE_SIZE_REJECTED,
                    Reason.CURSOR_MALFORMED, Reason.CURSOR_FOREIGN, Reason.SORT_UNDECLARED)));
            case DIGEST -> new Form(EffectClass.SCOPE_COMPOUND, AddressDepth.SCOPE, null, false,
                AnswerShape.DIGEST_VIEW, refusals(List.of(Reason.ADDRESS_MALFORMED, Reason.TYPE_UNKNOWN),
                    BODY));
        };
    }

    // The reason groups the forms above are put together from.
    private static final List<Enum<?>> WRITE = List.of(Reason.SCOPE_LOCKED, Reason.SCOPE_READ_ONLY,
        Reason.SELECTOR_RESERVED);
    private static final List<Enum<?>> ADDRESSED = List.of(Reason.ADDRESS_MALFORMED,
        Reason.KEY_MALFORMED);
    private static final List<Enum<?>> TOKEN = List.of(Reason.CONFLICT_TOKEN_MISSING,
        Reason.CONFLICT_TOKEN_STALE);
    private static final List<Enum<?>> VALUES = List.of(Reason.TYPE_UNKNOWN, Reason.CONTENT_ABSENT,
        Reason.CONTENT_OVERSIZE, Reason.REFERENCE_CREDENTIAL_BEARING, Reason.FIELD_IMMUTABLE);
    private static final List<Enum<?>> BODY =
        List.of(SurfaceException.Reason.PAYLOAD_MALFORMED);

    /** The reasons a verb names, beside those of every call, sorted by code. */
    @SafeVarargs
    private static List<String> refusals(List<? extends Enum<?>>... groups) {
        Set<String> all = new TreeSet<>(EVERY_CALL);
        for (List<? extends Enum<?>> group : groups) {
            group.forEach(reason -> all.add(reason.name()));
        }
        return List.copyOf(all);
    }

    // ======================================================================
    // The version
    // ======================================================================

    /**
     * A SHA-256 over the declaration written in one canonical form: keys of
     * an object sorted, lists in their order, every scalar as its text.
     */
    static String versionOf(Map<String, Object> declaration) {
        StringBuilder canonical = new StringBuilder();
        canonical(declaration, canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("the runtime carries no SHA-256", absent);
        }
    }

    private static void canonical(@Nullable Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            map.keySet().stream().map(String::valueOf).sorted().forEach(key -> {
                out.append(quoted(key)).append(':');
                canonical(map.get(key), out);
                out.append(',');
            });
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            list.forEach(element -> {
                canonical(element, out);
                out.append(',');
            });
            out.append(']');
        } else if (value instanceof String text) {
            out.append(quoted(text));
        } else {
            out.append(value);
        }
    }

    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The raised reasons that are answered under another code. */
    private static Map<String, String> collapsed() {
        Map<String, String> collapsed = new LinkedHashMap<>();
        for (String raised : ReasonCatalogue.raisable()) {
            String code = ReasonCatalogue.wireCode(raised);
            if (!code.equals(raised)) {
                collapsed.put(raised, code);
            }
        }
        return collapsed;
    }

    // ======================================================================
    // The check at start
    // ======================================================================

    /**
     * Refuses a declaration that could not be served as stated.
     *
     * @param catalogue the reason catalogue to check, so that a start can be
     *                  observed refusing an incomplete one
     * @throws IllegalStateException naming what is wrong
     */
    public static void requireServable(Map<String, ReasonCatalogue.Reason> catalogue) {
        ReasonCatalogue.requireComplete(catalogue);
        Set<String> seen = new HashSet<>();
        for (AssistantVerb verb : AssistantVerb.values()) {
            if (!seen.add(verb.call())) {
                throw new IllegalStateException(
                    "two calls are declared as " + verb.call() + "; a tool list is flat");
            }
            if (verb.description() == null || verb.description().isBlank()) {
                throw new IllegalStateException(verb.call() + " is declared without a "
                    + "description, and the description is all a caller has to go on");
            }
            requireDescribedArguments(verb.call(), verb.arguments());
            List<String> undeclared = formOf(verb).refusals().stream()
                .filter(code -> !catalogue.containsKey(code))
                .toList();
            if (!undeclared.isEmpty()) {
                throw new IllegalStateException(verb.call() + " is declared to refuse with "
                    + undeclared + ", which the catalogue does not declare");
            }
        }
        // Built once, so that a text or a value description it cannot find
        // stops the start rather than the first reader of the declaration.
        asMap();
    }

    /**
     * Refuses a call with no arguments, or an argument with no description.
     *
     * <p>A call with none would have an empty closed schema and nothing for
     * the undeclared-argument refusal to name instead; an argument with no
     * description leaves the missing-argument refusal unable to say what the
     * value is.
     */
    public static void requireDescribedArguments(String call, List<Argument> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalStateException(call + " is declared with no arguments");
        }
        for (Argument argument : arguments) {
            if (argument.description() == null || argument.description().isBlank()) {
                throw new IllegalStateException(
                    call + " declares " + argument.name() + " without a description");
            }
        }
    }
}
