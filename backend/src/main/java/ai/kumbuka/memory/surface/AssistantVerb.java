package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.QueryFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The six calls of the assistant surface, and the one place they are declared.
 *
 * <h2>Why the six verbs and nothing else</h2>
 *
 * An entry has no intermediate state. It is in force from the call that
 * creates it until the call that withdraws it; there is no draft, no hand-over
 * and nothing that only somebody else can move. So there is no process to
 * name beyond the verbs themselves, and the declaration is the verbs. A
 * service whose objects pass through states carries process verbs on this
 * surface instead; this one has none to carry.
 *
 * <h2>What is derived from here</h2>
 *
 * The tool list, every input schema, every description, the names that appear
 * in {@code next}, the argument check and the declaration served at
 * {@code /mcp/declaration}. The descriptions are the contract's normative text,
 * copied character for character; a probe compares them with the contract and
 * never with this file.
 */
public enum AssistantVerb {

    CREATE("memory_create",
        "Lays down a new entry at a free address. The address is the scope and the key; "
            + "the key begins with the selector. Refused if an entry already stands there: "
            + "change it with memory_update. Callable by anyone who may write to the scope.",
        "Lays down a new entry at a free address.",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, Descriptions.SCOPE),
            Argument.top(Names.SELECTOR, Argument.STRING, true,
                "the selector the entry stands under; the key begins with it")),
        List.of(
            Argument.field(Names.KEY, true,
                "the key of the entry, <selector>.<id> in lowercase letters, digits, dots "
                    + "and hyphens"),
            Argument.field(Names.TYPE, true, Descriptions.TYPE, Descriptions.TYPE_ENUM),
            Argument.field(Names.CONTENT, true, Descriptions.CONTENT),
            Argument.field(Names.REFERENCE, false, Descriptions.REFERENCE))),

    READ("memory_read",
        "Reads one entry by its complete address and hands out a fresh conflict token. "
            + "Changes nothing. An address that does not exist and one you may not see are "
            + "answered alike.",
        "Reads the entry again and hands out a fresh conflict token.",
        List.of(Argument.top(Names.ADDRESS, Argument.STRING, true, Descriptions.ADDRESS)),
        List.of()),

    UPDATE("memory_update",
        "Changes the content, the type or the reference of an entry; its address is fixed "
            + "for its life. Needs the conflict token of the latest read and is refused if "
            + "the entry changed since. Callable by anyone who may write to the scope.",
        "Changes the content, the type or the reference; needs the conflict token.",
        List.of(
            Argument.top(Names.ADDRESS, Argument.STRING, true, Descriptions.ADDRESS),
            Argument.top(Names.CONFLICT_TOKEN, Argument.STRING, true,
                Descriptions.CONFLICT_TOKEN)),
        List.of(
            Argument.field(Names.CONTENT, false, Descriptions.CONTENT),
            Argument.field(Names.TYPE, false, Descriptions.TYPE, Descriptions.TYPE_ENUM),
            Argument.field(Names.REFERENCE, false, Descriptions.REFERENCE))),

    WITHDRAW("memory_withdraw",
        "Takes an entry out of force. Final: no call restores it, and the answer says "
            + "whether its address is free again. Needs the conflict token of the latest "
            + "read.",
        "Takes the entry out of force; needs the conflict token.",
        List.of(
            Argument.top(Names.ADDRESS, Argument.STRING, true, Descriptions.ADDRESS),
            Argument.top(Names.CONFLICT_TOKEN, Argument.STRING, true,
                Descriptions.CONFLICT_TOKEN)),
        List.of()),

    QUERY("memory_query",
        "Lists the entries of a scope page by page, optionally narrowed by selector, type "
            + "or text. Changes nothing. Use it to find an address.",
        "Lists the entries of the scope page by page.",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, Descriptions.SCOPE),
            Argument.top(Names.SELECTOR, Argument.STRING, false,
                "the selector whose entries alone are listed"),
            Argument.top(Names.TYPE, Argument.STRING, false,
                "the type whose entries alone are listed", Descriptions.TYPE_ENUM),
            Argument.top(Names.TEXT, Argument.STRING, false, "a text the entry must contain"),
            Argument.top(Names.AFTER, Argument.STRING, false,
                "the cursor of the previous page, exactly as that page handed it out"),
            Argument.top(Names.PAGE_SIZE, Argument.INTEGER, false,
                "how many entries one page carries, a whole number from 1 to "
                    + QueryFilter.MAX_PAGE_SIZE + ", " + QueryFilter.DEFAULT_PAGE_SIZE
                    + " when left out",
                Map.of("minimum", 1, "maximum", QueryFilter.MAX_PAGE_SIZE,
                    "default", QueryFilter.DEFAULT_PAGE_SIZE))),
        List.of()),

    DIGEST("memory_digest",
        "Returns everything in force in a scope, whole and grouped by type, with a tally "
            + "per type. Changes nothing. Carries the types the scope's digest selection "
            + "names unless `types` says otherwise. Call it once at the start of a session.",
        "Returns everything in force in the scope, grouped by type.",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, Descriptions.SCOPE),
            Argument.top(Names.TYPES, Argument.ARRAY, false,
                "a list of type names to carry instead of the scope's digest selection",
                Map.of("items", Map.of("type", Argument.STRING,
                        "enum", EntryType.wireNames()),
                    "minItems", 1))),
        List.of());

    /** The name of the object that carries everything a call writes (DEC-0040). */
    public static final String FIELDS = "fields";

    /** What {@code fields} is, for the refusal that finds it missing. */
    public static final String FIELDS_DESCRIPTION =
        "an object carrying the values this call writes";

    private final String call;
    private final String description;
    private final String does;
    private final List<Argument> topArguments;
    private final List<Argument> fieldArguments;

    AssistantVerb(String call, String description, String does, List<Argument> top,
                  List<Argument> fields) {
        this.call = call;
        this.description = description;
        this.does = does;
        this.topArguments = List.copyOf(top);
        this.fieldArguments = List.copyOf(fields);
    }

    /** The tool name: {@code memory_<verb>} (DEC-0043). */
    public String call() {
        return call;
    }

    /** The contract's normative description. */
    public String description() {
        return description;
    }

    /** What the call does, as a {@code next} entry names it. */
    public String does() {
        return does;
    }

    public List<Argument> topArguments() {
        return topArguments;
    }

    public List<Argument> fieldArguments() {
        return fieldArguments;
    }

    /** Whether the call writes values, and so takes a {@code fields} object. */
    public boolean writesFields() {
        return !fieldArguments.isEmpty();
    }

    /** Every argument, top-level first, in declaration order. */
    public List<Argument> arguments() {
        List<Argument> all = new ArrayList<>(topArguments);
        all.addAll(fieldArguments);
        return List.copyOf(all);
    }

    /**
     * The names a caller may write at the top level, {@code fields} included
     * where the call writes. This is the list an {@code ARGUMENT_UNKNOWN}
     * refusal reads out.
     */
    public List<String> topNames() {
        List<String> names = new ArrayList<>(topArguments.stream().map(Argument::name).toList());
        if (writesFields()) {
            names.add(FIELDS);
        }
        return List.copyOf(names);
    }

    /** The names a caller may write under {@code fields}. */
    public List<String> fieldNames() {
        return fieldArguments.stream().map(Argument::name).toList();
    }

    /** The call a tool name names, if any. */
    public static Optional<AssistantVerb> byCall(String call) {
        return Arrays.stream(values()).filter(v -> v.call.equals(call)).findFirst();
    }

    /** The argument names, so that the enum above and the adapter spell them once. */
    public static final class Names {

        public static final String SCOPE = "scope";
        public static final String SELECTOR = "selector";
        public static final String ADDRESS = "address";
        public static final String CONFLICT_TOKEN = "conflict_token";
        public static final String KEY = "key";
        public static final String TYPE = "type";
        public static final String CONTENT = "content";
        public static final String REFERENCE = "reference";
        public static final String TEXT = "text";
        public static final String AFTER = "after";
        public static final String PAGE_SIZE = "page_size";
        public static final String TYPES = "types";

        private Names() {
        }
    }

    private static final class Descriptions {

        static final String SCOPE = "the scope, by its name";
        static final String ADDRESS =
            "the complete address of the entry, memory://<scope>/<selector>/<id>";
        static final String CONFLICT_TOKEN =
            "the conflict token the latest read of the entry handed out";
        static final String TYPE = "the type of the entry, one of "
            + String.join(", ", EntryType.wireNames());
        static final Map<String, Object> TYPE_ENUM = Map.of("enum", EntryType.wireNames());
        static final String CONTENT = "the text of the entry, at most "
            + MemoryService.CONTENT_LIMIT + " characters";
        static final String REFERENCE =
            "a provenance URL, stored and never fetched, that carries no credential";

        private Descriptions() {
        }
    }
}
