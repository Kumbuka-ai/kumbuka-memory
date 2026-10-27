/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * No refusal of the assistant surface repeats a value the caller sent, in
 * its message or in any member of {@code data} (DEC-0042).
 *
 * <h2>How</h2>
 *
 * Every code the catalogue declares for this surface is raised against the
 * running service with values that carry a mark no pattern could produce,
 * and the refusal is searched for the mark, ignoring case. The scope the probe
 * calls in is searched for in every message as well. The set of codes raised
 * is held against the table of section 4.4 of the contract, so a code added
 * later without a case here turns this probe red rather than going unchecked.
 *
 * <h2>Two codes raised elsewhere</h2>
 *
 * {@code ACTOR_UNKNOWN} is raised by a token without a subject; the caller
 * sends no value it could repeat. {@code UNEXPECTED_FAILURE} needs a failure
 * injected into the service, and {@code UnexpectedFailureIT} raises it and
 * holds its message free of the address and the scope the call sent.
 *
 * <h2>One member of data that carries an address</h2>
 *
 * A stale conflict token is answered with the entry as this caller's own
 * read would answer it (DEC-0041), and that answer carries the entry's
 * address. Its {@code data} is therefore searched for the stale token, which
 * it must not repeat, and not for the address. The contract records the
 * departure in section 8.
 *
 * <p>Extended on 2026-10-15 with an undeclared argument whose NAME carries the
 * mark, at the top and under {@code fields} (DEC-0043 R19): its red run
 * against the surface before that change found both names repeated, in the
 * message and under {@code data.offenders}.
 *
 * <p>The red run, observed on 2026-10-13 against the catalogue before this
 * probe: 15 of the 22 codes raised here repeated a marked value in the
 * message — the address, the scope, the key, the selector, the type, the
 * cursor, the page size or the argument's value. ({@code TYPE_UNKNOWN}'s
 * {@code offenders} carried the type sent as well, on the generic surface;
 * {@code ArgumentOffendersIT} holds that.)
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@CounterProbe
class RefusalValueConformanceIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String KEY = "convention.branch-names";
    private static final String ADDRESS = "memory://" + SCOPE + "/convention/branch-names";

    /** A mark no pattern of the catalogue could produce. */
    private static final String MARK = "zqmark";

    private static final String CREATE = Contract.toolFor("create");
    private static final String READ = Contract.toolFor("read");
    private static final String UPDATE = Contract.toolFor("update");
    private static final String WITHDRAW = Contract.toolFor("withdraw");
    private static final String QUERY = Contract.toolFor("query");
    private static final String DIGEST = Contract.toolFor("digest");

    /** Raised elsewhere, for the reasons the class comment gives. */
    private static final Set<String> RAISED_ELSEWHERE =
        Set.of("ACTOR_UNKNOWN", "UNEXPECTED_FAILURE");

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntryInEachScope() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "convention", "planted"));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(
            SubstrateDatabaseResource.LOCKED_SCOPE_ID, KEY, "convention", "planted"));
    }

    /** One way to raise a code, and the values its refusal must not repeat. */
    private record Case(String code, Supplier<Mcp.Result> call, List<String> marks,
                        boolean dataCarriesTheEntry) {

        Case(String code, Supplier<Mcp.Result> call, String... marks) {
            this(code, call, List.of(marks), false);
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void no_refusal_repeats_a_value_the_caller_sent() {
        String locked = "memory://" + LOCKED + "/convention/branch-names";
        String cursor = foreignCursor();

        List<Case> cases = List.of(
            new Case("NOT_FOUND", () -> Mcp.call(READ,
                args("address", "memory://" + SCOPE + "/convention/" + MARK)), MARK),
            new Case("SCOPE_LOCKED", () -> Mcp.call(UPDATE, args("address", locked,
                "conflict_token", token(locked), "fields", Map.of("content", MARK))),
                MARK, locked, LOCKED),
            new Case("ADDRESS_MALFORMED", () -> Mcp.call(READ,
                args("address", MARK + "/convention/x")), MARK),
            new Case("KEY_MALFORMED", () -> create("convention", "convention.ZQMARK",
                "convention", "x"), MARK),
            new Case("SELECTOR_ABSENT", () -> create("decision", MARK, "decision", "x"), MARK),
            new Case("SELECTOR_RESERVED", () -> create("system", "system." + MARK,
                "convention", "x"), MARK, "system."),
            new Case("SELECTOR_MISMATCHED", () -> create("decision", MARK + ".tabs",
                "convention", "x"), MARK),
            new Case("ALREADY_EXISTS", () -> create("convention", KEY, "convention", MARK),
                MARK, ADDRESS, KEY),
            new Case("CONFLICT_TOKEN_STALE", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "conflict_token", MARK + "-token", "fields", Map.of("content", "x"))),
                List.of(MARK), true),
            new Case("UPDATE_EMPTY", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "conflict_token", token(ADDRESS), "fields", Map.of())), ADDRESS, KEY),
            new Case("TYPE_UNKNOWN", () -> create("decision", "decision.tabs", MARK, "x"),
                MARK),
            new Case("TYPE_UNKNOWN", () -> Mcp.call(QUERY, args("scope", SCOPE, "type", MARK)),
                MARK),
            new Case("TYPE_UNKNOWN", () -> Mcp.call(DIGEST, args("scope", SCOPE,
                "types", List.of("decision", MARK))), MARK),
            new Case("CONTENT_ABSENT", () -> create("decision", "decision." + MARK,
                "decision", "   "), MARK),
            new Case("CONTENT_OVERSIZE", () -> create("decision", "decision.tabs", "decision",
                (MARK + " ").repeat(400)), MARK),
            new Case("REFERENCE_CREDENTIAL_BEARING", () -> Mcp.call(CREATE, args("scope", SCOPE,
                "selector", "decision", "fields", Map.of("key", "decision.tabs",
                    "type", "decision", "content", "x",
                    "reference", "https://" + MARK + ":pw@" + MARK + ".example.org/?token=x"))),
                MARK),
            new Case("PAGE_SIZE_REJECTED", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "page_size", 9137)), "9137"),
            new Case("PAGE_SIZE_REJECTED", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "page_size", MARK)), MARK),
            new Case("CURSOR_MALFORMED", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "after", MARK + "!!")), MARK),
            new Case("CURSOR_FOREIGN", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "selector", "convention", "after", cursor)), cursor),
            new Case("SORT_UNDECLARED", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "sort", MARK)), MARK),
            new Case("ARGUMENT_UNKNOWN", () -> Mcp.call(READ, args("address", ADDRESS,
                MARK + "_colour", MARK)), MARK),
            new Case("ARGUMENT_UNKNOWN", () -> Mcp.call(CREATE, args("scope", SCOPE,
                "selector", "decision", "fields", Map.of("key", "decision.tabs",
                    "type", "decision", "content", "x", MARK + "_field", MARK))), MARK),
            new Case("ARGUMENT_MISSING", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "fields", Map.of("content", MARK))), MARK, ADDRESS),
            new Case("ARGUMENT_INVALID", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "direction", MARK)), MARK),
            new Case("ARGUMENT_INVALID", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "conflict_token", token(ADDRESS), "fields", MARK)), MARK),
            new Case("ARGUMENT_INVALID", () -> Mcp.call(DIGEST, args("scope", SCOPE,
                "types", List.of("decision", Map.of(MARK, MARK)))), MARK));

        Set<String> raised = new LinkedHashSet<>(RAISED_ELSEWHERE);
        raised.add("SCOPE_READ_ONLY");
        List<String> repeated = new ArrayList<>();
        for (Case c : cases) {
            Mcp.Result refusal = c.call().get();
            assertThat(refusal.reason()).as("%s: %s", c.code(), refusal).isEqualTo(c.code());
            repeated.addAll(repeatedIn(c, refusal));
            raised.add(c.code());
        }

        assertThat(repeated)
            .as("a refusal is logged, and a value can be content (DEC-0042)")
            .isEmpty();
        assertThat(raised)
            .as("every code section 4.4 of the contract declares for this surface is raised "
                + "here")
            .containsExactlyInAnyOrderElementsOf(Contract.surfaceReasons());
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void the_refusal_of_a_reader_repeats_neither_the_address_nor_the_scope() {
        Case c = new Case("SCOPE_READ_ONLY", () -> Mcp.call(WITHDRAW, args("address", ADDRESS,
            "conflict_token", token(ADDRESS))), ADDRESS, KEY);
        Mcp.Result refusal = c.call().get();

        assertThat(refusal.reason()).as("%s", refusal).isEqualTo(c.code());
        assertThat(repeatedIn(c, refusal)).isEmpty();
    }

    /**
     * Which of several values was wrong is named by its position as the
     * argument's path, and an undeclared name is answered with the declared
     * ones (DEC-0043 R19).
     *
     * <p>The red run, observed on 2026-10-15 against the surface before this
     * change: a digest with one unknown type of two answered
     * {@code offenders = ["types"]}.
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_wrong_value_is_named_by_its_position_and_an_unknown_name_by_the_declared_ones() {
        Mcp.Result unknownType = Mcp.call(DIGEST, args("scope", SCOPE,
            "types", List.of("decision", MARK)));
        assertThat(unknownType.strings("data.offenders")).containsExactly("types[1]");

        Mcp.Result notAText = Mcp.call(DIGEST, args("scope", SCOPE,
            "types", List.of("decision", "convention", 7)));
        assertThat(notAText.reason()).isEqualTo("ARGUMENT_INVALID");
        assertThat(notAText.strings("data.offenders")).containsExactly("types[2]");

        Mcp.Result unknownName = Mcp.call(READ, args("address", ADDRESS, MARK, 1));
        assertThat(unknownName.reason()).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(unknownName.strings("data.accepts"))
            .as("the arguments section 5 of the contract gives memory_read")
            .containsExactlyElementsOf(Contract.argumentsOf(READ).top().keySet());
        assertThat(unknownName.map("data")).doesNotContainKey("offenders");

        Mcp.Result unknownField = Mcp.call(UPDATE, args("address", ADDRESS,
            "conflict_token", token(ADDRESS), "fields", Map.of(MARK, "x")));
        assertThat(unknownField.strings("data.accepts"))
            .as("the names memory_update declares under fields")
            .containsExactlyElementsOf(Contract.argumentsOf(UPDATE).fields().keySet());
    }

    /**
     * The protocol answers a method or a tool it does not have with the ones
     * it has, and never with the name that arrived (DEC-0043 R19, R20).
     *
     * <p>The red run, observed on 2026-10-15 before this change: the unknown
     * method was answered opening with the name sent, {@code zqmark/list is
     * not a method of this server}.
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void no_error_of_the_protocol_repeats_a_name_the_caller_sent() {
        List<io.restassured.response.Response> answers = List.of(
            Mcp.rpc(MARK + "/list", Map.of()),
            Mcp.rpc("tools/call", Map.of("name", "memory_" + MARK, "arguments", Map.of())),
            Mcp.rpc("tools/call", Map.of("name", READ, "arguments", MARK)),
            Mcp.post(Map.of("jsonrpc", MARK, "id", 1, "method", "ping")));
        for (io.restassured.response.Response answer : answers) {
            assertThat(answer.jsonPath().getString("error.message"))
                .as("%s", answer.asString())
                .isNotBlank();
            assertThat(answer.asString().toLowerCase(Locale.ROOT))
                .as("the protocol's error carries what its specification prescribes and "
                    + "nothing of the caller's beyond it")
                .doesNotContain(MARK);
        }
        assertThat(answers.get(1).jsonPath().getString("error.message"))
            .as("the tools there are, by the contract's names")
            .contains(Contract.tools());
    }

    /**
     * The generic surface words its refusals as the assistant surface does,
     * from the declared patterns, and repeats nothing the caller sent either:
     * not a path segment, a body value, a query parameter's name or value, a
     * header's value, nor the text a JSON parser quotes from a broken body
     * (DEC-0043 R18).
     *
     * <p>The red run, observed on 2026-10-15 before this change: 10 of the 26
     * cases repeated what the caller sent -- the domain's own sentences named
     * the scope, the selector and the key of {@code ADDRESS_MALFORMED}, both
     * {@code KEY_MALFORMED}, {@code SELECTOR_ABSENT}, {@code
     * SELECTOR_MISMATCHED} and {@code SELECTOR_RESERVED}, {@code UPDATE_EMPTY}
     * named the address and with it the scope, both {@code ARGUMENT_UNKNOWN}
     * returned the parameter's name under {@code offenders}, and the 405
     * quoted the segment with its colon verb. A {@code null} key was answered
     * {@code KEY_MALFORMED} quoting {@code 'null'} rather than {@code
     * ARGUMENT_MISSING}.
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void no_refusal_of_the_generic_surface_repeats_a_value_the_caller_sent() {
        String item = SurfaceFixture.API + "/" + SCOPE + "/convention/branch-names";
        String decisions = SurfaceFixture.API + "/" + SCOPE + "/decision";
        String scope = SurfaceFixture.API + "/" + SCOPE;
        String etag = SurfaceFixture.call().get(item).header("ETag");

        List<RestCase> cases = List.of(
            new RestCase("NOT_FOUND", () -> SurfaceFixture.call()
                .get(SurfaceFixture.API + "/" + SCOPE + "/convention/" + MARK)),
            new RestCase("ADDRESS_MALFORMED", () -> SurfaceFixture.call()
                .get(SurfaceFixture.API + "/ZQMARK/convention/x")),
            new RestCase("KEY_MALFORMED", () -> SurfaceFixture.call()
                .get(SurfaceFixture.API + "/" + SCOPE + "/ZQMARK")),
            new RestCase("KEY_MALFORMED", () -> rest(decisions, "decision.ZQMARK", "decision")),
            new RestCase("SELECTOR_ABSENT", () -> rest(decisions, MARK, "decision")),
            new RestCase("SELECTOR_MISMATCHED", () -> rest(decisions, MARK + ".tabs",
                "decision")),
            new RestCase("SELECTOR_RESERVED", () -> rest(SurfaceFixture.API + "/" + SCOPE
                + "/system", "system." + MARK, "convention")),
            new RestCase("ALREADY_EXISTS", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", KEY, "type", "convention", "content", MARK))
                .post(SurfaceFixture.API + "/" + SCOPE + "/convention"), ADDRESS, KEY),
            new RestCase("TYPE_UNKNOWN", () -> rest(decisions, "decision.tabs", MARK)),
            new RestCase("CONTENT_OVERSIZE", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", "decision.tabs", "type", "decision",
                    "content", (MARK + " ").repeat(400)))
                .post(decisions)),
            new RestCase("REFERENCE_CREDENTIAL_BEARING", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", "decision.tabs", "type", "decision",
                    "content", "x", "reference",
                    "https://" + MARK + ":pw@" + MARK + ".example.org/?token=x"))
                .post(decisions)),
            new RestCase("FIELD_IMMUTABLE", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("key", "decision.tabs", "type", "decision",
                    "content", "x", "scope", MARK))
                .post(decisions)),
            new RestCase("ARGUMENT_MISSING", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("type", "decision", "content", MARK))
                .post(decisions)),
            new RestCase("UPDATE_EMPTY", () -> SurfaceFixture.call().header("If-Match", etag)
                .body("{\"fields\":{}}").patch(item), ADDRESS, KEY),
            new RestCase("CONFLICT_TOKEN_MISSING", () -> SurfaceFixture.call()
                .body(SurfaceFixture.fields("content", MARK)).patch(item), ADDRESS, KEY),
            new RestCase("TYPE_UNKNOWN", () -> SurfaceFixture.call()
                .get(scope + "?type=" + MARK)),
            new RestCase("SORT_UNDECLARED", () -> SurfaceFixture.call()
                .get(scope + "?sort=" + MARK)),
            new RestCase("ARGUMENT_INVALID", () -> SurfaceFixture.call()
                .get(scope + "?direction=" + MARK)),
            new RestCase("PAGE_SIZE_REJECTED", () -> SurfaceFixture.call()
                .get(scope + "?page_size=" + MARK)),
            new RestCase("CURSOR_MALFORMED", () -> SurfaceFixture.call()
                .get(scope + "?after=" + MARK)),
            new RestCase("ARGUMENT_UNKNOWN", () -> SurfaceFixture.call()
                .get(scope + "?" + MARK + "=" + MARK)),
            new RestCase("ARGUMENT_UNKNOWN", () -> SurfaceFixture.call()
                .get(item + "?" + MARK + "=" + MARK), ADDRESS, KEY),
            new RestCase("TYPE_UNKNOWN", () -> SurfaceFixture.call()
                .body("{\"types\":[\"decision\",\"" + MARK + "\"]}")
                .post(scope + ":digest")),
            new RestCase("ARGUMENT_INVALID", () -> SurfaceFixture.call()
                .header("Kumbuka-Surface", MARK).get(scope)),
            new RestCase("VERB_NOT_CARRIED", () -> SurfaceFixture.call()
                .post(item + ":" + MARK), ADDRESS, KEY),
            new RestCase("PAYLOAD_MALFORMED", () -> SurfaceFixture.call()
                .body("{\"" + MARK + "\": [}").post(decisions)));

        List<String> repeated = new ArrayList<>();
        List<String> otherCode = new ArrayList<>();
        for (RestCase c : cases) {
            io.restassured.response.Response answer = c.call().get();
            if (!c.code().equals(answer.jsonPath().getString("reason"))) {
                otherCode.add(c.code() + ": " + answer.asString());
            }
            String body = answer.asString().toLowerCase(Locale.ROOT);
            for (String mark : c.marks()) {
                if (body.contains(mark.toLowerCase(Locale.ROOT))) {
                    repeated.add(c.code() + " repeats '" + mark + "': " + answer.asString());
                }
            }
            if (answer.jsonPath().getString("message").contains(SCOPE)) {
                repeated.add(c.code() + " names the scope: " + answer.asString());
            }
        }
        assertThat(repeated)
            .as("a refusal is logged, and a value can be content (DEC-0042, DEC-0043 R18)")
            .isEmpty();
        assertThat(otherCode).as("each case raises the code it is written for").isEmpty();
    }

    /** One way to raise a code on the generic surface, and what it must not repeat. */
    private record RestCase(String code, Supplier<io.restassured.response.Response> call,
                            List<String> marks) {

        RestCase(String code, Supplier<io.restassured.response.Response> call,
                 String... more) {
            this(code, call, withMark(more));
        }

        private static List<String> withMark(String... more) {
            List<String> marks = new ArrayList<>(List.of(MARK));
            marks.addAll(List.of(more));
            return marks;
        }
    }

    private static io.restassured.response.Response rest(String collection, String key,
                                                         String type) {
        return SurfaceFixture.call()
            .body(SurfaceFixture.fields("key", key, "type", type, "content", "x"))
            .post(collection);
    }

    // ------------------------------------------------------------------

    private static List<String> repeatedIn(Case c, Mcp.Result refusal) {
        List<String> found = new ArrayList<>();
        String message = refusal.message().toLowerCase(Locale.ROOT);
        String data = c.dataCarriesTheEntry() ? "" : json(refusal.map("data"))
            .toLowerCase(Locale.ROOT);
        for (String mark : c.marks()) {
            String sought = mark.toLowerCase(Locale.ROOT);
            if (message.contains(sought)) {
                found.add(c.code() + " repeats '" + mark + "' in its message: "
                    + refusal.message());
            }
            if (data.contains(sought)) {
                found.add(c.code() + " repeats '" + mark + "' in data: " + data);
            }
        }
        if (c.dataCarriesTheEntry()
                && json(refusal.map("data")).toLowerCase(Locale.ROOT).contains(MARK)) {
            found.add(c.code() + " repeats the token it was sent in data");
        }
        if (message.contains(SCOPE)) {
            found.add(c.code() + " names the scope in its message: " + refusal.message());
        }
        return found;
    }

    private static String foreignCursor() {
        create("decision", "decision.one", "decision", "first");
        create("decision", "decision.two", "decision", "second");
        String cursor = Mcp.call(QUERY, args("scope", SCOPE, "selector", "decision",
            "page_size", 1)).string("next_cursor");
        assertThat(cursor).as("a first page of one out of two hands out a cursor").isNotNull();
        return cursor;
    }

    private static Mcp.Result create(String selector, String key, String type,
                                     String content) {
        return Mcp.call(CREATE, args("scope", SCOPE, "selector", selector,
            "fields", Map.of("key", key, "type", type, "content", content)));
    }

    private static String token(String address) {
        return Mcp.call(READ, args("address", address)).string("conflict_token");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
