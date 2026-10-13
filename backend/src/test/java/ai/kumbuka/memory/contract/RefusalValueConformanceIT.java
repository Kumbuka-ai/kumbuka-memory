/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

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
 * <p>The red run, observed on 2026-10-13 against the catalogue before this
 * probe: 15 of the 22 codes raised here repeated a marked value in the
 * message — the address, the scope, the key, the selector, the type, the
 * cursor, the page size or the argument's value. ({@code TYPE_UNKNOWN}'s
 * {@code offenders} carried the type sent as well, on the generic surface;
 * {@code ArgumentOffendersIT} holds that.)
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
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
                "colour", MARK)), MARK),
            new Case("ARGUMENT_MISSING", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "fields", Map.of("content", MARK))), MARK, ADDRESS),
            new Case("ARGUMENT_INVALID", () -> Mcp.call(QUERY, args("scope", SCOPE,
                "direction", MARK)), MARK),
            new Case("ARGUMENT_INVALID", () -> Mcp.call(UPDATE, args("address", ADDRESS,
                "conflict_token", token(ADDRESS), "fields", MARK)), MARK));

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
