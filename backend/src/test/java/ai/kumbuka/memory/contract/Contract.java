package ai.kumbuka.memory.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The contract of the assistant surface, read from its copy on the test
 * classpath.
 *
 * <p>Every expected value a conformance probe asserts comes from here: tool
 * names, descriptions, arguments, message patterns, the next-step table and
 * the texts it names. None comes from the declaration in the code or from an
 * answer of the service, because a probe whose expectation is drawn from the
 * thing it checks can only ever agree with it.
 *
 * <p>The copy is character for character the contract document at the
 * commit the surface was built against. Each parser below refuses to return
 * nothing: a probe over an empty result passes for every reason, the wrong
 * ones included.
 */
public final class Contract {

    private static final String RESOURCE = "/contract/assistant-surface.md";
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z ]+)>");

    private Contract() {
    }

    public static String text() {
        try (InputStream in = Contract.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole document with every run of whitespace made one space. */
    private static String flat() {
        return text().replaceAll("\\s+", " ");
    }

    // ======================================================================
    // Section 5 — the verbs
    // ======================================================================

    /** Tool name to its normative description, quoted lines joined by one space. */
    public static Map<String, String> descriptions() {
        Map<String, String> described = new LinkedHashMap<>();
        List<String> lines = List.of(text().split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            String name = boldCallName(lines.get(i));
            if (name == null) {
                continue;
            }
            List<String> quoted = new ArrayList<>();
            for (int j = i + 1; j < lines.size() && (lines.get(j).startsWith(">")
                    || (quoted.isEmpty() && lines.get(j).isBlank())); j++) {
                if (lines.get(j).startsWith(">")) {
                    quoted.add(lines.get(j).substring(1).trim());
                }
            }
            described.put(name, String.join(" ", quoted));
        }
        requireFound(described.size(), "tool descriptions in section 5");
        return described;
    }

    /** The tool names, in the contract's order. */
    public static List<String> tools() {
        return List.copyOf(descriptions().keySet());
    }

    /** The tool that carries a step: {@code read} names {@code memory_read}. */
    public static String toolFor(String step) {
        return tools().stream().filter(t -> t.endsWith("_" + step)).findFirst()
            .orElseThrow(() -> new IllegalStateException("no tool for the step " + step));
    }

    /**
     * The arguments section 5 gives a tool: top-level names and names under
     * {@code fields}, each marked required or optional.
     */
    public static Arguments argumentsOf(String tool) {
        String paragraph = paragraphAfterDescriptionOf(tool);
        String top = between(paragraph, "Top-level:", "Under `fields`:", "Callable", "Result:");
        String fields = paragraph.contains("Under `fields`:")
            ? between(paragraph, "Under `fields`:", "Callable", "Result:")
            : "";
        boolean atLeastOne = fields.contains("at least one of");
        return new Arguments(namesIn(top, false), namesIn(fields, atLeastOne));
    }

    /** What section 5 says a tool takes. Name to whether it is required. */
    public record Arguments(Map<String, Boolean> top, Map<String, Boolean> fields) {
    }

    private static Map<String, Boolean> namesIn(String text, boolean allOptional) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(optional )?`([a-z_]+)`").matcher(text);
        while (m.find()) {
            names.put(m.group(2), !allOptional && m.group(1) == null);
        }
        return names;
    }

    private static String paragraphAfterDescriptionOf(String tool) {
        List<String> lines = List.of(text().split("\n", -1));
        int at = lines.indexOf("**`" + tool + "`**");
        if (at < 0) {
            throw new IllegalStateException(tool + " is not a heading in section 5");
        }
        int i = at + 1;
        while (i < lines.size() && (lines.get(i).isBlank() || lines.get(i).startsWith(">"))) {
            i++;
        }
        StringBuilder paragraph = new StringBuilder();
        while (i < lines.size() && !lines.get(i).isBlank()) {
            paragraph.append(lines.get(i)).append(' ');
            i++;
        }
        return paragraph.toString();
    }

    private static String between(String text, String start, String... ends) {
        int from = text.indexOf(start);
        if (from < 0) {
            throw new IllegalStateException("'" + start + "' not found in: " + text);
        }
        from += start.length();
        int to = text.length();
        for (String end : ends) {
            int at = text.indexOf(end, from);
            if (at >= 0 && at < to) {
                to = at;
            }
        }
        return text.substring(from, to);
    }

    private static String boldCallName(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("**`") || !trimmed.endsWith("`**")) {
            return null;
        }
        return trimmed.substring(3, trimmed.length() - 3);
    }

    // ======================================================================
    // Section 1 — the types
    // ======================================================================

    /** The six types, in the contract's order. */
    public static List<String> types() {
        String sentence = between(flat(), "has a **type**, one of", "and optionally");
        List<String> types = new ArrayList<>();
        Matcher m = BACKTICKED.matcher(sentence);
        while (m.find()) {
            types.add(m.group(1));
        }
        requireFound(types.size(), "types in section 1");
        return types;
    }

    // ======================================================================
    // Section 4.3 — the not-found refusal
    // ======================================================================

    /** The fixed message of {@code NOT_FOUND}: the fenced block of section 4.3. */
    public static String notFoundMessage() {
        String section = between(text(), "### 4.3", "### 4.4");
        String fence = "```";
        int open = section.indexOf(fence);
        int close = open < 0 ? -1 : section.indexOf(fence, open + fence.length());
        if (close < 0) {
            throw new IllegalStateException("section 4.3 of the contract carries no fenced "
                + "message");
        }
        return section.substring(open + fence.length(), close).strip();
    }

    // ======================================================================
    // Section 4.4 — the refusals
    // ======================================================================

    /** Reason to the cells of its row: the pattern cell and the remedy cell. */
    public static Map<String, String[]> refusalRows() {
        Map<String, String[]> rows = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : text().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| Reason |")) {
                inTable = true;
                continue;
            }
            if (inTable && !trimmed.startsWith("|")) {
                break;
            }
            if (!inTable || trimmed.startsWith("|---")) {
                continue;
            }
            String[] cells = trimmed.split("\\|");
            rows.put(cells[1].replace("`", "").trim(),
                new String[] {cells[2].trim(), cells[3].trim()});
        }
        requireFound(rows.size(), "rows of the table in section 4.4");
        return rows;
    }

    /** The reasons of the table in section 4.4. */
    public static List<String> surfaceReasons() {
        return List.copyOf(refusalRows().keySet());
    }

    /** The reasons section 4.4 lists as raised on the generic surface only. */
    public static List<String> genericOnlyReasons() {
        String sentence = between(flat(), "declared in the catalogue all the same:",
            "A reason that is in neither list");
        List<String> reasons = new ArrayList<>();
        Matcher m = BACKTICKED.matcher(sentence);
        while (m.find()) {
            reasons.add(m.group(1));
        }
        requireFound(reasons.size(), "generic-only reasons in section 4.4");
        return reasons;
    }

    /** The message pattern of a reason: the first backticked text of its pattern cell. */
    public static String patternOf(String reason) {
        String cell = refusalRows().get(reason)[0];
        Matcher m = BACKTICKED.matcher(cell);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The form of {@code UNEXPECTED_FAILURE} for a call without an address:
     * the pattern with its opening replaced as the cell's note says.
     */
    public static String unexpectedFailureWithoutAddress() {
        String cell = refusalRows().get("UNEXPECTED_FAILURE")[0];
        Matcher m = BACKTICKED.matcher(cell);
        m.find();
        String pattern = m.group(1);
        m.find();
        String opening = m.group(1);
        return pattern.replace("<call> on <address>", opening);
    }

    /** The placeholders a pattern names, in order. */
    public static List<String> placeholdersIn(String pattern) {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(pattern);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static final Set<String> STEPS = Set.of("create", "read", "update", "query",
        "digest");

    /**
     * The message a refusal must carry, as a regular expression.
     *
     * <p>Every placeholder of the pattern must be accounted for: given a value,
     * or named as one the contract leaves open (the probe then accepts any
     * text there). A step placeholder is filled with the contract's own tool
     * name. A placeholder the probe forgot is an error, not a wildcard —
     * otherwise a probe would quietly accept anything in the place it did not
     * think about.
     */
    public static Pattern message(String pattern, Map<String, String> values,
                                  String... open) {
        Set<String> openNames = Set.of(open);
        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(pattern);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(pattern.substring(last, m.start())));
            String name = m.group(1);
            if (values.containsKey(name)) {
                regex.append(Pattern.quote(values.get(name)));
            } else if (STEPS.contains(name)) {
                regex.append(Pattern.quote(toolFor(name)));
            } else if (openNames.contains(name)) {
                regex.append("(.+)");
            } else {
                throw new IllegalArgumentException("the probe gives no value for <" + name
                    + "> in: " + pattern);
            }
            last = m.end();
        }
        regex.append(Pattern.quote(pattern.substring(last)));
        return Pattern.compile(regex.toString());
    }

    // ======================================================================
    // Sections 3 and 6 — the next steps
    // ======================================================================

    /** What section 3 says each call on an entry in force does. */
    public static Map<String, String> doesOnAnEntry() {
        String sectionThree = between(text(), "## 3. The answer", "## 4. The refusal");
        Map<String, String> does = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\{ \"call\": \"([a-z_]+)\",\\s*\"does\": \"([^\"]+)\" }")
            .matcher(sectionThree);
        while (m.find()) {
            does.put(m.group(1), m.group(2));
        }
        requireFound(does.size(), "next entries in the example of section 3");
        return does;
    }

    /** What section 6 says the one call after a withdrawal does. */
    public static String doesAfterWithdrawal(boolean released) {
        String sentence = between(flat(), released
            ? "After a withdrawal that released the address:"
            : "After one that kept it:", "##", "The list is computed");
        Matcher m = BACKTICKED.matcher(sentence);
        if (!m.find()) {
            throw new IllegalStateException("no text after a withdrawal in section 6");
        }
        return m.group(1);
    }

    /** One row of the table in section 6. */
    public record NextRow(String about, String condition, List<String> calls) {
    }

    public static List<NextRow> nextTable() {
        List<NextRow> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : text().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| The answer is about |")) {
                inTable = true;
                continue;
            }
            if (inTable && !trimmed.startsWith("|")) {
                break;
            }
            if (!inTable || trimmed.startsWith("|---")) {
                continue;
            }
            String[] cells = trimmed.split("\\|");
            List<String> calls = new ArrayList<>();
            Matcher m = BACKTICKED.matcher(cells[3]);
            while (m.find()) {
                calls.add(m.group(1));
            }
            rows.add(new NextRow(cells[1].trim(), cells[2].trim(), calls));
        }
        requireFound(rows.size(), "rows of the table in section 6");
        return rows;
    }

    /** The calls the table lists for an entry in force, for a reader or a writer. */
    public static List<String> nextOnAnEntry(boolean mayWrite) {
        List<String> calls = new ArrayList<>();
        for (NextRow row : nextTable()) {
            if (!row.about().equals("an entry in force")) {
                continue;
            }
            if (row.condition().equals("always") || mayWrite) {
                calls.addAll(row.calls());
            }
        }
        return calls;
    }

    /** The one call the table lists after a withdrawal. */
    public static List<String> nextAfterWithdrawal(boolean released) {
        return nextTable().stream()
            .filter(r -> r.about().contains(released ? "released" : "kept"))
            .findFirst().orElseThrow().calls();
    }

    /** The one fixed sentence section 4.3 refers to, which lives in the generic surface. */
    public static boolean notFoundIsTheGenericSurfacesMessage() {
        return flat().contains("the one fixed message the service already answers on its "
            + "generic surface");
    }

    private static void requireFound(int count, String what) {
        if (count == 0) {
            throw new IllegalStateException("no " + what + " were found in the contract copy");
        }
    }
}
