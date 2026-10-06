package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallException;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arguments of one tool call, checked against the declaration before a
 * verb sees them.
 *
 * <h2>No argument is accepted and discarded</h2>
 *
 * An argument the call does not declare is refused by name, at the top level
 * and inside {@code fields} alike. A dropped argument answers exactly as if
 * the caller had not sent it, which is the one failure a caller cannot see:
 * a misspelt {@code page_size} returns fifty entries and looks like a correct
 * answer to a question nobody asked.
 *
 * <h2>The order of the checks</h2>
 *
 * Unknown names first, then the declared arguments in declaration order, each
 * either missing or of the wrong kind. A value that is of the right kind and
 * still not acceptable — a page size of a thousand, a type that is not one of
 * the six — is not judged here: the verb judges it, and refuses with the
 * service's own reason, so that both surfaces refuse it the same way.
 */
final class CallArguments {

    /** The arguments whose value may be the text of an entry, and is never shown back. */
    private static final Set<String> WITHHELD =
        Set.of(AssistantVerb.Names.CONTENT, AssistantVerb.Names.REFERENCE);

    private final Map<String, Object> top;
    private final Map<String, Object> fields;

    CallArguments(AssistantVerb verb, Map<String, Object> arguments) {
        this.top = arguments;
        refuseUndeclared(top, verb.topNames());
        this.fields = fieldsOf(verb, top);
        refuseUndeclared(fields, verb.fieldNames());

        for (Argument argument : verb.topArguments()) {
            check(argument, top.get(argument.name()));
        }
        if (verb.writesFields() && top.get(AssistantVerb.FIELDS) == null) {
            throw CallException.missing(AssistantVerb.FIELDS, AssistantVerb.FIELDS_DESCRIPTION);
        }
        for (Argument argument : verb.fieldArguments()) {
            check(argument, fields.get(argument.name()));
        }
        if (verb == AssistantVerb.UPDATE && "".equals(fields.get(AssistantVerb.Names.REFERENCE))) {
            // An empty text would be read by the verb as "not mentioned" and
            // dropped, so the caller would believe it had removed the
            // reference while the entry kept it. Refused, because this
            // surface accepts nothing it then discards.
            throw CallException.invalid(AssistantVerb.Names.REFERENCE, "an empty text",
                "a reference is a URL, and this call cannot remove one");
        }
    }

    // ======================================================================
    // Reading the checked values
    // ======================================================================

    String top(String name) {
        return (String) top.get(name);
    }

    String field(String name) {
        return (String) fields.get(name);
    }

    /** A whole number, written as text so that the verb parses it as it parses any. */
    String number(String name) {
        Object value = top.get(name);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    List<String> list(String name) {
        return (List<String>) top.get(name);
    }

    // ======================================================================
    // The checks
    // ======================================================================

    private static void refuseUndeclared(Map<String, Object> given, List<String> declared) {
        for (String name : given.keySet()) {
            if (!declared.contains(name)) {
                throw CallException.unknown(name, declared);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(AssistantVerb verb, Map<String, Object> top) {
        Object raw = top.get(AssistantVerb.FIELDS);
        if (!verb.writesFields() || raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map)) {
            throw CallException.invalid(AssistantVerb.FIELDS, shown(AssistantVerb.FIELDS, raw),
                "it is an object carrying the values this call writes");
        }
        return (Map<String, Object>) raw;
    }

    private static void check(Argument argument, Object value) {
        if (value == null) {
            if (argument.required()) {
                throw CallException.missing(argument.name(), argument.description());
            }
            return;
        }
        String why = switch (argument.type()) {
            case Argument.STRING -> value instanceof String ? null : "it is a text";
            case Argument.INTEGER -> isWholeNumber(value) ? null : "it is a whole number";
            case Argument.ARRAY -> isNonEmptyTextList(value)
                ? null : "it is a list of at least one text";
            default -> throw new IllegalStateException(
                argument.name() + " is declared with a type the check does not know");
        };
        if (why != null) {
            throw CallException.invalid(argument.name(), shown(argument.name(), value), why);
        }
    }

    private static boolean isWholeNumber(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigInteger;
    }

    private static boolean isNonEmptyTextList(Object value) {
        return value instanceof List<?> list && !list.isEmpty()
            && list.stream().allMatch(String.class::isInstance);
    }

    /**
     * How a value of the wrong kind is shown back: as the caller wrote it,
     * except where it could be the text of an entry, which no refusal repeats.
     */
    private static String shown(String name, Object value) {
        if (WITHHELD.contains(name)) {
            return kindOf(value);
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return kindOf(value);
    }

    private static String kindOf(Object value) {
        if (value instanceof String) {
            return "a text";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean) {
            return "true or false";
        }
        if (value instanceof List) {
            return "a list";
        }
        return "an object";
    }
}
