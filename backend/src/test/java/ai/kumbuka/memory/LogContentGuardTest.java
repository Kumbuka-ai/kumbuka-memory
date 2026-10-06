package ai.kumbuka.memory;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard on what a log line may carry.
 *
 * <p>The convention says: address, selector, number, transition, status, typed
 * reason, duration, scope id — and never a title, a text, metadata, a token, a
 * receipt or the actor. For this service that means at least: never the
 * content of an entry, never its reference, never a conflict token, never the
 * actor. Without something that runs, that sentence is a request.
 *
 * <h2>What this guard does not decide</h2>
 *
 * An address of this service carries a key its caller chose. Whether that key,
 * or the address holding it, may appear in a log line is not decided. The
 * guard therefore neither refuses it nor lists it among the permitted shapes;
 * the permitted fixture leaves it out on purpose.
 *
 * <h2>Why it matters more than it looks</h2>
 *
 * The operator boundary of this service is built as a missing GRANT: there is
 * no privilege that would let the provider read an entry. A log line
 * carrying its content walks around that entirely — the log leaves the container
 * by a different road, and a shipper collecting it delivers exactly the
 * content the database refuses to hand over. The guarantee would still be true
 * of the database and false of the deployment.
 *
 * <p>The actor is excluded for a different reason. It belongs in the audit
 * log, whose collection is governed and whose purpose is to record who changed
 * what. A second, aggregatable stream of the same fact, kept somewhere with
 * different rules, is how not-collecting-behavioural-data gets circumvented
 * without anybody deciding to circumvent it. Correlation runs through a
 * request id.
 *
 * <h2>Identifiers, not substrings</h2>
 *
 * A forbidden thing is looked for as an identifier written in the source, and
 * the identifier is compared by the words it is made of. A search for
 * substrings catches every longer word that happens to contain one: the actor
 * looked for as {@code ctor} reports {@code selector} as carrying the actor,
 * and the selector is on the permitted list. This was measured in the sibling
 * service this guard is copied from.
 *
 * <p>Comparing words rather than whole identifiers is what keeps the reach the
 * substring version had. {@code subjectId} and {@code getTitle} still fall,
 * because {@code subject} and {@code title} are words inside them.
 * {@code selector} and {@code somebody} do not, because {@code actor} and
 * {@code body} are not words inside them — only letters.
 *
 * <h2>No exception reaches a log call</h2>
 *
 * Checking the arguments is not enough. An exception's message is text nobody
 * in this service wrote: a database that refuses a row names the row, and the
 * row holds an entry's content. Handed to the logger, an exception is printed
 * with its message and the messages of its causes, by whatever format the
 * deployment configures. So no log call may take an exception, in any form
 * the logging libraries offer, and this is checked twice:
 *
 * <ul>
 *   <li>in the compiled classes, where every method a class calls is named
 *       with its parameter types. A call to a logging method that takes a
 *       {@code Throwable} is refused whatever the exception is called and
 *       wherever in the argument list it stands — before the format string,
 *       after the message, beside a level;</li>
 *   <li>in the source, for the forms the compiled signature cannot show: an
 *       exception passed as a format parameter, its message, or one made on
 *       the spot. These are typed as {@code Object} or {@code String} by the
 *       time they are compiled.</li>
 * </ul>
 *
 * <p>The typed reason of a refusal stays permitted, reached through the
 * exception that carries it: {@code e.reason()} is a constant out of a
 * closed set. Anything else built from an exception goes through a local
 * first, which is where somebody has to decide what it holds.
 *
 * <h2>The cardinality trap</h2>
 *
 * A guard that walks a tree and finds no log calls passes. It also passes when
 * the logging was deleted, when the pattern stopped matching, and when it is
 * pointed at the wrong directory. So this asserts a minimum count as well: the
 * check must have had something to check. That applies to the permitted
 * fixture too — a green result over an empty directory says nothing.
 */
class LogContentGuardTest {

