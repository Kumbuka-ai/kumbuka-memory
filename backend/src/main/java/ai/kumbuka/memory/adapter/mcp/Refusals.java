package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.surface.AddressParser;
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
 * The reason's pattern from the catalogue, and values taken from three places
 * only: the call itself (the tool name and the arguments the caller sent), the
 * structured data a domain refusal carries (an offending name, an address),
 * and the service's published limits. Never the domain refusal's message. That
 * sentence was written for the generic surface, names calls in that surface's
 * vocabulary, and may carry what this surface keeps out; so it is not read
 * here at all.
 *
 * <h2>What {@code data} carries</h2>
 *
 * {@code attempted} on every refusal. Where the refusal concerns an entry this
 * caller may see, also its {@code state} and {@code next}: the calls the
 * remedy names, each kept only if it would succeed for this caller, judged
 * from the same write right that decides an answer's {@code next}. A stale
 * conflict token additionally carries the entry as this caller's own read
 * would answer it (DEC-0041). {@code NOT_FOUND} carries {@code attempted}
 * and nothing else, so that its three causes cannot be told apart.
 */
final class Refusals {

    private static final String ATTEMPTED = "attempted";
    private static final String STATE = "state";
    private static final String NEXT = "next";

    private Refusals() {
    }

    // ======================================================================
    // The three sources of a refusal
    // ======================================================================

    /** A refusal the argument check made. */
    static Payloads.Refusal of(AssistantVerb verb, CallException e, Map<String, Object> args,
                               Optional<EntryView> seen) {
        ReasonCatalogue.Reason reason = ReasonCatalogue.of(e.reason().name());
        Map<String, String> values = new LinkedHashMap<>(e.values());
        values.put(ReasonCatalogue.CALL, verb.call());
        return envelope(verb, reason, ReasonCatalogue.message(reason, false, values), seen);
    }

    /**
     * A refusal the service made.
     *
     * @param reason the catalogue entry for its code, already known to be one
     *               this surface can return
     */
    static Payloads.Refusal of(AssistantVerb verb, MemoryException e,
                               ReasonCatalogue.Reason reason, Map<String, Object> args,
                               Optional<EntryView> seen) {
        if (ReasonCatalogue.NOT_FOUND.equals(reason.code())) {
            return new Payloads.Refusal(reason.code(), ReasonCatalogue.NOT_FOUND_MESSAGE,
                Map.of(ATTEMPTED, verb.call()));
        }
        String message = ReasonCatalogue.message(reason, false, valuesFor(verb, e, args));
        Payloads.Refusal refusal = envelope(verb, reason, message, seen);
        if (e.data().get("fields") instanceof EntryView current) {
            refusal.data().put(MemoryException.ADDRESS, current.address().canonical());
            refusal.data().put(Names.CONFLICT_TOKEN, current.conflictToken());
            refusal.data().put(AssistantVerb.FIELDS, Payloads.EntryFields.of(current));
        }
        return refusal;
    }

