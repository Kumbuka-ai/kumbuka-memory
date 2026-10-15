/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.domain.SortKey;

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
 * {@code /mcp/declaration} and on the generic surface. The texts themselves are
 * in the message bundles ({@link ToolDescriptions}, {@link NextStepTexts},
 * {@link ArgumentDescriptions}); this enum names which text belongs where. A
 * probe compares the descriptions with the contract and never with the bundle.
 *
 * <h2>The names a text may use</h2>
 *
 * Every {@link VerbName} and every {@link ArgumentName} is made here, from this
 * declaration, and handed out from it. A text names a call or an argument only
 * through one of those, so a name that arrived in a request can never stand in
 * a text (code-conventions/service-construction.md).
 */
public enum AssistantVerb {

    CREATE("memory_create",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, ArgumentDescriptions::scope),
            Argument.top(Names.SELECTOR, Argument.STRING, true,
                ArgumentDescriptions::createSelector)),
        List.of(
            Argument.field(Names.KEY, true, ArgumentDescriptions::createKey),
            Argument.field(Names.TYPE, true, ArgumentDescriptions::createType,
                Keywords.TYPE_ENUM),
            Argument.field(Names.CONTENT, true,
                texts -> texts.createContent(Bound.CONTENT_LIMIT)),
            Argument.field(Names.REFERENCE, false, ArgumentDescriptions::createReference))),

    READ("memory_read",
        List.of(Argument.top(Names.ADDRESS, Argument.STRING, true,
            ArgumentDescriptions::address)),
        List.of()),

    UPDATE("memory_update",
        List.of(
            Argument.top(Names.ADDRESS, Argument.STRING, true, ArgumentDescriptions::address),
            Argument.top(Names.CONFLICT_TOKEN, Argument.STRING, true,
                ArgumentDescriptions::conflictToken)),
        List.of(
            Argument.field(Names.CONTENT, false,
                texts -> texts.updateContent(Bound.CONTENT_LIMIT)),
            Argument.field(Names.TYPE, false, ArgumentDescriptions::updateType,
                Keywords.TYPE_ENUM),
            Argument.field(Names.REFERENCE, false, ArgumentDescriptions::updateReference))),

    WITHDRAW("memory_withdraw",
        List.of(
            Argument.top(Names.ADDRESS, Argument.STRING, true, ArgumentDescriptions::address),
            Argument.top(Names.CONFLICT_TOKEN, Argument.STRING, true,
                ArgumentDescriptions::conflictToken)),
        List.of()),

    QUERY("memory_query",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, ArgumentDescriptions::scope),
            Argument.top(Names.SELECTOR, Argument.STRING, false,
                ArgumentDescriptions::querySelector),
            Argument.top(Names.TYPE, Argument.STRING, false, ArgumentDescriptions::queryType,
                Keywords.TYPE_ENUM),
            Argument.top(Names.TEXT, Argument.STRING, false, ArgumentDescriptions::queryText),
            Argument.top(Names.AFTER, Argument.STRING, false, ArgumentDescriptions::queryAfter),
            Argument.top(Names.SORT, Argument.STRING, false, ArgumentDescriptions::querySort,
                Map.of("enum", SortKey.wireNames(),
                    Keywords.DEFAULT, PageRequest.DEFAULT_SORT.wire())),
            Argument.top(Names.DIRECTION, Argument.STRING, false,
                ArgumentDescriptions::queryDirection,
                Map.of("enum", SortKey.Direction.wireNames(),
                    Keywords.DEFAULT, PageRequest.DEFAULT_DIRECTION.wire())),
            Argument.top(Names.PAGE_SIZE, Argument.INTEGER, false,
                texts -> texts.queryPageSize(Bound.PAGE_SIZE_MIN, Bound.PAGE_SIZE_MAX,
                    Bound.PAGE_SIZE_DEFAULT),
                Map.of("minimum", Bound.PAGE_SIZE_MIN.value(),
                    "maximum", Bound.PAGE_SIZE_MAX.value(),
                    Keywords.DEFAULT, Bound.PAGE_SIZE_DEFAULT.value()))),
        List.of()),

    DIGEST("memory_digest",
        List.of(
            Argument.top(Names.SCOPE, Argument.STRING, true, ArgumentDescriptions::scope),
            Argument.top(Names.TYPES, Argument.ARRAY, false, ArgumentDescriptions::digestTypes,
                Map.of("items", Map.of("type", Argument.STRING,
                        "enum", EntryType.wireNames()),
                    "minItems", 1))),
        List.of());

    /** The name of the object that carries everything a call writes (DEC-0040). */
    public static final String FIELDS = "fields";

    private final String call;
    private final VerbName verbName;
    private final List<Argument> topArguments;
    private final List<Argument> fieldArguments;

    AssistantVerb(String call, List<Argument> top, List<Argument> fields) {
        this.call = call;
        this.verbName = new VerbName(call);
        this.topArguments = List.copyOf(top);
        this.fieldArguments = List.copyOf(fields);
    }

    /** The tool name: {@code memory_<verb>} (DEC-0043). */
    public String call() {
        return call;
    }

    /** The tool name as a text names it. */
    public VerbName verbName() {
        return verbName;
    }

    /** The call's description, from its bundle ({@link ToolDescriptions}). */
    public String description() {
        ToolDescriptions texts = Texts.tools();
        return switch (this) {
            case CREATE -> texts.create(UPDATE.verbName);
            case READ -> texts.read();
            case UPDATE -> texts.update();
            case WITHDRAW -> texts.withdraw();
            case QUERY -> texts.query();
            case DIGEST -> texts.digest();
        };
    }

    /** What the call does, as a {@code next} entry names it ({@link NextStepTexts}). */
    public String does() {
        NextStepTexts texts = Texts.next();
        return switch (this) {
            case CREATE -> texts.create();
            case READ -> texts.read();
            case UPDATE -> texts.update();
            case WITHDRAW -> texts.withdraw();
            case QUERY -> texts.query();
            case DIGEST -> texts.digest();
        };
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
    public List<ArgumentName> topNames() {
        List<ArgumentName> names = new ArrayList<>(
            topArguments.stream().map(Argument::argumentName).toList());
        if (writesFields()) {
            names.add(Keywords.FIELDS_NAME);
        }
        return List.copyOf(names);
    }

    /** The names a caller may write under {@code fields}. */
    public List<ArgumentName> fieldNames() {
        return fieldArguments.stream().map(Argument::argumentName).toList();
    }

    /**
     * The declared name a caller's text spells, at the top, under
     * {@code fields} or {@code fields} itself; empty where the call declares
     * none by that spelling. What comes back is the declaration's own
     * instance, never one made from the text.
     */
    public Optional<ArgumentName> declaredName(String spelled) {
        if (writesFields() && FIELDS.equals(spelled)) {
            return Optional.of(Keywords.FIELDS_NAME);
        }
        return arguments().stream()
            .map(Argument::argumentName)
            .filter(name -> name.value().equals(spelled))
            .findFirst();
    }

    /** The name of {@code fields} as a text names it. */
    public static ArgumentName fieldsName() {
        return Keywords.FIELDS_NAME;
    }

    /** What {@code fields} is, for the refusal that finds it missing. */
    public static String fieldsDescription() {
        return Texts.arguments().fields();
    }

    /** The call a tool name names, if any. */
    public static Optional<AssistantVerb> byCall(String call) {
        return Arrays.stream(values()).filter(v -> v.call.equals(call)).findFirst();
    }

    /** The tool names, as a text lists them. */
    public static List<VerbName> verbNames() {
        return Arrays.stream(values()).map(AssistantVerb::verbName).toList();
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
        public static final String SORT = "sort";
        public static final String DIRECTION = "direction";
        public static final String PAGE_SIZE = "page_size";
        public static final String TYPES = "types";

        private Names() {
        }
    }

    /** The JSON-schema keywords several arguments share, and the name of fields. */
    private static final class Keywords {

        /** The JSON-schema keyword for the value an omitted argument takes. */
        static final String DEFAULT = "default";
        static final Map<String, Object> TYPE_ENUM = Map.of("enum", EntryType.wireNames());
        static final ArgumentName FIELDS_NAME = new ArgumentName(FIELDS);

        private Keywords() {
        }
    }
}