    /**
     * Words a log call's arguments must never contain.
     *
     * <p>Matched on the argument expression rather than on a resolved value,
     * because the point is to catch the habit — {@code log.debugf("...%s", e)}
     * while debugging a race — at the moment somebody writes it.
     *
     * <p>Each entry is a whole word, compared case-insensitively against the
     * words an identifier is made of. So {@code actor} covers {@code actor},
     * {@code Actor}, {@code getActor} and {@code e.actor()}, and does not
     * cover {@code selector}.
     */
    private static final List<String> FORBIDDEN_WORDS = List.of(
        // What an entry says. The fields the operator boundary exists for.
        "content", "reference",
        // The convention's own words for content, as its sibling service names it.
        "title", "body", "text",
        // Free text belonging to the caller.
        "metadata",
        // A conflict token or a receipt: either lets its holder act.
        "token", "receipt",
        // Who did it. That is the audit log's business, under its own rules.
        "subject", "principal", "actor");

    /**
     * Arguments that ARE a whole entity.
     *
     * <p>Matched exactly rather than by word: printing an entity prints its
     * title and its metadata along with everything else, and the habit looks
     * harmless because the call site says nothing about content. An exact
     * match is used because a bare identifier is too short to look for any
     * other way — {@code e} is a word inside a great many identifiers.
     */
    private static final List<String> FORBIDDEN_WHOLE_ARGUMENTS = List.of(
        "e", "entry", "memory", "entity", "row");

    /** Below this the guard is not measuring the tree it thinks it is. */
    private static final int MINIMUM_LOG_CALLS = 8;

    /**
     * One log call per item the convention permits — selector, number,
     * transition, status, typed reason, duration, scope id; the address is
     * left out because its admission is undecided here — plus the typed
     * reason written as a constant for each of the six reason names that
     * collide with the forbidden list.
     */
    private static final int MINIMUM_ALLOWED_FIXTURE_LOG_CALLS = 13;

    private static final Pattern LOG_CALL = Pattern.compile(
        "LOG\\.(trace|debug|info|warn|error|fatal|log)[fv]?\\(([^;]*)\\)\\s*;",
        Pattern.DOTALL);

    /**
     * The types a logging call can be made on. Every logging method these
     * offer that takes an exception takes it as {@code Throwable}.
     */
    private static final Set<String> LOGGING_TYPES = Set.of(
        "org/jboss/logging/Logger", "org/jboss/logging/BasicLogger",
        "org/jboss/logging/DelegatingBasicLogger", "io/quarkus/logging/Log",
        "org/jboss/logmanager/Logger", "java/util/logging/Logger", "java/lang/System$Logger");

    private static final String THROWABLE = "Ljava/lang/Throwable;";

    /** Below this the compiled check is not reading the classes it thinks it is. */
    private static final int MINIMUM_CLASSES = 30;

    /** Below this the compiled check is not finding the logging calls it should. */
    private static final int MINIMUM_LOGGING_REFERENCES = 8;

    /**
     * A variable declared with an exception type: a parameter, a local, a
     * field, a single-type catch, a loop variable.
     */
    private static final Pattern THROWABLE_DECLARED = Pattern.compile(
        "\\b(?:[A-Z][A-Za-z0-9_$]*(?:Exception|Error)|Throwable)\\s+([a-z_$][A-Za-z0-9_$]*)"
            + "\\s*[,)=;:]");

    /** The variable of a catch clause, multi-catch included. */
    private static final Pattern CATCH_VARIABLE = Pattern.compile(
        "catch\\s*\\(\\s*(?:final\\s+)?[A-Za-z0-9_$.|\\s]+?\\s+([a-z_$][A-Za-z0-9_$]*)\\s*\\)");

    /** An exception made inside the call. */
    private static final Pattern THROWABLE_MADE = Pattern.compile(
        "\\bnew\\s+(?:[A-Za-z0-9_$]+\\.)*[A-Z][A-Za-z0-9_$]*(?:Exception|Error|Throwable)\\s*\\(");

    /** A message read off something, whatever it was read off. */
    private static final Pattern MESSAGE_READ = Pattern.compile(
        "\\.get(?:Localized)?Message\\s*\\(");

