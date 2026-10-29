/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import ai.kumbuka.memory.adapter.payload.RefusalDataPayload;
import ai.kumbuka.memory.adapter.rest.RefusalMapper;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The codes and statuses of the contract's table are the same in every
 * service, a service adds a code only by declaring it, and a refusal carries
 * no member of {@code data} outside the contract's list (C8.7).
 *
 * <p>Both tables and the rule on {@code attempted} are read from a verbatim
 * copy in the test tree, {@code contract/shared-code-table.md}, so the
 * expectation does not come from the catalogue it checks. The column {@code
 * data} of a row names the members beyond {@code attempted}; the rule above
 * the table says which refusals carry no {@code attempted}, because they fall
 * before the route stands. Five statements:
 *
 * <ol>
 *   <li>every code of the table this service carries has the table's
 *       status;</li>
 *   <li>a code of the table this service does not carry is listed in
 *       {@link #NOT_CARRIED}, with the reason it has no occasion for it;</li>
 *   <li>every code this service carries is in the table, or is declared in
 *       {@link #SERVICE_CODES} with its status, or is named in
 *       {@link #RENAMED_IN_RUN_E} with its status; every code declared or
 *       named there is carried;</li>
 *   <li>every member a refusal's {@code data} can carry on the wire is in the
 *       list of members, and so is every member a row of the code table
 *       names;</li>
 *   <li>the codes this service decides before the route stands, {@link
 *       #BEFORE_THE_ROUTE}, are the ones the rule names as carrying no {@code
 *       attempted}.</li>
 * </ol>
 *
 * <h2>The codes whose renaming would break a caller</h2>
 *
 * Some codes this service answers name a condition a row of the table names
 * under another code, or one whose target row is not decided yet. Renaming
 * them changes what a caller reads, so it is memory's breaking step, run E,
 * and not this run. Until then they stand in {@link #RENAMED_IN_RUN_E}, with
 * the status they answer today and the row they are to become: a named,
 * temporary list, not a verdict. A code in it is no offence; a code that
 * leaves the service, or changes its status, without leaving the list is.
 * The list empties in run E, and this class says so the day it is empty.
 *
 * <p>The constants, {@link #carried()} and {@link #members()} are the service
 * binding; the rule reads none of the service's words. {@link #carried()}
 * reads the three places a status comes from today (the domain's table in
 * {@code RefusalMapper}, the transport's reasons, the argument check) until
 * one catalogue holds the statuses.
 */
class SharedCodeTableTest {

    /** The codes of the table this service has no occasion for, and why. */
    static final Map<String, String> NOT_CARRIED = Map.ofEntries(
        Map.entry("VALUE_UNDECLARED", "no argument takes a value from a scope's vocabulary; "
            + "whether the entry types become this row is open (TYPE_UNKNOWN, run E)"),
        Map.entry("PREDICATE_UNKNOWN", "the filters are declared arguments, so an undeclared "
            + "one is ARGUMENT_UNKNOWN"),
        Map.entry("SELECTOR_UNDECLARED", "a selector is the prefix of a key and no scope "
            + "declares one"),
        Map.entry("SELECTOR_WITHDRAWN", "a selector is the prefix of a key and no scope "
            + "declares one, so none is withdrawn"),
        Map.entry("ADDRESS_OCCUPIED", "an occupied address is answered as ALREADY_EXISTS "
            + "until run E renames it"),
        Map.entry("IDEMPOTENCY_KEY_REUSED", "no call takes an idempotency key yet"),
        Map.entry("CLAIM_HELD", "no call claims an entry"),
        Map.entry("CLAIM_ABSENT", "no call needs a hold"),
        Map.entry("RECEIPT_WRONG", "no call takes a receipt"),
        Map.entry("DRAW_EMPTY", "no call draws"),
        Map.entry("TRANSITION_REFUSED", "an entry has no lifecycle a state refuses a call by"),
        Map.entry("ROLE_INSUFFICIENT", "no role is read; whether a token without a subject "
            + "becomes this row is open (ACTOR_UNKNOWN, run E)"));

    /** The codes this service declares beyond the table, with their statuses. */
    static final Map<String, Integer> SERVICE_CODES = Map.of(
        "SELECTOR_RESERVED", 422,
        "SELECTOR_MISMATCHED", 400,
        "UPDATE_EMPTY", 400,
        "REFERENCE_CREDENTIAL_BEARING", 422);

    /**
     * The codes whose renaming is memory's breaking step, run E: each with
     * the status it answers today and the row it is to become, or the open
     * question that decides the row. Temporary: it empties in run E.
     */
    static final Map<String, RunE> RENAMED_IN_RUN_E = Map.of(
        "ALREADY_EXISTS", new RunE(409, "ADDRESS_OCCUPIED"),
        "FIELD_IMMUTABLE", new RunE(422, "ARGUMENT_UNKNOWN, the row of an unknown argument "
            + "inside fields"),
        "SESSION_NOT_BOUND", new RunE(500, "UNEXPECTED_FAILURE, the row of an unanticipated "
            + "failure"),
        "SELECTOR_ABSENT", new RunE(400, "open: no row names an address without selector"),
        "TYPE_UNKNOWN", new RunE(422, "open: VALUE_UNDECLARED or ARGUMENT_INVALID"),
        "CONTENT_ABSENT", new RunE(400, "open: ARGUMENT_MISSING is the candidate"),
        "CONTENT_OVERSIZE", new RunE(400, "open: ARGUMENT_INVALID is the candidate"),
        "KEY_MALFORMED", new RunE(400, "open: ARGUMENT_INVALID or ADDRESS_MALFORMED"),
        "ACTOR_UNKNOWN", new RunE(403, "open: a code of the service, or the capacity form "
            + "of ROLE_INSUFFICIENT"));

    /** A code named for run E: the status it answers today, and what it is to become. */
    record RunE(int status, String becomes) {
    }

    /**
     * The codes this service answers, in some or all of their cases, before
     * the route stands, and so without {@code attempted}: the surface header
     * and {@code NOT_FOUND}, which carries nothing; and {@code
     * ADDRESS_MALFORMED} below a reserved name, which the rule names and this
     * service does not refuse yet (the reserved names come with run B2). Every
     * {@code ADDRESS_MALFORMED} this service answers today carries {@code
     * attempted}.
     */
    static final Set<String> BEFORE_THE_ROUTE =
        Set.of("HEADER_INVALID", "ADDRESS_MALFORMED", "NOT_FOUND");

    /** The rule on {@code attempted}: the paragraph of the copy that opens with it. */
    private static final Pattern ATTEMPTED_RULE = Pattern.compile(
        "\\*\\*`attempted` is not listed per row\\.\\*\\*(.*?)\\n\\n", Pattern.DOTALL);

    /** A sentence of the rule that says a refusal carries none, or nothing. */
    private static final Pattern WITHOUT = Pattern.compile(
        "`([A-Z_]+)`[^.]*\\b(?:none|nothing)\\b[^.]*\\.");

    private static final Pattern ROW = Pattern.compile(
        "^\\|[^|]*\\|\\s*`([A-Z_]+)`\\s*\\|\\s*(\\d{3})\\s*\\|([^|\\n]*)\\|",
        Pattern.MULTILINE);

    /** A row of the list of members: its first cell, which names one member or several. */
    private static final Pattern MEMBER = Pattern.compile(
        "^\\|\\s*(`[a-z_]+`(?:\\s*,\\s*`[a-z_]+`)*)\\s*\\|", Pattern.MULTILINE);

    private static final Pattern NAMED = Pattern.compile("`([a-z_]+)`");

    private static final String MEMBERS_HEADING = "## Members of `data`";

    @Test
    void the_service_speaks_the_shared_table() throws IOException {
        assertThat(offences(table(), carried(), RENAMED_IN_RUN_E))
            .as("one condition answers with one code and one status in every service, so a "
                + "caller that learned one service can read the next (C8.7). Align the code "
                + "and its status with the table, declare a code the table does not have, or "
                + "name a code whose renaming breaks a caller for run E")
            .isEmpty();
    }

    @Test
    void the_codes_named_for_run_e_are_no_row_of_the_table() throws IOException {
        Map<String, Integer> table = table();
        assertThat(RENAMED_IN_RUN_E.keySet().stream().filter(table::containsKey).toList())
            .as("a code of the table is spoken already and has nothing to wait for")
            .isEmpty();
        assertThat(RENAMED_IN_RUN_E.keySet().stream()
                .filter(SERVICE_CODES::containsKey).toList())
            .as("a code is either the service's own or named for run E, not both")
            .isEmpty();
    }

    @Test
    void a_refusal_carries_no_member_outside_the_list() throws IOException {
        assertThat(memberOffences(memberList(), members(), rowMembers()))
            .as("each member of data means one thing on every service, and a refusal carries "
                + "no other (C8.7). Name what the caller acts on with a member of the list")
            .isEmpty();
    }

    @Test
    void the_refusals_before_the_route_are_those_the_rule_names() throws IOException {
        assertThat(withoutAttempted(copy()))
            .as("a refusal that falls once the route stands carries attempted, and the rule "
                + "names the ones that fall before it (C8.7). Align BEFORE_THE_ROUTE with the "
                + "copy, or the service's check order with the rule")
            .containsExactlyInAnyOrderElementsOf(BEFORE_THE_ROUTE);
    }

    /** The red state of the rule, observed on every build. */
    @Test
    void the_check_reads_the_codes_the_rule_says_carry_no_attempted() {
        String planted = """
            **`attempted` is not listed per row.** Every refusal that falls once the route \
            stands carries `attempted`. `HEADER_INVALID` carries none. `ROLE_INSUFFICIENT` \
            carries `next`. `NOT_FOUND` carries nothing (C8.4).

            | Condition | Code |
            """;
        assertThat(withoutAttempted(planted))
            .as("RED STATE, observed: a rule that names HEADER_INVALID and NOT_FOUND without "
                + "attempted reads as those two, and not as the code that carries next, so a "
                + "service that decides a third code before the route is reported")
            .containsExactly("HEADER_INVALID", "NOT_FOUND");
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_wrong_status_an_undeclared_code_and_a_missing_one()
            throws IOException {
        Map<String, Integer> planted = new LinkedHashMap<>(carried());
        planted.put("CONFLICT_TOKEN_STALE", 409);
        planted.put("CONFLICT_TOKEN_MISSING", 409);
        planted.remove("HEADER_INVALID");
        planted.put("REPORT_PLANTED", 500);
        planted.remove("UPDATE_EMPTY");
        planted.put("TYPE_UNKNOWN", 400);

        assertThat(offences(table(), planted, RENAMED_IN_RUN_E))
            .as("RED STATE, observed: the token statuses this service answered before this "
                + "run, a code of the table nobody carries, a code nobody declared, a declared "
                + "code nobody carries, and a code named for run E whose status moved, must "
                + "each be reported")
            .containsExactlyInAnyOrder(
                "CONFLICT_TOKEN_MISSING answers 409; the table says 428",
                "CONFLICT_TOKEN_STALE answers 409; the table says 412",
                "HEADER_INVALID is in the table and not carried, and NOT_CARRIED does not say why",
                "REPORT_PLANTED is neither in the table nor declared as a code of the service, "
                    + "nor named for run E",
                "UPDATE_EMPTY is declared as a code of the service and not carried",
                "TYPE_UNKNOWN answers 400; named for run E as 422");
    }

    /** The red state of the list for run E, observed on every build. */
    @Test
    void the_check_reports_a_code_named_for_run_e_that_is_gone() throws IOException {
        Map<String, Integer> renamed = new LinkedHashMap<>(carried());
        renamed.remove("ALREADY_EXISTS");
        renamed.put("ADDRESS_OCCUPIED", 409);

        assertThat(offences(table(), renamed, RENAMED_IN_RUN_E))
            .as("RED STATE, observed: once run E renames a code, the list must say so, or it "
                + "outlives the codes it names")
            .containsExactly("ALREADY_EXISTS is named for run E and not carried");
    }

    /** The red state of the members, observed on every build. */
    @Test
    void the_check_reports_a_member_outside_the_list() throws IOException {
        List<String> planted = new ArrayList<>(members());
        planted.add("report");
        Map<String, List<String>> rows = new LinkedHashMap<>(rowMembers());
        rows.put("UPDATE_EMPTY", List.of("attempted", "patch"));

        assertThat(memberOffences(memberList(), planted, rows))
            .as("RED STATE, observed: the member this service carried before this run, and a "
                + "member a row names, neither in the list, must each be reported")
            .containsExactlyInAnyOrder(
                "data carries report, which is not in the list of members",
                "the row of UPDATE_EMPTY names patch, which is not in the list of members");
    }

    static List<String> offences(Map<String, Integer> table, Map<String, Integer> carried,
                                 Map<String, RunE> runE) {
        List<String> offences = new ArrayList<>();
        table.forEach((code, status) -> {
            Integer actual = carried.get(code);
            if (actual == null) {
                if (!NOT_CARRIED.containsKey(code)) {
                    offences.add(code + " is in the table and not carried, and NOT_CARRIED "
                        + "does not say why");
                }
            } else if (!actual.equals(status)) {
                offences.add(code + " answers " + actual + "; the table says " + status);
            }
        });
        carried.forEach((code, status) -> {
            if (table.containsKey(code)) {
                return;
            }
            if (SERVICE_CODES.containsKey(code)) {
                if (!SERVICE_CODES.get(code).equals(status)) {
                    offences.add(code + " answers " + status + "; declared as "
                        + SERVICE_CODES.get(code));
                }
            } else if (runE.containsKey(code)) {
                if (runE.get(code).status() != status) {
                    offences.add(code + " answers " + status + "; named for run E as "
                        + runE.get(code).status());
                }
            } else {
                offences.add(code + " is neither in the table nor declared as a code of the "
                    + "service, nor named for run E");
            }
        });
        SERVICE_CODES.keySet().stream().filter(code -> !carried.containsKey(code)).sorted()
            .forEach(code -> offences.add(code + " is declared as a code of the service and "
                + "not carried"));
        runE.keySet().stream().filter(code -> !carried.containsKey(code)).sorted()
            .forEach(code -> offences.add(code + " is named for run E and not carried"));
        return offences;
    }

    static List<String> memberOffences(List<String> list, List<String> onTheWire,
                                       Map<String, List<String>> rows) {
        List<String> offences = new ArrayList<>();
        onTheWire.stream().filter(member -> !list.contains(member)).forEach(member ->
            offences.add("data carries " + member + ", which is not in the list of members"));
        rows.forEach((code, named) -> named.stream().filter(member -> !list.contains(member))
            .forEach(member -> offences.add("the row of " + code + " names " + member
                + ", which is not in the list of members")));
        return offences;
    }

    /** The copy of the code table: code to status, in its order. */
    static Map<String, Integer> table() throws IOException {
        Map<String, Integer> rows = new LinkedHashMap<>();
        Matcher m = ROW.matcher(codeTable());
        while (m.find()) {
            rows.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        assertThat(rows).as("the table's rows must have been read").hasSizeGreaterThan(20)
            .containsKeys("IDEMPOTENCY_KEY_REUSED", "SCOPE_LOCKED");
        return rows;
    }

    /** The codes the rule on {@code attempted} names as carrying none of it, in its order. */
    static List<String> withoutAttempted(String copy) {
        Matcher rule = ATTEMPTED_RULE.matcher(copy);
        assertThat(rule.find()).as("the copy must hold the rule on attempted").isTrue();
        List<String> codes = new ArrayList<>();
        Matcher without = WITHOUT.matcher(rule.group(1).replaceAll("\\s+", " "));
        while (without.find()) {
            codes.add(without.group(1));
        }
        return codes;
    }

    /**
     * The copy of the code table: code to the members of data its row names
     * beyond {@code attempted}. A row's cell may explain a member after a
     * colon or in parentheses, naming values of it there; those are no
     * members and are not read.
     */
    static Map<String, List<String>> rowMembers() throws IOException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher m = ROW.matcher(codeTable());
        while (m.find()) {
            List<String> named = new ArrayList<>();
            String cell = m.group(3).replaceAll("\\([^)]*\\)", " ").replaceAll(":.*$", " ");
            Matcher member = NAMED.matcher(cell);
            while (member.find()) {
                named.add(member.group(1));
            }
            rows.put(m.group(1), named);
        }
        return rows;
    }

    /** The copy of the list of members, in its order. */
    static List<String> memberList() throws IOException {
        String text = copy();
        int at = text.indexOf(MEMBERS_HEADING);
        assertThat(at).as("the copy must hold the list of members").isNotNegative();
        List<String> members = new ArrayList<>();
        Matcher m = MEMBER.matcher(text.substring(at));
        while (m.find()) {
            Matcher named = NAMED.matcher(m.group(1));
            while (named.find()) {
                members.add(named.group(1));
            }
        }
        assertThat(members).as("the list's rows must have been read").hasSizeGreaterThan(10);
        return members;
    }

    private static String codeTable() throws IOException {
        String text = copy();
        int at = text.indexOf(MEMBERS_HEADING);
        return at < 0 ? text : text.substring(0, at);
    }

    private static String copy() throws IOException {
        try (InputStream in = SharedCodeTableTest.class.getClassLoader()
                .getResourceAsStream("contract/shared-code-table.md")) {
            assertThat(in).as("the copy of the table must be on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The service binding: every code the service carries, with its status.
     * A code is carried where the catalogue declares it; its status comes
     * from where it is raised: the domain's table, the transport's reason,
     * or the argument check, whose refusals answer 400 and whose failure
     * nobody foresaw answers 500.
     */
    static Map<String, Integer> carried() {
        Map<String, Integer> carried = new LinkedHashMap<>();
        Arrays.stream(MemoryException.Reason.values()).forEach(reason -> carried.put(
            ReasonCatalogue.wireCode(reason.name()), RefusalMapper.Domain.statusOf(reason)));
        Arrays.stream(SurfaceException.Reason.values())
            .forEach(reason -> carried.put(reason.name(), reason.status()));
        Arrays.stream(CallException.Reason.values()).forEach(reason -> carried.put(
            reason.name(), reason == CallException.Reason.UNEXPECTED_FAILURE ? 500 : 400));
        assertThat(carried.keySet()).as("every code raised is declared in the catalogue")
            .containsExactlyInAnyOrderElementsOf(ReasonCatalogue.byCode().keySet());
        return carried;
    }

    /**
     * The service binding: every member a refusal's data has on the wire. A
     * plain mapper writes the members with no value too, so an empty payload
     * names them all, under the names the wire carries.
     */
    private static List<String> members() throws IOException {
        int components = RefusalDataPayload.class.getRecordComponents().length;
        Object[] none = new Object[components];
        Class<?>[] types = Arrays.stream(RefusalDataPayload.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            RefusalDataPayload empty = RefusalDataPayload.class.getDeclaredConstructor(types)
                .newInstance(none);
            List<String> members = new ArrayList<>();
            new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(empty))
                .fieldNames().forEachRemaining(members::add);
            assertThat(members).as("every component is a member").hasSize(components);
            return members;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the payload of data cannot be built empty", e);
        }
    }
}
