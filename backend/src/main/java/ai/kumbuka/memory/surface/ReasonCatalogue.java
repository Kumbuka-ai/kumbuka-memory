package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every reason this service can refuse with, each with its message pattern and
 * its remedy (DEC-0043).
 *
 * <h2>A message is built from its pattern and from nothing else</h2>
 *
 * The domain's refusals carry sentences of their own, written for the generic
 * surface, and those sentences stay there. On the assistant surface a message
 * is this table's pattern with its placeholders filled from the call itself —
 * the name of the tool, an argument the caller sent, a count, a limit — and a
 * sentence produced inside the service never reaches the caller, in whole or
 * in part. A pattern that interpolated the domain's message would hand the
 * caller exactly the internals the rule keeps out.
 *
 * <h2>Steps are named in the vocabulary of the surface</h2>
 *
 * A pattern never names a call literally. It names a step — {@code {read}},
 * {@code {update}} — and the step is filled with the tool name the declaration
 * gives it. A refusal that told an assistant to call {@code read} would name a
 * call this surface does not carry.
 *
 * <h2>Complete, or the service does not start</h2>
 *
 * {@link #requireComplete} walks every reason the service can raise — the
 * domain's, the generic surface's and this surface's own — and refuses when
 * one has no entry here. A reason not in the table could only be worded by
 * improvising, and an improvised refusal is the thing the table exists to
 * prevent; so the service refuses to start instead.
 */
public final class ReasonCatalogue {

    // --- the placeholders, spelled once ------------------------------------

    public static final String CALL = "call";
    public static final String ADDRESS = "address";
    public static final String SCOPE = "scope";
    public static final String KEY = "key";
    public static final String KEY_SELECTOR = "key_selector";
    public static final String SELECTOR = "selector";
    public static final String FIELD = "field";
    public static final String VALUE = "value";
    public static final String TYPES = "types";
    public static final String LENGTH = "length";
    public static final String LIMIT = "limit";
    public static final String MAX = "max";
    public static final String NAME = "name";
    public static final String ARGUMENTS = "arguments";
    public static final String WHAT = "what";
    public static final String WHY = "why";
    public static final String REPORT = "report";

    /** The one code the not-found class carries on every surface. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /**
     * The one message the not-found class carries.
     *
     * <p>The contract fixes the wording in section 4.3, and the generic
     * surface answers the same text: a second wording would tell a caller which
     * surface answered, and through that which hop it reached. It is held here
     * as well as there because this package may not reach into an adapter; a
     * probe holds both against the contract's text, and another asserts that
     * both surfaces answer it.
     */
    public static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

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
     * One declared reason.
     *
     * @param code      the code on the wire
     * @param pattern   the message, with placeholders in braces
     * @param alternate a second form of the same message for a call without
     *                  an address, or null where there is none
     * @param remedy    what the caller can do, in the surface's vocabulary
     * @param steps     the calls the remedy names, in order; listed in
     *                  {@code data.next} where the refusal concerns an entry
     *                  the caller may see
     * @param repeat    whether the remedy is to make the same call again,
     *                  corrected
     * @param reach     where the reason can be returned
     */
    public record Reason(String code, String pattern, String alternate, String remedy,
                         List<AssistantVerb> steps, boolean repeat, Reach reach) {

        public Reason {
            steps = List.copyOf(steps);
        }
    }

    private static Map<String, Reason> declare() {
        Map<String, Reason> declared = new LinkedHashMap<>();

        put(declared, NOT_FOUND, NOT_FOUND_MESSAGE, "check scope and address");
        put(declared, "SCOPE_LOCKED",
            "{call} is not possible in scope {scope}: the scope is locked and takes no write "
                + "from anyone.",
            "{read}, {query}, {digest}",
            AssistantVerb.READ, AssistantVerb.QUERY, AssistantVerb.DIGEST);
        put(declared, "SCOPE_READ_ONLY",
            "{call} is not possible in scope {scope}: you may read this scope but not write "
                + "to it.",
            "{read}, {query}, {digest}",
            AssistantVerb.READ, AssistantVerb.QUERY, AssistantVerb.DIGEST);
        repeat(declared, "ADDRESS_MALFORMED",
            "{value} is not an address this service carries. An entry stands at "
                + "memory://<scope>/<selector>/<id>.",
            "correct the address");
        repeat(declared, "KEY_MALFORMED",
            "{key} is not a key this service stores. A key is <selector>.<id> in lowercase "
                + "letters, digits, dots and hyphens.",
            "correct the key");
        repeat(declared, "SELECTOR_ABSENT",
            "{call} names no selector for {key}. A key is <selector>.<id>, and the part "
                + "before its first dot is the selector the entry stands under.",
            "write the key as <selector>.<id>");
        repeat(declared, "SELECTOR_RESERVED",
            "The selector {selector} is reserved for entries the service lays down itself.",
            "use another selector");
        repeat(declared, "SELECTOR_MISMATCHED",
            "The key {key} begins with the selector {key_selector}, but {call} names the "
                + "selector {selector}.",
            "make the two agree");
        put(declared, "ALREADY_EXISTS",
            "{call} is not possible: an entry already stands at {address}. Change it with "
                + "{update}.",
            "{read}, then {update}",
            AssistantVerb.READ, AssistantVerb.UPDATE);
        put(declared, "CONFLICT_TOKEN_STALE",
            "{call} is not possible on {address}: the entry was changed since you read it. "
                + "Read it again with {read} and repeat the call with the new conflict token.",
            "{read}, then repeat",
            AssistantVerb.READ);
        repeat(declared, "UPDATE_EMPTY",
            "{call} on {address} names nothing to change. It can change content, type and "
                + "reference.",
            "supply one of the three");
        repeat(declared, "TYPE_UNKNOWN",
            "{value} is not a type of entry. The types are: {types}.",
            "use one of the list");
        repeat(declared, "CONTENT_ABSENT",
            "{call} needs content: an entry without content is not stored.",
            "supply content");
        repeat(declared, "CONTENT_OVERSIZE",
            "The content is {length} characters long. An entry holds at most {limit}.",
            "shorten it, or split it into several entries");
        repeat(declared, "REFERENCE_CREDENTIAL_BEARING",
            "The reference carries a credential and is not stored. Remove the user "
                + "information and every secret parameter from the URL.",
            "correct the reference");
        repeat(declared, "PAGE_SIZE_REJECTED",
            "page_size = {value} is not valid for {call}: it is a whole number from 1 to "
                + "{max}.",
            "correct the value");
        put(declared, "CURSOR_MALFORMED",
            "after = {value} is not a cursor {query} handed out.",
            "repeat {query} without after",
            AssistantVerb.QUERY);
        put(declared, "ACTOR_UNKNOWN",
            "The token is valid but names no subject to act as.",
            "authenticate as a subject");
        repeat(declared, "ARGUMENT_UNKNOWN",
            "{call} has no argument named {name}. Its arguments are: {arguments}.",
            "correct the name");
        repeat(declared, "ARGUMENT_MISSING",
            "{call} needs {name}: {what}.",
            "supply it");
        repeat(declared, "ARGUMENT_INVALID",
            "{name} = {value} is not valid for {call}: {why}.",
            "correct the value");
        declared.put("UNEXPECTED_FAILURE", new Reason("UNEXPECTED_FAILURE",
            "{call} on {address} failed unexpectedly. This is a defect, not a rule. Nothing "
                + "was changed. Report reference {report}.",
            "{call} in scope {scope} failed unexpectedly. This is a defect, not a rule. "
                + "Nothing was changed. Report reference {report}.",
            "report the reference", List.of(), false, Reach.BOTH));

        // --- the generic surface's own -------------------------------------
        generic(declared, "CONFLICT_TOKEN_MISSING",
            "{call} on {address} needs the conflict token of the latest read, and none "
                + "arrived.",
            "{read}, then repeat with the token");
        generic(declared, "PREDICATE_UNKNOWN",
            "{call} carries no predicate named {name}.",
            "correct the name");
        // A field fixed for the life of an entry is no argument of any call on
        // the assistant surface, so naming one there is ARGUMENT_UNKNOWN.
        generic(declared, "FIELD_IMMUTABLE",
            "{field} of {address} is fixed for the life of the entry and cannot be changed by "
                + "{call}.",
            "leave the field out");
        generic(declared, "PAYLOAD_MALFORMED",
            "The body of {call} is not the JSON it takes.",
            "correct the body");
        generic(declared, "VERB_NOT_CARRIED",
            "{name} is not a verb {address} carries.",
            "use a verb the address carries");
        generic(declared, "ADDRESS_TRUNCATED",
            "{call} needs a complete address and was given a truncated one.",
            "complete the address");
        generic(declared, "SESSION_NOT_BOUND",
            "{call} could not be answered: the session the read contract needs was not "
                + "bound.",
            "report it");

        return Map.copyOf(declared);
    }

    private static void put(Map<String, Reason> into, String code, String pattern,
                            String remedy, AssistantVerb... steps) {
        into.put(code, new Reason(code, pattern, null, remedy, List.of(steps), false,
            Reach.BOTH));
    }

    private static void repeat(Map<String, Reason> into, String code, String pattern,
                               String remedy) {
        into.put(code, new Reason(code, pattern, null, remedy, List.of(), true, Reach.BOTH));
    }

    private static void generic(Map<String, Reason> into, String code, String pattern,
                                String remedy) {
        into.put(code, new Reason(code, pattern, null, remedy, List.of(), false,
            Reach.GENERIC));
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
            .sorted(java.util.Comparator.comparing(Reason::code))
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
     * one without a pattern or a remedy.
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
        for (Reason reason : catalogue.values()) {
            if (reason.pattern() == null || reason.pattern().isBlank()
                || reason.remedy() == null || reason.remedy().isBlank()) {
                throw new IllegalStateException(
                    reason.code() + " is declared without a pattern or without a remedy.");
            }
        }
    }

    // ======================================================================
    // Building a message
    // ======================================================================

    /**
     * A pattern with its steps filled in this surface's vocabulary and its
     * value placeholders left standing. This is the form the declaration
     * serves.
     */
    public static String inVocabulary(String pattern) {
        return fill(pattern, Map.of(), true);
    }

    /**
     * The message for a reason, built from its pattern alone.
     *
     * @param alternate whether to use the form for a call without an address
     * @throws IllegalStateException when the pattern names a placeholder the
     *         values do not fill; a half-filled message reads as finished,
     *         which is worse than none
     */
    public static String message(Reason reason, boolean alternate, Map<String, String> values) {
        String pattern = alternate && reason.alternate() != null
            ? reason.alternate()
            : reason.pattern();
        return fill(pattern, values, false);
    }

    /**
     * Fills every placeholder in ONE pass over the pattern.
     *
     * <p>One pass, and not a replace per value, because a value is often
     * something the caller sent: an address written with a brace in it must
     * come out as the caller wrote it, and must not be taken for a
     * placeholder by the next replacement.
     */
    private static String fill(String pattern, Map<String, String> values,
                               boolean leaveValues) {
        Matcher at = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        List<String> unfilled = new ArrayList<>();
        while (at.find()) {
            String name = at.group(1);
            String filled = stepName(name);
            if (filled == null) {
                filled = values.get(name);
            }
            if (filled == null) {
                if (!leaveValues) {
                    unfilled.add(name);
                }
                filled = at.group();
            }
            at.appendReplacement(out, Matcher.quoteReplacement(filled));
        }
        at.appendTail(out);
        if (!unfilled.isEmpty()) {
            throw new IllegalStateException("a message pattern was left with unfilled "
                + "placeholders " + unfilled);
        }
        return out.toString();
    }

    /** The tool a step placeholder names, or null when the name is not a step. */
    private static String stepName(String placeholder) {
        return switch (placeholder) {
            case "create" -> AssistantVerb.CREATE.call();
            case "read" -> AssistantVerb.READ.call();
            case "update" -> AssistantVerb.UPDATE.call();
            case "query" -> AssistantVerb.QUERY.call();
            case "digest" -> AssistantVerb.DIGEST.call();
            default -> null;
        };
    }
}