    private static final Pattern STRING_LITERAL = Pattern.compile(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])+'");

    /** An identifier as Java spells one. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * The boundaries inside an identifier: {@code scopeId} is two words,
     * {@code SCOPE_UNRESOLVED} is two words, {@code SELECTOR} is one.
     */
    private static final Pattern WORD_BOUNDARY = Pattern.compile(
        "[_$]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    @Test
    void no_log_call_carries_content_the_operator_boundary_withholds() throws IOException {
        Findings findings = scan(sourceRoot("main"));

        assertThat(findings.total())
            .as("the guard must have found log calls at all. A walk that finds none passes "
                + "for every reason including the wrong ones: logging deleted, pattern "
                + "stale, directory wrong")
            .isGreaterThanOrEqualTo(MINIMUM_LOG_CALLS);

        assertThat(findings.offenders())
            .as("a log line may carry a selector, a number, a transition, a status, a "
                + "typed reason, a duration and a scope id. Never an entry's content or "
                + "its reference, a conflict token, metadata text, a receipt or the "
                + "actor — the operator boundary is a missing GRANT, and a shipper "
                + "carrying content out of the container delivers what the database "
                + "refuses")
            .isEmpty();
    }

    /**
     * The green counter-probe, observed on every build.
     *
     * <p>A fixture carries one log call per permitted item, written with the
     * bare identifiers a developer actually reaches for. All of them must come
     * back clean. Without this, the empty result over the main sources is a
     * check nobody has seen say yes — and a check that only ever says no is
     * indistinguishable from one that has quietly started refusing things the
     * convention allows. That is not hypothetical: {@code selector} was
     * reported as carrying the actor, and the first developer to log a
     * selector would have got a red build saying their call carried
     * {@code ctor}.
     */
    @Test
    void the_guard_passes_every_shape_the_convention_permits() throws IOException {
        Findings findings = scan(allowedFixtureRoot());

        assertThat(findings.total())
            .as("the permitted fixture must have been read. A green result over a "
                + "directory the guard found nothing in is the same green it would give "
                + "for a deleted fixture or a stale pattern, and it is the reason this "
                + "counter-probe would otherwise prove nothing")
            .isGreaterThanOrEqualTo(MINIMUM_ALLOWED_FIXTURE_LOG_CALLS);

        assertThat(findings.offenders())
            .as("every one of these is on the permitted list: a selector, a number, a "
                + "transition, a status, a typed reason, a duration and a scope id. A "
                + "guard that reports one of them is refusing what the convention "
                + "allows, and the developer who hits it gets a red build about a rule "
                + "that does not exist")
            .isEmpty();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>A fixture carries the two log calls the convention exists to stop —
     * one printing the content, one printing a whole entity — and the guard has to
     * name both. Without this the empty result over the main sources is a
     * query that finds nothing rather than a tree that contains nothing.
     */
    @Test
    void the_guard_catches_a_log_call_carrying_content_and_one_carrying_an_entity()
            throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        assertThat(findings.offenders())
            .as("RED STATE, observed: the fixture logs an entry's content and logs a whole "
                + "entry, and both must be reported. These are the two shapes somebody reaches "
                + "for while debugging a race, which is why the convention carries a "
                + "guard rather than a javadoc line")
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(findings.offenders().toString()).contains("ForbiddenLogFixture");
    }

    /**
     * The red state for every other thing the convention withholds.
     *
     * <p>The content and the whole entity have their own test above, and the
     * actor has one below. This covers the rest of the forbidden list one
     * entry at a time, because a count cannot tell which of them is still
     * being caught. That matters most exactly when a rule is added to let
     * something through: the way such a rule fails is by letting one more
     * thing through than intended, and a test that only counts offences would
     * stay green while it happened.
     */
    @Test
    void the_guard_catches_every_thing_the_convention_withholds() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        for (String written : List.of("e.content", "e.reference", "conflictToken",
                "e.metadata", "e.title", "e.body", "receipt", "subject")) {
            assertThat(findings.offenders())
                .as("RED STATE, observed: a log call carrying '%s' must be reported, and "
                    + "the report must name what it carries", written)
                .anySatisfy(offence -> {
                    assertThat(offence).endsWith(", " + written);
                    assertThat(offence.substring(0, offence.indexOf(" — ")))
                        .containsIgnoringCase(written.replace("e.", ""));
                });
        }
    }

    /**
     * The red state for the actor specifically, in each shape it is written.
     *
     * <p>The actor is the entry that motivated the substring search in the
     * first place, and narrowing the check to identifiers is exactly the move
     * that could drop it along with the false positive. Dropping it would be
     * the quiet kind of damage: the guard stays green, the convention still
     * says the actor is forbidden, and nothing runs that would notice the
     * difference. So each of the four shapes gets its own assertion, and each
     * report has to name the actor rather than merely count.
     */
    @Test
    void the_guard_catches_the_actor_in_each_shape_it_is_written() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        for (String shape : List.of("actor", "Actor.MEMBER", "e.getActor()", "e.actor()")) {
            assertThat(findings.offenders())
                .as("RED STATE, observed: the actor written as '%s' must be reported, and "
                    + "the report must name the actor — an offence the developer cannot "
                    + "trace back to a word in their own call is how '%s carries ctor' "
                    + "happened", shape, "selector")
                .anySatisfy(offence -> {
                    assertThat(offence).contains("\"changed by %s\", " + shape);
                    assertThat(offence.substring(0, offence.indexOf(" — ")))
                        .containsIgnoringCase("actor");
                });
        }
    }

    /**
     * No compiled class of the main tree calls a logging method that takes an
     * exception.
     */
    @Test
    void no_compiled_class_hands_an_exception_to_a_logging_call() throws IOException {
        Compiled compiled = scanCompiled(classRoot("classes"));

        assertThat(compiled.classes())
            .as("the compiled main tree must have been read. A walk over a directory "
                + "without classes passes for every reason including the wrong ones")
            .isGreaterThanOrEqualTo(MINIMUM_CLASSES);
        assertThat(compiled.loggingReferences())
            .as("the compiled check must have found logging calls at all, or it is not "
                + "reading the constant pools it thinks it is")
            .isGreaterThanOrEqualTo(MINIMUM_LOGGING_REFERENCES);

        assertThat(compiled.offenders())
            .as("a log call never takes an exception. An exception's message is text nobody "
                + "in the service wrote, and a database's message names the row it "
                + "rejected, which holds the content; the logger prints it with every "
                + "cause. Log the reference and the shape of the failure instead")
            .isEmpty();
    }

    /**
     * The red state for every form a logging library takes an exception in,
     * compiled. The fixture calls each of them, and each must be named.
     */
    @Test
    void the_guard_catches_every_compiled_form_of_handing_over_an_exception()
            throws IOException {
        Compiled compiled = scanCompiled(classRoot("test-classes")
            .resolve("ai/kumbuka/memory/fixture"));

        for (String form : List.of("org/jboss/logging/Logger.errorf(Ljava/lang/Throwable;",
                "org/jboss/logging/Logger.error(Ljava/lang/Object;Ljava/lang/Throwable;)",
                "org/jboss/logging/Logger.log(Lorg/jboss/logging/Logger$Level;"
                    + "Ljava/lang/Object;Ljava/lang/Throwable;)",
                "org/jboss/logging/Logger.warnv(Ljava/lang/Throwable;",
                "java/util/logging/Logger.log(Ljava/util/logging/Level;Ljava/lang/String;"
                    + "Ljava/lang/Throwable;)")) {
            assertThat(compiled.offenders())
                .as("RED STATE, observed: the fixture calls %s, and it must be reported", form)
                .anySatisfy(offence -> {
                    assertThat(offence).startsWith("ThrowableLogFixture");
                    assertThat(offence).contains(form);
                });
        }
    }

    /**
     * The red state for the forms only the source shows: an exception as a
     * format parameter, its message, one made on the spot, one caught in a
     * multi-catch.
     */
    @Test
    void the_guard_catches_an_exception_handed_over_as_a_parameter() throws IOException {
        Findings findings = scan(forbiddenFixtureRoot());

        for (String written : List.of("failure'", "failure.getMessage()'",
                "String.valueOf(broken)'", "new IllegalStateException(")) {
            assertThat(findings.offenders())
                .as("RED STATE, observed: a log call handing over %s must be reported", written)
                .anySatisfy(offence -> assertThat(offence)
                    .startsWith("ThrowableLogFixture.java: LOG call hands over an exception '"
                        + written));
        }
    }

    /**
     * The green counter-probe for the exception check: a typed reason read
     * off a refusal, and a failure's frames assembled into a local first.
     */
    @Test
    void the_guard_passes_a_typed_reason_and_a_failure_s_frames() throws IOException {
        Compiled compiled = scanCompiled(classRoot("test-classes")
            .resolve("ai/kumbuka/memory/fixture/allowed"));
        assertThat(compiled.loggingReferences())
            .as("the permitted fixture's classes must have been read")
            .isPositive();
        assertThat(compiled.offenders())
            .as("none of the permitted fixtures hands an exception to a logging call")
            .isEmpty();

        assertThat(scan(allowedFixtureRoot()).offenders())
            .as("a typed reason read off a refusal is on the permitted list, and so is a "
                + "local the failure's frames were assembled into")
            .isEmpty();
    }

    private static Findings scan(Path root) throws IOException {
        List<String> offenders = new ArrayList<>();
        int total = 0;

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                Set<String> throwables = throwablesDeclaredIn(source);
                Matcher m = LOG_CALL.matcher(source);
                while (m.find()) {
                    total++;
                    String call = m.group(2);
                    String handedOver = exceptionIn(call, throwables);
                    if (handedOver != null) {
                        offenders.add(file.getFileName() + ": LOG call hands over an exception '"
                            + handedOver + "' — " + call.strip());
                    }
                    // The format string is quoted; only what follows it can be
                    // an argument, and only arguments can carry content.
                    String tail = call.substring(Math.max(call.lastIndexOf('"') + 1, 0));
                    String offence = offenceIn(tail);
                    if (offence != null) {
                        offenders.add(file.getFileName() + ": LOG call carries '" + offence
                            + "' — " + call.strip());
                    }
                }
            }
        }
        return new Findings(total, offenders);
    }

    /**
     * The first forbidden thing among a call's arguments, or null.
     *
     * <p>The identifier is returned as it was written rather than as the word
     * that matched it, so the report names something the developer can find by
     * searching their own call. Whole entities are checked after identifiers
     * and by splitting on commas, so that a bare entity can be matched
     * exactly.
     */
    private static String offenceIn(String argumentTail) {
        Matcher identifiers = IDENTIFIER.matcher(argumentTail);
        while (identifiers.find()) {
            String written = identifiers.group();
            if (wordsIn(written).stream().anyMatch(FORBIDDEN_WORDS::contains)) {
                return written;
            }
        }
        for (String argument : argumentTail.split(",")) {
            String bare = argument.strip();
            if (FORBIDDEN_WHOLE_ARGUMENTS.contains(bare)) {
                return bare + " (a whole entity)";
            }
        }
        return null;
    }

    /**
     * The words an identifier is made of, lower-cased.
     *
     * <p>{@code getActor} is {@code get} and {@code actor}; {@code scopeId} is
     * {@code scope} and {@code id}; {@code selector} is one word and is not
     * any of them.
     *
     * <p>A constant is the exception and is one word: its whole name. See
     * {@link #isConstant(String)}.
     */
    private static List<String> wordsIn(String identifier) {
        if (isConstant(identifier)) {
            return List.of(identifier.toLowerCase(Locale.ROOT));
        }
        List<String> words = new ArrayList<>();
        for (String word : WORD_BOUNDARY.split(identifier)) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /**
     * Whether an identifier is written the way Java writes a constant: no
     * lower-case letter anywhere in it.
     *
     * <p>A constant out of a closed set carries a category, never content.
     * {@code ACTOR_UNKNOWN} says the caller had no subject; it does not say
     * who the caller was. That is precisely why the typed reason is on the
     * permitted list, and it is why a constant's name is compared whole rather
     * than split into words. Split into words, {@code CONFLICT_TOKEN_STALE}
     * reads as the token, {@code CONTENT_OVERSIZE} as the content and
     * {@code ACTOR_UNKNOWN} as the actor — category names reported as the
     * content they exist to describe. The main sources already write this
     * form: {@code CallerActor} logs
     * {@code MemoryException.Reason.ACTOR_UNKNOWN}.
     *
     * <p>Whole-name comparison is not a blanket exemption. A constant that
     * names nothing but a forbidden thing is still reported, because its whole
     * name IS the forbidden word: {@code ACTOR}, {@code TITLE} and
     * {@code CONTENT} all fall. What passes is a name that says something about
     * the forbidden thing rather than being it.
     *
     * <p>The price, stated rather than hidden: this opens a gap for an
     * identifier that looks like a constant and carries content, such as a
     * hypothetical {@code ENTRY_CONTENT}. The gap is narrow, because a
     * constant is fixed in the source and an entry's content is not — the
     * content this guard exists to keep out of the log does not exist at
     * compile time. It is weighed against the alternative, which is a guard
     * that reports three permitted reason names as content and teaches the
     * next developer to delete a word from the forbidden list.
     */
    private static boolean isConstant(String identifier) {
        return identifier.chars().noneMatch(Character::isLowerCase)
            && identifier.chars().anyMatch(Character::isLetter);
    }

    /** The names this source declares with an exception type. */
    private static Set<String> throwablesDeclaredIn(String source) {
        Set<String> names = new HashSet<>();
        for (Pattern declared : List.of(THROWABLE_DECLARED, CATCH_VARIABLE)) {
            Matcher m = declared.matcher(source);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * The first argument of a log call that hands over an exception, or null.
     *
     * <p>An exception variable may appear only to read its typed reason;
     * every other use — bare, as a parameter of a further call, through an
     * accessor — is reported. Every argument is looked at, the ones before
     * the format string included.
     */
    private static String exceptionIn(String call, Set<String> throwables) {
        for (String argument : argumentsOf(call)) {
            String code = STRING_LITERAL.matcher(argument).replaceAll("\"\"");
            if (THROWABLE_MADE.matcher(code).find() || MESSAGE_READ.matcher(code).find()) {
                return argument;
            }
            Matcher identifiers = IDENTIFIER.matcher(code);
            while (identifiers.find()) {
                boolean member = identifiers.start() > 0
                    && code.charAt(identifiers.start() - 1) == '.';
                if (!member && throwables.contains(identifiers.group())
                        && !code.startsWith(".reason()", identifiers.end())) {
                    return argument;
                }
            }
        }
        return null;
    }

    /** A call's arguments, split at the commas that are not nested or quoted. */
    private static List<String> argumentsOf(String call) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        Character quote = null;
        for (int i = 0; i < call.length(); i++) {
            char c = call.charAt(i);
            if (quote != null) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = null;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(call.substring(start, i).strip());
                start = i + 1;
            }
        }
        arguments.add(call.substring(start).strip());
        return arguments;
    }

    /**
     * Every class file under a root, read for the methods it calls on a
     * logging type.
     */
    private static Compiled scanCompiled(Path root) throws IOException {
        List<String> offenders = new ArrayList<>();
        int classes = 0;
        int references = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".class"))::iterator) {
                classes++;
                for (String called : loggingCallsIn(file)) {
                    references++;
                    String parameters = called.substring(called.indexOf('('),
                        called.indexOf(')') + 1);
                    if (parameters.contains(THROWABLE)) {
                        offenders.add(file.getFileName() + ": calls " + called);
                    }
                }
            }
        }
        return new Compiled(classes, references, offenders);
    }

    /**
     * The methods a class file names on a logging type, as
     * {@code owner.name(descriptor)}.
     *
     * <p>Read off the constant pool, where every method a class calls is
     * named with its owner and its descriptor. Reading the pool needs no
     * library, and it is all this check needs: whether a logging method that
     * takes a {@code Throwable} is called at all.
     */
    private static List<String> loggingCallsIn(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(classFile)))) {
            assertThat(in.readInt()).as("%s is a class file", classFile).isEqualTo(0xCAFEBABE);
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] className = new int[count];
            int[][] method = new int[count][];
            int[][] nameAndType = new int[count][];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7 -> className[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 17, 18 -> in.readInt();
                    case 10, 11 -> method[i] = new int[] {in.readUnsignedShort(),
                        in.readUnsignedShort()};
                    case 12 -> nameAndType[i] = new int[] {in.readUnsignedShort(),
                        in.readUnsignedShort()};
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IllegalStateException(
                        "constant tag " + tag + " in " + classFile + " is not one this reader "
                            + "knows, so the pool cannot be read past it");
                }
            }
            List<String> called = new ArrayList<>();
            for (int[] ref : method) {
                if (ref == null) {
                    continue;
                }
                String owner = utf8[className[ref[0]]];
                if (LOGGING_TYPES.contains(owner)) {
                    int[] signature = nameAndType[ref[1]];
                    called.add(owner + "." + utf8[signature[0]] + utf8[signature[1]]);
                }
            }
            return called;
        }
    }

    private static Path classRoot(String classes) {
        Path direct = Paths.get("target", classes);
        Path fromRepoRoot = Paths.get("backend", "target", classes);
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("compiled classes %s must exist — run from the module directory after "
                + "compiling", root)
            .isTrue();
        return root;
    }

    private static Path forbiddenFixtureRoot() {
        return sourceRoot("test").resolve("ai/kumbuka/memory/fixture");
    }

    private static Path allowedFixtureRoot() {
        return forbiddenFixtureRoot().resolve("allowed");
    }

    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
    }

    private record Findings(int total, List<String> offenders) {
    }

    private record Compiled(int classes, int loggingReferences, List<String> offenders) {
    }
}