    /** A failure nobody foresaw, already logged under {@code reportId}. */
    static Payloads.Refusal unexpected(AssistantVerb verb, Map<String, Object> args,
                                       String reportId) {
        ReasonCatalogue.Reason reason =
            ReasonCatalogue.of(CallException.Reason.UNEXPECTED_FAILURE.name());
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.CALL, verb.call());
        values.put(ReasonCatalogue.REPORT, reportId);
        boolean addressed = takesAddress(verb);
        if (addressed) {
            values.put(ReasonCatalogue.ADDRESS, String.valueOf(args.get(Names.ADDRESS)));
        } else {
            values.put(ReasonCatalogue.SCOPE, String.valueOf(args.get(Names.SCOPE)));
        }
        Payloads.Refusal refusal = new Payloads.Refusal(reason.code(),
            ReasonCatalogue.message(reason, !addressed, values), new LinkedHashMap<>());
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
     * Every placeholder the reason's pattern can name, filled from the call.
     *
     * <p>Filled generously rather than per reason: a value the pattern does
     * not name is simply not used, while a value it names and is missing makes
     * the message refuse to build — which is the failure worth having.
     */
    private static Map<String, String> valuesFor(AssistantVerb verb, MemoryException e,
                                                 Map<String, Object> args) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.CALL, verb.call());

        String key = keyOf(verb, args);
        Optional<AddressParser.Parts> parts = partsOf(args);
        String scope = string(args.get(Names.SCOPE));
        if (scope == null) {
            scope = parts.map(AddressParser.Parts::scope).orElse(null);
        }

        putIfPresent(values, ReasonCatalogue.SCOPE, scope);
        putIfPresent(values, ReasonCatalogue.KEY, key);
        // The reserved check reads the key's leading segment; every other
        // pattern that names a selector means the one the call named.
        putIfPresent(values, ReasonCatalogue.SELECTOR,
            e.reason() == MemoryException.Reason.SELECTOR_RESERVED
                ? leadingSegmentOf(key)
                : string(args.get(Names.SELECTOR)));
        if (key != null) {
            int dot = key.indexOf(EntryAddress.SEPARATOR);
            values.put(ReasonCatalogue.KEY_SELECTOR, dot < 0 ? key : key.substring(0, dot));
        }
        putIfPresent(values, ReasonCatalogue.ADDRESS, addressOf(verb, e, args, scope, key));

        Object offenders = e.data().get(MemoryException.OFFENDERS);
        String firstOffender = offenders instanceof List<?> list && !list.isEmpty()
            ? String.valueOf(list.get(0)) : null;
        putIfPresent(values, ReasonCatalogue.FIELD, firstOffender);

        values.put(ReasonCatalogue.TYPES, String.join(", ", EntryType.wireNames()));
        values.put(ReasonCatalogue.LIMIT, String.valueOf(MemoryService.CONTENT_LIMIT));
        values.put(ReasonCatalogue.MAX, String.valueOf(QueryFilter.MAX_PAGE_SIZE));
        String content = string(fieldsOf(args).get(Names.CONTENT));
        putIfPresent(values, ReasonCatalogue.LENGTH,
            content == null ? null : String.valueOf(content.length()));
        putIfPresent(values, ReasonCatalogue.VALUE, valueOf(verb, e, args, firstOffender));
        return values;
    }

    /** The value a refusal about one value names: the one the caller sent. */
    private static String valueOf(AssistantVerb verb, MemoryException e,
                                  Map<String, Object> args, String firstOffender) {
        return switch (e.reason()) {
            case TYPE_UNKNOWN -> firstOffender != null ? firstOffender : typeOf(args);
            case PAGE_SIZE_REJECTED -> string(args.get(Names.PAGE_SIZE));
            case CURSOR_MALFORMED -> string(args.get(Names.AFTER));
            case ADDRESS_MALFORMED -> takesAddress(verb)
                ? string(args.get(Names.ADDRESS))
                : targetOf(verb, args);
            default -> null;
        };
    }

    private static String typeOf(Map<String, Object> args) {
        String written = string(fieldsOf(args).get(Names.TYPE));
        return written != null ? written : string(args.get(Names.TYPE));
    }

    /** The complete address a refusal names: the one given, or the one a create aimed at. */
    private static String addressOf(AssistantVerb verb, MemoryException e,
                                    Map<String, Object> args, String scope, String key) {
        if (e.data().get(MemoryException.ADDRESS) instanceof String complete) {
            return complete;
        }
        if (takesAddress(verb)) {
            return string(args.get(Names.ADDRESS));
        }
        if (verb == AssistantVerb.CREATE && scope != null && key != null
            && key.indexOf(EntryAddress.SEPARATOR) > 0) {
            return EntryAddress.ofKey(scope, key).canonical();
        }
        return null;
    }

    /** Where a call without an address was aimed, written as an address. */
    private static String targetOf(AssistantVerb verb, Map<String, Object> args) {
        StringBuilder target = new StringBuilder("memory://")
            .append(string(args.get(Names.SCOPE)));
        String selector = string(args.get(Names.SELECTOR));
        if (verb != AssistantVerb.DIGEST && selector != null) {
            target.append('/').append(selector);
        }
        return target.toString();
    }

    /** The key a call concerns: the one a create writes, or the one an address names. */
    private static String keyOf(AssistantVerb verb, Map<String, Object> args) {
        if (verb == AssistantVerb.CREATE) {
            return string(fieldsOf(args).get(Names.KEY));
        }
        if (verb == AssistantVerb.QUERY) {
            return string(args.get(Names.SELECTOR));
        }
        return partsOf(args)
            .map(p -> p.selector() + EntryAddress.SEPARATOR + p.id())
            .orElse(null);
    }

    private static Optional<AddressParser.Parts> partsOf(Map<String, Object> args) {
        if (!(args.get(Names.ADDRESS) instanceof String address)) {
            return Optional.empty();
        }
        try {
            return Optional.of(AddressParser.complete(address));
        } catch (MemoryException notAnAddress) {
            return Optional.empty();
        }
    }

    /** The first dot- or dash-separated segment, which is what the reserved check reads. */
    private static String leadingSegmentOf(String key) {
        return key == null ? null : key.split("[.-]", 2)[0];
    }

    static boolean takesAddress(AssistantVerb verb) {
        return verb.topArguments().stream().anyMatch(a -> Names.ADDRESS.equals(a.name()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> args) {
        return args.get(AssistantVerb.FIELDS) instanceof Map<?, ?> fields
            ? (Map<String, Object>) fields
            : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static void putIfPresent(Map<String, String> values, String name, String value) {
        if (value != null) {
            values.put(name, value);
        }
    }
}
