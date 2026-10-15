/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No text addressed to a caller lives in Java code (code-conventions/
 * service-construction.md, "texts addressed to a caller", points 1 and 3).
 *
 * <h2>What it holds</h2>
 *
 * <ul>
 *   <li>Every message bundle's methods carry {@code @Message} without a value,
 *       so a text lives in the bundle's file and nowhere else, and the build
 *       fails when the file lacks one. The file's keys are exactly the
 *       bundle's methods: a text nothing can render is a text nobody checks.</li>
 *   <li>One set of texts: no {@code @Localized} bundle and no file with a
 *       locale suffix.</li>
 *   <li>No string literal of prose in the main tree stands anywhere but in a
 *       logger call or in the note of a developer exception
 *       ({@link #DEVELOPER_NOTES}), which no surface answers with: an
 *       unforeseen failure is {@code UNEXPECTED_FAILURE} with a reference, and
 *       its message goes nowhere. A sentence in a refusal, an answer, a
 *       constant or a description is a text in Java code.</li>
 * </ul>
 *
 * <h2>The two packages that speak SQL</h2>
 *
 * {@code repository} and {@code tenancy} write statements to the database, and
 * a statement reads as prose to any test of words. They are walked all the
 * same for what would make a literal reach a caller: neither may construct a
 * refusal or reach a payload, so nothing written there is a text for a
 * caller. That is held below, not assumed.
 */
class MessageBundleTest {

    private static final String MAIN = "main";

    /** Calls whose literals are notes for the operator's log. */
    static final Set<String> LOGGER_METHODS = Set.of("trace", "tracef", "debug", "debugf",
        "info", "infof", "warn", "warnf", "error", "errorf");

    /** Exceptions whose message is a note for a developer, never for a caller. */
    static final Set<String> DEVELOPER_NOTES = Set.of("IllegalStateException",
        "IllegalArgumentException", "UncheckedIOException", "UnsupportedOperationException",
        "AssertionError");

    /** The packages whose statements are SQL, walked for what they may not do. */
    private static final Set<String> SQL_LAYERS = Set.of("repository", "tenancy");

    /** What would let a literal in an SQL layer reach a caller. */
    private static final Pattern REACHES_A_CALLER = Pattern.compile(
        "\\b(MemoryException|SurfaceException|CallException|adapter\\.payload|Texts)\\b");

    private static final Pattern LOCALE_SUFFIX =
        Pattern.compile("_[a-z]{2}(_[A-Z]{2})?\\.properties$");

    // ======================================================================
    // The bundles
    // ======================================================================

    @Test
    void every_bundle_method_carries_no_text_and_its_file_carries_exactly_its_texts() {
        List<Class<?>> bundles = bundles();
        assertThat(bundles)
            .as("the service addresses texts to callers, so it has bundles to hold them")
            .isNotEmpty();
        List<String> offences = new ArrayList<>();
        for (Class<?> bundle : bundles) {
            offences.addAll(valuedMessages(bundle));
            offences.addAll(fileMismatch(bundle));
        }
        assertThat(offences)
            .as("a text belongs in the bundle's file under messages/, and the file holds "
                + "the bundle's texts and no others")
            .isEmpty();
    }

    @Test
    void one_set_of_texts_and_no_localized_bundle() {
        List<String> localized = SourceTree.files(SourceTree.root(MAIN)).stream()
            .filter(file -> SourceTree.code(file).contains("@Localized"))
            .map(Path::toString)
            .toList();
        assertThat(localized).as("no variant per deployment, tenant or call").isEmpty();
        try (Stream<Path> files = Files.list(messages())) {
            assertThat(files.map(Path::getFileName).map(Path::toString)
                    .filter(name -> LOCALE_SUFFIX.matcher(name).find()))
                .as("one set of texts per service: no file for another locale")
                .isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ======================================================================
    // Prose in Java code
    // ======================================================================

    @Test
    void no_string_of_prose_reaches_the_surface() {
        Path root = SourceTree.root(MAIN);
        List<String> offences = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (SQL_LAYERS.contains(SourceTree.layerOf(root, file))) {
                continue;
            }
            offences.addAll(proseIn(SourceTree.fqcn(root, file), SourceTree.text(file)));
        }
        assertThat(offences)
            .as("a sentence for a caller belongs in a message bundle; in Java code a "
                + "sentence is a note for the log or for a developer exception")
            .isEmpty();
    }

    @Test
    void the_sql_layers_reach_no_caller() {
        Path root = SourceTree.root(MAIN);
        List<String> reaching = SourceTree.files(root).stream()
            .filter(file -> SQL_LAYERS.contains(SourceTree.layerOf(root, file)))
            .filter(file -> REACHES_A_CALLER.matcher(SourceTree.code(file)).find())
            .map(file -> SourceTree.fqcn(root, file))
            .toList();
        assertThat(reaching)
            .as("the exemption for SQL holds only while nothing in these packages can "
                + "carry a literal to a caller")
            .isEmpty();
    }

    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(proseIn("Planted", "class Planted { void f() { throw new MemoryException("
                + "Reason.SCOPE_LOCKED, \"the scope is locked for writes\"); } }"))
            .as("RED STATE, observed: a sentence handed to a refusal is reported")
            .hasSize(1);
        assertThat(proseIn("Planted", "class Planted { static final String TEXT = "
                + "\"Do not send this argument\"; }"))
            .as("RED STATE, observed: a sentence kept in a constant is reported")
            .hasSize(1);
        assertThat(proseIn("Planted", "class Planted { void f() { LOG.warnf(\"a scope was "
                + "refused: %s\", r); throw new IllegalStateException(\"the column and "
                + "the mapping have parted\"); } }"))
            .as("RED STATE, observed: a note for the log or a developer passes")
            .isEmpty();
        assertThat(valuedMessages(PlantedBundle.class))
            .as("RED STATE, observed: a bundle method with its text in @Message is "
                + "reported")
            .hasSize(1);
    }

    /** A bundle as a careless change would write it; never annotated, never built. */
    interface PlantedBundle {

        @Message("Send the type again")
        String planted();

        @Message
        String kept();
    }

    // ======================================================================

    private static List<String> proseIn(String where, String source) {
        return ProseLiterals.in(source).stream()
            .filter(found -> !ProseLiterals.within(found, LOGGER_METHODS, DEVELOPER_NOTES))
            .map(found -> where + ":" + found.line() + " \"" + found.text() + "\"")
            .toList();
    }

    private static List<String> valuedMessages(Class<?> bundle) {
        String unset = defaultOf("value");
        String unsetDefault = defaultOf("defaultValue");
        return Arrays.stream(bundle.getDeclaredMethods())
            .filter(method -> !method.isSynthetic() && !method.isDefault())
            .filter(method -> {
                Message message = method.getAnnotation(Message.class);
                return message == null || !unset.equals(message.value())
                    || !unsetDefault.equals(message.defaultValue());
            })
            .map(method -> bundle.getSimpleName() + "." + method.getName()
                + " carries its text in Java, or no @Message")
            .toList();
    }

    private static List<String> fileMismatch(Class<?> bundle) {
        String name = bundle.getAnnotation(MessageBundle.class).value();
        Path file = messages().resolve(name + ".properties");
        if (!Files.isRegularFile(file)) {
            return List.of(bundle.getSimpleName() + " has no file " + file);
        }
        Set<String> methods = new TreeSet<>();
        for (Method method : bundle.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                methods.add(method.getName());
            }
        }
        Set<String> keys = new TreeSet<>(texts(file).stringPropertyNames());
        return methods.equals(keys) ? List.of()
            : List.of(name + ".properties holds " + keys + " for the methods " + methods);
    }

    private static String defaultOf(String element) {
        try {
            return (String) Message.class.getMethod(element).getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every interface of the main tree annotated as a message bundle. */
    static List<Class<?>> bundles() {
        Path root = SourceTree.root(MAIN);
        List<Class<?>> bundles = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (!SourceTree.code(file).contains("@MessageBundle")) {
                continue;
            }
            try {
                Class<?> type = Class.forName(SourceTree.fqcn(root, file));
                if (type.isAnnotationPresent(MessageBundle.class)) {
                    bundles.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return bundles;
    }

    static Path messages() {
        Path direct = Path.of("src", MAIN, "resources", "messages");
        return Files.isDirectory(direct) ? direct
            : Path.of("backend", "src", MAIN, "resources", "messages");
    }

    static Properties texts(Path file) {
        Properties texts = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            texts.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return texts;
    }

    /**
     * The string literals of prose in a Java source, and the call each stands in.
     *
     * <p>A small tokenizer rather than a regular expression over lines, because
     * the question is structural: a sentence handed to a logger is a note for the
     * operator, and the same sentence handed to a refusal or kept in a constant
     * is a text that can reach a caller. Which of the two it is depends on the
     * innermost open call around the literal, and that spans lines and
     * concatenations. Comments are skipped, and a {@code //} inside a literal is
     * read as part of the literal, which a line-based strip gets wrong.
     *
     * <p>Prose is three or more words written with spaces. A name, a path, a
     * pattern, a key or a single word is not, so the declared vocabulary the code
     * has to spell -- argument names, codes, JSON keys -- never counts.
     */
    static final class ProseLiterals {

        /** Three words or more, joined by spaces and the punctuation of a sentence. */
        private static final Pattern PROSE =
            Pattern.compile("[A-Za-z][A-Za-z'-]*(?:[,;:.]? +[A-Za-z][A-Za-z'-]*){2,}");

        /** A sentence has a word in lower case; a list of methods or a constant does not. */
        private static final Pattern LOWER_WORD = Pattern.compile("\\b[a-z]{2,}\\b");

        private ProseLiterals() {
        }

        /**
         * One literal of prose.
         *
         * @param line      the line it starts on
         * @param text      the literal's content
         * @param call      the innermost call open around it, or empty at none
         * @param construct whether that call is a {@code new}
         */
        record Found(int line, String text, String call, boolean construct) {
        }

        /** Every literal of prose in a source text. */
        static List<Found> in(String source) {
            List<Found> found = new ArrayList<>();
            Deque<String[]> calls = new ArrayDeque<>();
            int line = 1;
            int i = 0;
            int n = source.length();
            while (i < n) {
                char c = source.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                } else if (source.startsWith("//", i)) {
                    while (i < n && source.charAt(i) != '\n') {
                        i++;
                    }
                } else if (source.startsWith("/*", i)) {
                    int end = source.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    line += count(source, i, end);
                    i = end;
                } else if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? n : end;
                    record(found, calls, line, source.substring(i + 3, end));
                    line += count(source, i, end);
                    i = end + 3;
                } else if (c == '"') {
                    int end = i + 1;
                    while (end < n && source.charAt(end) != '"') {
                        end += source.charAt(end) == '\\' ? 2 : 1;
                    }
                    record(found, calls, line, source.substring(i + 1, Math.min(end, n)));
                    i = end + 1;
                } else if (c == '\'') {
                    int end = i + 1;
                    while (end < n && source.charAt(end) != '\'') {
                        end += source.charAt(end) == '\\' ? 2 : 1;
                    }
                    i = end + 1;
                } else if (c == '(') {
                    String[] call = callBefore(source, i);
                    // A parenthesis that follows no name groups an expression and
                    // stands in the call around it.
                    calls.push(call[0].isEmpty() && !calls.isEmpty() ? calls.peek() : call);
                    i++;
                } else if (c == ')') {
                    if (!calls.isEmpty()) {
                        calls.pop();
                    }
                    i++;
                } else {
                    i++;
                }
            }
            return found;
        }

        /** Whether a literal stands in one of the named calls. */
        static boolean within(Found literal, Set<String> methods, Set<String> constructed) {
            return literal.construct()
                ? constructed.contains(literal.call())
                : methods.contains(literal.call());
        }

        private static void record(List<Found> into, Deque<String[]> calls, int line,
                                   String text) {
            if (!PROSE.matcher(text).find() || !LOWER_WORD.matcher(text).find()) {
                return;
            }
            String[] call = calls.isEmpty() ? new String[] {"", ""} : calls.peek();
            into.add(new Found(line, text, call[0], "new".equals(call[1])));
        }

        /** The identifier before an opening parenthesis, and the word before that. */
        private static String[] callBefore(String source, int open) {
            int end = open;
            while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
                end--;
            }
            int start = end;
            while (start > 0 && (Character.isJavaIdentifierPart(source.charAt(start - 1))
                || source.charAt(start - 1) == '.')) {
                start--;
            }
            String name = source.substring(start, end);
            name = name.substring(name.lastIndexOf('.') + 1);
            int before = start;
            while (before > 0 && Character.isWhitespace(source.charAt(before - 1))) {
                before--;
            }
            int wordStart = before;
            while (wordStart > 0 && Character.isJavaIdentifierPart(source.charAt(wordStart - 1))) {
                wordStart--;
            }
            return new String[] {name, source.substring(wordStart, before)};
        }

        private static int count(String source, int from, int to) {
            int lines = 0;
            for (int at = from; at < to && at < source.length(); at++) {
                if (source.charAt(at) == '\n') {
                    lines++;
                }
            }
            return lines;
        }
    }
}
