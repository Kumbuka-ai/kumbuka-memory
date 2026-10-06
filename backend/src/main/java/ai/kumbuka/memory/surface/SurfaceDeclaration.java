package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.NextStep;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The assistant surface as one declaration: its calls, its reasons, and the
 * next steps an answer offers.
 *
 * <p>Served whole at {@code /mcp/declaration}, so that what the surface
 * carries can be read without calling it, and checked at start, so that a
 * declaration nobody could serve is never served.
 */
public final class SurfaceDeclaration {

    /** Changes when the shape of {@link #asMap()} changes, not when its content does. */
    public static final String SHAPE_VERSION = "1";

    /** What {@code next} says after a withdrawal that freed the address. */
    public static final String DOES_AFTER_RELEASE =
        "The address is free again; a new entry may be laid down at it.";

    /** What {@code next} says after a withdrawal that left the entry at its address. */
    public static final String DOES_AFTER_RETIREMENT =
        "Reads the retired entry, which still stands at this address.";

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
            ? new NextStep(AssistantVerb.CREATE.call(), DOES_AFTER_RELEASE)
            : new NextStep(AssistantVerb.READ.call(), DOES_AFTER_RETIREMENT));
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
        return declaration;
    }

    private static List<Map<String, Object>> callsAsMaps() {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (AssistantVerb verb : AssistantVerb.values()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("call", verb.call());
            call.put("description", verb.description());
            call.put("does", verb.does());
            List<Map<String, Object>> arguments = new ArrayList<>();
            for (Argument argument : verb.arguments()) {
                Map<String, Object> declared = new LinkedHashMap<>();
                declared.put("name", argument.name());
                declared.put("type", argument.type());
                declared.put("required", argument.required());
                declared.put("placement", argument.placement().name().toLowerCase(Locale.ROOT));
                declared.put("description", argument.description());
                arguments.add(declared);
            }
            call.put("arguments", arguments);
            calls.add(call);
        }
        return calls;
    }

    private static List<Map<String, Object>> reasonsAsMaps() {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("reason", reason.code());
            declared.put("message_pattern", ReasonCatalogue.inVocabulary(reason.pattern()));
            if (reason.alternate() != null) {
                declared.put("message_pattern_without_address",
                    ReasonCatalogue.inVocabulary(reason.alternate()));
            }
            declared.put("remedy", ReasonCatalogue.inVocabulary(reason.remedy()));
            declared.put("surface", reason.reach() == ReasonCatalogue.Reach.BOTH
                ? "both" : "generic");
            reasons.add(declared);
        }
        return reasons;
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
        }
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
