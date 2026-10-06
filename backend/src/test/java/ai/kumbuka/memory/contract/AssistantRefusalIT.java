package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.kumbuka.memory.contract.Mcp.args;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal the assistant surface can return, raised against a running
 * service and compared with the contract.
 *
 * <p>The message is matched whole against the pattern of section 4.4, its
 * placeholders filled with the values this probe sent. A message that carried
 * one word more — a sentence from inside the service, the content of an
 * entry — does not match, which is how "built from the pattern alone" is
 * checked rather than trusted.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AssistantRefusalIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String PRIVATE = SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG;
    private static final String KEY = "convention.branch-names";
    private static final String ADDRESS = "memory://" + SCOPE + "/convention/branch-names";
    private static final String CONTENT = "feature/<slug>, never pushed to main";

    private static final String CREATE = Contract.toolFor("create");
    private static final String READ = Contract.toolFor("read");
    private static final String UPDATE = Contract.toolFor("update");
    private static final String WITHDRAW = Contract.toolFor("withdraw");
    private static final String QUERY = Contract.toolFor("query");
    private static final String DIGEST = Contract.toolFor("digest");

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntryInEachScope() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "convention", CONTENT));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(
            SubstrateDatabaseResource.LOCKED_SCOPE_ID, KEY, "convention", CONTENT));
    }

    // ======================================================================
    // 4.3 — the one deliberately indistinguishable refusal
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_absent_entry_and_an_unresolvable_scope_are_one_refusal_and_the_generic_one() {
        Mcp.Result absent = Mcp.call(READ,
            args("address", "memory://" + SCOPE + "/convention/nothing-here"));
        Mcp.Result unresolved = Mcp.call(READ,
            args("address", "memory://no-such-scope/convention/branch-names"));

        for (Mcp.Result refusal : List.of(absent, unresolved)) {
            assertNotFound(refusal, READ);
        }
        assertThat(unresolved.structured())
            .as("reason, message and data are identical whichever the cause")
            .isEqualTo(absent.structured());
        assertThat(unresolved.json().prettify()
                .replaceAll("\"id\": \\d+", ""))
            .as("and so is the way the refusal is transported")
            .isEqualTo(absent.json().prettify().replaceAll("\"id\": \\d+", ""));

        assertThat(absent.message())
            .as("the fixed message of section 4.3")
            .isEqualTo(Contract.notFoundMessage());
        assertThat(given().get("/api/" + SCOPE + "/convention/nothing-here")
                .jsonPath().getString("message"))
            .as("which is the one the service answers on its generic surface")
            .isEqualTo(Contract.notFoundMessage());
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.SECOND_SUBJECT)
    void another_author_s_private_entry_is_the_same_refusal_as_nothing_at_all() {
        SurfaceFixture.plant(new SurfaceFixture.Planted(SubstrateDatabaseResource.TENANT_ID,
            SubstrateDatabaseResource.PRIVATE_SCOPE_ID, true,
            SubstrateDatabaseResource.PROBE_SUBJECT, "decision", "decision.my-own-note",
            "not yours", Instant.now()));

        Mcp.Result hers = Mcp.call(READ,
            args("address", "memory://" + PRIVATE + "/decision/my-own-note"));
        Mcp.Result nothing = Mcp.call(READ,
            args("address", "memory://" + PRIVATE + "/decision/nothing-at-all"));

        assertNotFound(hers, READ);
        assertThat(hers.structured()).isEqualTo(nothing.structured());
        assertThat(hers.text()).doesNotContain("not yours");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_write_to_an_absent_entry_is_the_same_refusal_naming_the_write() {
        assertNotFound(Mcp.call(UPDATE, args("address",
            "memory://" + SCOPE + "/convention/nothing-here", "conflict_token", "t",
            "fields", Map.of("content", "x"))), UPDATE);
        assertNotFound(Mcp.call(WITHDRAW, args("address",
            "memory://" + SCOPE + "/convention/nothing-here", "conflict_token", "t")), WITHDRAW);
    }

    // ======================================================================
    // The scope
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_write_in_a_locked_scope_is_scope_locked_and_offers_the_reads() {
        String locked = "memory://" + LOCKED + "/convention/branch-names";
        Mcp.Result refusal = Mcp.call(UPDATE, args("address", locked,
            "conflict_token", token(locked), "fields", Map.of("content", "x")));

        assertRefusal(refusal, "SCOPE_LOCKED", UPDATE,
            Map.of("call", UPDATE, "scope", LOCKED));
        assertConcernsAVisibleEntry(refusal, List.of(READ, QUERY, DIGEST));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void a_write_without_the_write_right_is_scope_read_only_and_offers_the_reads() {
        Mcp.Result refusal = Mcp.call(WITHDRAW, args("address", ADDRESS,
            "conflict_token", token(ADDRESS)));

        assertRefusal(refusal, "SCOPE_READ_ONLY", WITHDRAW,
            Map.of("call", WITHDRAW, "scope", SCOPE));
        assertConcernsAVisibleEntry(refusal, List.of(READ, QUERY, DIGEST));
    }

    // ======================================================================
    // The address and the key
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_address_of_another_form_is_address_malformed_and_named_as_written() {
        for (String written : List.of(SCOPE + "/convention/branch-names",
                "memory://" + SCOPE + "/convention",
                "memory://Probe_Scope/convention/branch-names")) {
            assertRefusal(Mcp.call(READ, args("address", written)), "ADDRESS_MALFORMED", READ,
                literal(Map.of("value", written)));
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_key_of_another_form_is_key_malformed_and_named() {
        assertRefusal(Mcp.call(READ, args("address", "memory://" + SCOPE + "/Convention/x")),
            "KEY_MALFORMED", READ, literal(Map.of("key", "Convention.x")));
        assertRefusal(create("convention", "convention.Branch_Names", "convention", "x"),
            "KEY_MALFORMED", CREATE, literal(Map.of("key", "convention.Branch_Names")));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void the_reserved_selector_is_selector_reserved() {
        assertRefusal(create("system", "system.boot", "convention", "x"),
            "SELECTOR_RESERVED", CREATE, Map.of("selector", "system"));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_key_under_another_selector_is_selector_mismatched_and_names_both() {
        assertRefusal(create("decision", "convention.tabs", "convention", "x"),
            "SELECTOR_MISMATCHED", CREATE, Map.of("key", "convention.tabs",
                "key selector", "convention", "call", CREATE, "selector", "decision"));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_key_without_a_selector_is_selector_absent_and_names_the_key() {
        assertRefusal(create("decision", "storage", "decision", "x"),
            "SELECTOR_ABSENT", CREATE, literal(Map.of("call", CREATE, "key", "storage")));
    }

    // ======================================================================
    // Writing
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_create_onto_a_standing_entry_is_already_exists_and_offers_read_then_update() {
        Mcp.Result refusal = create("convention", KEY, "convention", "a second one");
        assertRefusal(refusal, "ALREADY_EXISTS", CREATE,
            Map.of("call", CREATE, "address", ADDRESS));
        assertConcernsAVisibleEntry(refusal, List.of(READ, UPDATE));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_stale_token_is_refused_with_the_entry_as_the_caller_s_own_read_answers_it() {
        Mcp.Result refusal = Mcp.call(UPDATE, args("address", ADDRESS,
            "conflict_token", "2000-01-01T00:00:00Z", "fields", Map.of("content", "x")));

        assertRefusal(refusal, "CONFLICT_TOKEN_STALE", UPDATE,
            Map.of("call", UPDATE, "address", ADDRESS));
        assertConcernsAVisibleEntry(refusal, List.of(READ));

        Mcp.Result read = Mcp.call(READ, args("address", ADDRESS));
        assertThat(refusal.map("data.fields"))
            .as("DEC-0041: the entry as this caller's own read would answer it")
            .isEqualTo(read.map("fields"));
        assertThat(refusal.string("data.conflict_token"))
            .isEqualTo(read.string("conflict_token"));
        assertThat(refusal.string("data.address")).isEqualTo(ADDRESS);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_update_that_names_nothing_is_update_empty() {
        Mcp.Result refusal = Mcp.call(UPDATE, args("address", ADDRESS,
            "conflict_token", token(ADDRESS), "fields", Map.of()));
        assertRefusal(refusal, "UPDATE_EMPTY", UPDATE, Map.of("call", UPDATE,
            "address", ADDRESS));
        assertConcernsAVisibleEntry(refusal, List.of(UPDATE));
    }

    // ======================================================================
    // The values
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_type_that_is_not_one_of_the_six_is_type_unknown_and_lists_them() {
        Map<String, String> values = Map.of("value", "rule",
            "list", String.join(", ", Contract.types()));
        assertRefusal(create("decision", "decision.tabs", "rule", "x"), "TYPE_UNKNOWN", CREATE,
            values);
        assertRefusal(Mcp.call(QUERY, args("scope", SCOPE, "type", "rule")), "TYPE_UNKNOWN",
            QUERY, values);
        assertRefusal(Mcp.call(DIGEST, args("scope", SCOPE, "types", List.of("rule"))),
            "TYPE_UNKNOWN", DIGEST, values);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void blank_content_is_content_absent() {
        assertRefusal(create("decision", "decision.tabs", "decision", "   "),
            "CONTENT_ABSENT", CREATE, Map.of("call", CREATE));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void oversized_content_is_content_oversize_and_the_content_is_not_repeated() {
        int limit = contractNumber("one text of at most (\\d+) characters");
        String tooLong = "a sentence nobody should read back. ".repeat(limit / 10);
        Mcp.Result refusal = create("decision", "decision.tabs", "decision", tooLong);

        assertRefusal(refusal, "CONTENT_OVERSIZE", CREATE, Map.of(
            "n", String.valueOf(tooLong.length()), "limit", String.valueOf(limit)));
        assertThat(refusal.text()).doesNotContain("a sentence nobody should read back");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_reference_with_a_credential_is_refused_and_its_value_is_not_repeated() {
        String reference = "https://probe:hunter2@docs.example.org/adr?token=s3cr3t";
        Mcp.Result refusal = Mcp.call(CREATE, args("scope", SCOPE, "selector", "decision",
            "fields", Map.of("key", "decision.tabs", "type", "decision", "content", "x",
                "reference", reference)));

        assertRefusal(refusal, "REFERENCE_CREDENTIAL_BEARING", CREATE, Map.of());
        assertThat(refusal.text()).doesNotContain("hunter2").doesNotContain("s3cr3t")
            .doesNotContain("docs.example.org");
    }

    // ======================================================================
    // Reading
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_page_size_out_of_bounds_is_page_size_rejected() {
        int max = contractNumber("optional `page_size` \\(1 to (\\d+)");
        for (int asked : List.of(0, max + 1)) {
            assertRefusal(Mcp.call(QUERY, args("scope", SCOPE, "page_size", asked)),
                "PAGE_SIZE_REJECTED", QUERY, Map.of("value", String.valueOf(asked),
                    "call", QUERY, "max", String.valueOf(max)));
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_cursor_not_handed_out_is_cursor_malformed() {
        assertRefusal(Mcp.call(QUERY, args("scope", SCOPE, "after", "!!not-a-cursor")),
            "CURSOR_MALFORMED", QUERY, Map.of("value", "!!not-a-cursor"));
    }

    // ======================================================================
    // The arguments
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void an_undeclared_argument_is_refused_by_name_at_the_top_and_inside_fields() {
        assertRefusal(Mcp.call(READ, args("address", ADDRESS, "colour", "red")),
            "ARGUMENT_UNKNOWN", READ, Map.of("call", READ, "n", "colour",
                "list", String.join(", ", Contract.argumentsOf(READ).top().keySet())));

        assertRefusal(Mcp.call(UPDATE, args("address", ADDRESS, "conflict_token",
                token(ADDRESS), "fields", Map.of("content", "x", "colour", "red"))),
            "ARGUMENT_UNKNOWN", UPDATE, Map.of("call", UPDATE, "n", "colour",
                "list", String.join(", ", Contract.argumentsOf(UPDATE).fields().keySet())));

        assertRefusal(Mcp.call(UPDATE, args("address", ADDRESS, "conflict_token",
                token(ADDRESS), "fields", Map.of("key", "convention.renamed"))),
            "ARGUMENT_UNKNOWN", UPDATE, Map.of("call", UPDATE, "n", "key",
                "list", String.join(", ", Contract.argumentsOf(UPDATE).fields().keySet())));

        Mcp.Result narrowing = Mcp.call(QUERY, args("scope", SCOPE, "owner", "someone"));
        assertThat(narrowing.reason())
            .as("an argument that narrows a query and is not declared is ARGUMENT_UNKNOWN, "
                + "not the generic surface's PREDICATE_UNKNOWN")
            .isEqualTo("ARGUMENT_UNKNOWN");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_missing_argument_is_argument_missing_and_so_is_a_missing_conflict_token() {
        assertRefusal(Mcp.call(UPDATE, args("address", ADDRESS,
                "fields", Map.of("content", "x"))),
            "ARGUMENT_MISSING", UPDATE, Map.of("call", UPDATE, "n", "conflict_token"),
            "what it is");
        assertRefusal(Mcp.call(READ, Map.of()), "ARGUMENT_MISSING", READ,
            Map.of("call", READ, "n", "address"), "what it is");
        assertRefusal(Mcp.call(CREATE, args("scope", SCOPE, "selector", "decision",
                "fields", Map.of("key", "decision.tabs", "type", "decision"))),
            "ARGUMENT_MISSING", CREATE, Map.of("call", CREATE, "n", "content"), "what it is");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_value_of_the_wrong_kind_is_argument_invalid() {
        assertRefusal(Mcp.call(QUERY, args("scope", SCOPE, "page_size", "fifty")),
            "ARGUMENT_INVALID", QUERY, Map.of("n", "page_size", "call", QUERY),
            "value", "why");
        assertRefusal(Mcp.call(UPDATE, args("address", ADDRESS, "conflict_token",
                token(ADDRESS), "fields", "content")),
            "ARGUMENT_INVALID", UPDATE, Map.of("n", "fields", "call", UPDATE),
            "value", "why");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void content_of_the_wrong_kind_is_refused_without_being_shown_back() {
        Mcp.Result refusal = Mcp.call(UPDATE, args("address", ADDRESS, "conflict_token",
            token(ADDRESS), "fields", Map.of("content", List.of("a list of secrets"))));
        assertRefusal(refusal, "ARGUMENT_INVALID", UPDATE,
            Map.of("n", "content", "call", UPDATE), "value", "why");
        assertThat(refusal.text()).doesNotContain("a list of secrets");
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static Mcp.Result create(String selector, String key, String type,
                                     String content) {
        return Mcp.call(CREATE, args("scope", SCOPE, "selector", selector,
            "fields", Map.of("key", key, "type", type, "content", content)));
    }

    private static String token(String address) {
        return Mcp.call(READ, args("address", address)).string("conflict_token");
    }

    /** The three literal placeholders the two grammar patterns carry, as written. */
    private static Map<String, String> literal(Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values);
        all.put("scope", "<scope>");
        all.put("selector", "<selector>");
        all.put("id", "<id>");
        return all;
    }

    private static int contractNumber(String regex) {
        Matcher m = Pattern.compile(regex).matcher(Contract.text().replaceAll("\\s+", " "));
        assertThat(m.find()).as("the contract states %s", regex).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private static void assertNotFound(Mcp.Result refusal, String tool) {
        assertThat(refusal.isError()).isTrue();
        assertThat(refusal.reason()).isEqualTo("NOT_FOUND");
        assertThat(refusal.map("data"))
            .as("4.3: data carries attempted and nothing else")
            .containsExactlyEntriesOf(Map.of("attempted", tool));
    }

    private static void assertRefusal(Mcp.Result refusal, String reason, String tool,
                                      Map<String, String> values, String... open) {
        assertThat(refusal.isError()).as("%s", refusal).isTrue();
        assertThat(refusal.reason()).as("%s", refusal).isEqualTo(reason);
        Pattern expected = Contract.message(Contract.patternOf(reason), values, open);
        assertThat(refusal.message())
            .as("the message of %s, built from the contract's pattern alone", reason)
            .matches(expected);
        assertThat(refusal.string("data.attempted"))
            .as("4.1: data.attempted is present on every refusal")
            .isEqualTo(tool);
        try {
            assertThat(new ObjectMapper().readValue(refusal.text(), Map.class))
                .isEqualTo(refusal.structured());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertConcernsAVisibleEntry(Mcp.Result refusal, List<String> next) {
        assertThat(refusal.string("data.state"))
            .as("4.1: the refusal concerns an entry this caller may see, so it carries its "
                + "state")
            .isNotBlank();
        assertThat(refusal.strings("data.next.call"))
            .as("and data.next lists the calls the message names")
            .containsExactlyElementsOf(next);
    }
}
