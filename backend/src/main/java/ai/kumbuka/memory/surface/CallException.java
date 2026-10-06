package ai.kumbuka.memory.surface;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A refusal about the arguments of a call on the assistant surface.
 *
 * <p>The generic surface takes its values from a path, a header and a body,
 * and refuses there in the vocabulary of HTTP. The assistant surface takes
 * every value as a named argument of one call, so it refuses in the vocabulary
 * of arguments: one it does not declare, one it needs and did not get, one
 * whose value is not of the declared kind. Those three, plus the failure
 * nobody foresaw, are the reasons this type adds, and the reason catalogue
 * declares each of them beside the domain's own.
 *
 * <p>The values a message is built from travel in {@link #values()}, keyed by
 * the placeholder they fill. The exception message is a note for a developer
 * and never reaches a caller: every message on the assistant surface is built
 * from its declared pattern and nothing else.
 */
public class CallException extends RuntimeException {

    /** The conditions only the assistant surface refuses on. */
    public enum Reason {

        /** The call names an argument it does not declare, at any level. */
        ARGUMENT_UNKNOWN,

        /** The call lacks an argument it declares as required. */
        ARGUMENT_MISSING,

        /** The call carries an argument whose value is not of the declared kind. */
        ARGUMENT_INVALID,

        /** The call failed in a way the service did not foresee. */
        UNEXPECTED_FAILURE
    }

    private final transient Reason reason;
    private final transient Map<String, String> values;

    private CallException(Reason reason, String note, Map<String, String> values) {
        super(note);
        this.reason = reason;
        this.values = Map.copyOf(values);
    }

    /**
     * @param declared the names the caller could have written at that level,
     *                 in declaration order
     */
    public static CallException unknown(String name, List<String> declared) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.NAME, name);
        values.put(ReasonCatalogue.ARGUMENTS, String.join(", ", declared));
        return new CallException(Reason.ARGUMENT_UNKNOWN, "undeclared argument", values);
    }

    /** @param what what the value is, from the argument's declaration */
    public static CallException missing(String name, String what) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.NAME, name);
        values.put(ReasonCatalogue.WHAT, what);
        return new CallException(Reason.ARGUMENT_MISSING, "required argument absent", values);
    }

    /**
     * @param shown how the value is shown back: the value itself, or only its
     *              kind where the value could be the content of an entry
     * @param why   a sentence of this surface's own saying what the value must be
     */
    public static CallException invalid(String name, String shown, String why) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ReasonCatalogue.NAME, name);
        values.put(ReasonCatalogue.VALUE, shown);
        values.put(ReasonCatalogue.WHY, why);
        return new CallException(Reason.ARGUMENT_INVALID, "argument of the wrong kind",
            values);
    }

    public Reason reason() {
        return reason;
    }

    /** The placeholder values this refusal fills its pattern with. */
    public Map<String, String> values() {
        return values;
    }
}
