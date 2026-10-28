/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException.Reason;
import ai.kumbuka.memory.domain.NextStep;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The service as one declaration: its calls, its reasons, and the next steps
 * an answer offers (ADR-0039), in the shape of contract section 5.
 *
 * <p>Served at {@code GET /api/declaration}, with its version as the
 * {@code ETag}, which is where the router reads it (C5.1). Until the router
 * binds it from there, the same declaration is also served at {@code GET /api}
 * and {@code GET /mcp/declaration}, the one exception C5.1 names; both go in
 * memory's breaking step. {@link DeclarationGuard} checks it at start, so that
 * a declaration nobody could serve is never served (C5.6).
 *
 * <h2>What it carries per verb</h2>
 *
 * Its identity {@code memory_<verb>} and its name on the REST surface; its
 * effect class, in the router's own names ({@link EffectClass}), with any
 * further reach; its arguments with type, requiredness, placement, a default
 * in its own JSON type, the values a fixed set holds with a description each,
 * and bounds; the shape of its answer; the reasons a refusal of it may name;
 * and its description, taken from the message bundles. The reasons themselves
 * are declared once, beside the calls, with their messages and remedy as the
 * bundles write them.
 *
 * <h2>The selectors are fixed values</h2>
 *
 * A selector of this service is the word of an entry type in hyphen form
 * ({@link EntryType#selector()}), so the arguments that carry one declare the
 * six as their fixed values (C5.4). They are declared here and nowhere else:
 * the input schema of the assistant surface and the address grammar do not
 * hold a selector to them yet.
 *
 * <h2>Versioned by its content</h2>
 *
 * {@code version} is a SHA-256 over everything else the declaration carries,
 * {@code shape_version} and {@code scheme} included, so that any change -- a
 * text, an argument, a reason -- yields a new version and an unchanged
 * declaration keeps its own, which is what a reader that records which
 * version it served can rely on.
 */
public final class SurfaceDeclaration {

    private static final String DESCRIPTION = "description";

    /**
     * Changes when the shape of {@link #asMap()} changes, not when its content
     * does: one value for every service, today {@code "3"} (C5.5).
     */
    public static final String SHAPE_VERSION = "3";

    /**
     * The most characters a call's description may carry. A description over
     * it refuses the start ({@link DeclarationGuard}), and with it every test
     * that boots the service.
     */
    public static final int DESCRIPTION_BUDGET = 500;

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
        declaration.put("technical_address_form", "memory://<uuid>");
        declaration.put("effect_classes",
            Arrays.stream(EffectClass.values()).map(Enum::name).toList());
        declaration.put("calls", callsAsMaps());
        declaration.put("reasons", reasonsAsMaps());
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
            call.put("rest_name", verb.name().toLowerCase(Locale.ROOT));
            call.put("effect_class", form.effect().name());
            EffectClass atScopeDepth = form.scopeDepth();
            if (atScopeDepth != null) {
                call.put("at_scope_depth", atScopeDepth.name());
            }
            if (form.technicalAddress()) {
                call.put("technical_address", true);
            }
            call.put(DESCRIPTION, verb.description());
            List<Map<String, Object>> arguments = new ArrayList<>();
            if (verb.writesFields()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("name", AssistantVerb.FIELDS);
                fields.put("type", "object");
                fields.put("required", true);
                fields.put("placement", Argument.Placement.TOP.name());
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
        declared.put("placement", argument.placement().name());
        declared.put(DESCRIPTION, argument.description());
        // Already a value of the argument's own type in the keywords: the
        // input schema publishes the same one (C5.4).
        Object fallback = argument.keywords().get("default");
        if (fallback != null) {
            declared.put("default", fallback);
        }
        List<Value> values = valuesOf(argument);
        if (!values.isEmpty()) {
            declared.put("values", values.stream().map(Value::name).toList());
            declared.put("value_descriptions", values.stream()
                .map(value -> {
                    Map<String, Object> described = new LinkedHashMap<>();
                    described.put("value", value.name());
                    described.put(DESCRIPTION, value.description());
                    return described;
                })
                .toList());
        }
        for (String bound : List.of("minimum", "maximum", "minItems")) {
            if (argument.keywords().containsKey(bound)) {
                declared.put(bound, argument.keywords().get(bound));
            }
        }
        return declared;
    }

    /** One value of a fixed set, with what it means. */
    private record Value(String name, String description) {
    }

    /**
     * The fixed set an argument's values come from, or of its elements for a
     * list; for an argument that carries a selector, the six selectors.
     */
    private static List<Value> valuesOf(Argument argument) {
        if (AssistantVerb.Names.SELECTOR.equals(argument.name())) {
            return EntryType.inDigestOrder().stream()
                .map(type -> new Value(type.selector(), typeDescription(type)))
                .toList();
        }
        Object values = argument.keywords().get("enum");
        if (values == null && argument.keywords().get("items") instanceof Map<?, ?> items) {
            values = items.get("enum");
        }
        if (!(values instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf)
            .map(value -> new Value(value, valueDescription(argument.name(), value)))
            .toList();
    }

    /** What one value of a fixed set means, from its bundle ({@link ValueDescriptions}). */
    private static String valueDescription(String argument, String value) {
        ValueDescriptions texts = Texts.values();
        return switch (argument + "=" + value) {
            case "sort=id" -> texts.sortId();
            case "sort=created_at" -> texts.sortCreatedAt();
            case "direction=asc" -> texts.directionAsc();
            case "direction=desc" -> texts.directionDesc();
            default -> EntryType.of(value).map(SurfaceDeclaration::typeDescription)
                .orElseThrow(() -> new IllegalStateException(argument + " declares a value "
                    + "that has no description in the value bundle"));
        };
    }

    /** What an entry type means; its selector means the same. */
    private static String typeDescription(EntryType type) {
        ValueDescriptions texts = Texts.values();
        return switch (type) {
            case CONSTRAINT -> texts.typeConstraint();
            case DECISION -> texts.typeDecision();
            case CONVENTION -> texts.typeConvention();
            case GLOSSARY -> texts.typeGlossary();
            case STATUS -> texts.typeStatus();
            case OPEN_QUESTION -> texts.typeOpenQuestion();
        };
    }

    /**
     * The reasons, in the form of the reference: the message under its key
     * and as its bundle writes it, and the remedy.
     *
     * <p>{@code message_pattern} is the message as this service sends it,
     * which ends with the remedy: the pattern a reader matches a refusal
     * against.
     */
    private static List<Map<String, Object>> reasonsAsMaps() {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("reason", reason.code());
            declared.put("message_pattern",
                reason.messageTemplate() + " " + reason.remedyTemplate());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("key", reason.key());
            message.put("template", reason.messageTemplate());
            declared.put("messages", List.of(message));
            declared.put("remedy", reason.remedyTemplate());
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
     * What the router needs of a verb beside its arguments: its effect class,
     * the further address forms it reaches, the shape of its answer, and the
     * reasons it can be refused with.
     *
     * @param scopeDepth       the class it acts with on a scope address, where
     *                         it reaches one beyond its own depth
     * @param technicalAddress whether {@code memory://<uuid>} addresses an
     *                         entry for it
     */
    public record Form(EffectClass effect, @Nullable EffectClass scopeDepth,
                       boolean technicalAddress, AnswerShape answer, List<String> refusals) {
    }

    public static Form formOf(AssistantVerb verb) {
        return switch (verb) {
            case CREATE -> new Form(EffectClass.CREATE, null, false,
                AnswerShape.ENTRY_CREATED, refusals(WRITE, ADDRESSED, List.of(
                    Reason.SELECTOR_ABSENT, Reason.SELECTOR_MISMATCHED, Reason.ALREADY_EXISTS),
                    VALUES, BODY));
            case READ -> new Form(EffectClass.ITEM_READ, null, true,
                AnswerShape.ENTRY, refusals(ADDRESSED));
            case UPDATE -> new Form(EffectClass.FIELD_WRITE, null, false,
                AnswerShape.ENTRY, refusals(WRITE, ADDRESSED, TOKEN, List.of(Reason.UPDATE_EMPTY),
                    VALUES, BODY));
            case WITHDRAW -> new Form(EffectClass.ITEM_TRANSITION, null,
                false, AnswerShape.WITHDRAWN, refusals(WRITE, ADDRESSED, TOKEN));
            case QUERY -> new Form(EffectClass.COLLECTION_READ,
                EffectClass.SCOPE_READ, false, AnswerShape.ENTRY_LISTING,
                refusals(ADDRESSED, List.of(Reason.TYPE_UNKNOWN, Reason.PAGE_SIZE_REJECTED,
                    Reason.CURSOR_MALFORMED, Reason.CURSOR_FOREIGN, Reason.SORT_UNDECLARED)));
            case DIGEST -> new Form(EffectClass.SCOPE_COMPOUND, null, false,
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
}
