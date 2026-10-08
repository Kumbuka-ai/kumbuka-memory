package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant surface, called over HTTP against a running database, under
 * the real service role.
 *
 * <p>The protocol, the shape of every answer, and the one rule about
 * {@code next} that a reading of the code cannot settle: that every listed
 * call succeeds for this caller and every call that would succeed is listed.
 * That rule is checked the only way it can be — by making the calls.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AssistantSurfaceIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String KEY = "convention.branch-names";
    private static final String ADDRESS = "memory://" + SCOPE + "/convention/branch-names";

    private static final List<String> ENTRY_FIELDS = List.of("scope", "key", "type", "content",
        "reference", "state", "private", "created_at", "updated_at");

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyTable() {
        SurfaceFixture.clearEntries();
    }

    // ======================================================================
    // The protocol
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void initialize_names_the_server_and_offers_tools() {
        JsonPath answer = Mcp.rpc("initialize", Map.of("protocolVersion", "2025-06-18",
            "capabilities", Map.of(), "clientInfo", Map.of("name", "probe", "version", "1")))
            .jsonPath();
        assertThat(answer.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(answer.getString("result.protocolVersion")).isNotBlank();
        assertThat(answer.getMap("result.capabilities")).containsKey("tools");
        assertThat(answer.getString("result.serverInfo.name")).isEqualTo("kumbuka-memory");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void tools_list_answers_exactly_the_six_tools_with_the_contract_s_descriptions() {
        JsonPath answer = Mcp.rpc("tools/list", Map.of()).jsonPath();
        List<Map<String, Object>> tools = answer.getList("result.tools");

        assertThat(tools.stream().map(t -> (String) t.get("name")).toList())
            .containsExactlyElementsOf(Contract.tools());
        Map<String, String> described = Contract.descriptions();
        for (Map<String, Object> tool : tools) {
            assertThat(tool.get("description"))
                .as("the description %s answers on the wire", tool.get("name"))
                .isEqualTo(described.get((String) tool.get("name")));
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void the_declaration_is_served_and_carries_the_calls_and_the_reasons() {
        JsonPath declaration = given().get(Mcp.PATH + "/declaration").then()
            .statusCode(200).extract().jsonPath();
        assertThat(declaration.getList("calls.call", String.class))
            .containsExactlyElementsOf(Contract.tools());
        assertThat(declaration.getList("reasons.reason", String.class))
            .containsAll(Contract.surfaceReasons())
            .containsAll(Contract.genericOnlyReasons());
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_method_that_does_not_exist_and_a_tool_that_does_not_exist_are_protocol_errors() {
        assertThat(Mcp.rpc("resources/list", Map.of()).jsonPath().getInt("error.code"))
            .isEqualTo(-32601);
        assertThat(Mcp.rpc("tools/call", Map.of("name", "memory_relate", "arguments", Map.of()))
            .jsonPath().getInt("error.code"))
            .isEqualTo(-32602);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void arguments_that_are_present_and_not_an_object_are_a_protocol_error() {
        String read = Contract.toolFor("read");
        for (Object notAnObject : List.of("memory://" + SCOPE + "/convention/x", List.of(1),
                42)) {
            JsonPath answer = Mcp.rpc("tools/call", Map.of("name", read,
                "arguments", notAnObject)).jsonPath();
            assertThat(answer.<Object>get("error.code"))
                .as("arguments = %s: no call yet, so an error of the protocol", notAnObject)
                .isEqualTo(-32602);
            assertThat(answer.getMap("result"))
                .as("and not a refusal in the envelope")
                .isNull();
        }
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void missing_arguments_are_the_empty_object() {
        String digest = Contract.toolFor("digest");
        JsonPath answer = Mcp.rpc("tools/call", Map.of("name", digest)).jsonPath();
        assertThat(answer.getMap("error"))
            .as("no arguments at all is a call with none")
            .isNull();
        assertThat(answer.getBoolean("result.isError")).isTrue();
        assertThat(answer.getString("result.structuredContent.reason"))
            .as("refused for the argument it lacks, in the envelope")
            .isEqualTo("ARGUMENT_MISSING");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_notification_is_accepted_without_an_answer() {
        Response answer = Mcp.post(Map.of("jsonrpc", "2.0",
            "method", "notifications/initialized"));
        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(answer.asString()).isEmpty();
    }

    @Test
    void the_surface_is_authenticated_as_the_generic_one_is() {
        assertThat(Mcp.rpc("tools/list", Map.of()).statusCode())
            .as("no token, no answer — on both surfaces alike")
            .isEqualTo(given().get("/api/" + SCOPE).statusCode())
            .isEqualTo(401);
    }

    @Test
    void the_declaration_is_authenticated_as_the_rest_of_the_surface_is() {
        assertThat(given().get(Mcp.PATH + "/declaration").statusCode())
            .as("no token, no declaration: the surface is authenticated on every route")
            .isEqualTo(401);
    }

    // ======================================================================
    // The answers (section 3)
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void create_read_and_update_answer_address_fields_token_and_next() throws Exception {
        Mcp.Result created = create(KEY, "convention", "feature/<slug>");
        assertEntryAnswer(created, true);
        assertThat(created.string("address")).isEqualTo(ADDRESS);

        Mcp.Result read = Mcp.call(Contract.toolFor("read"), args("address", ADDRESS));
        assertEntryAnswer(read, true);
        assertThat(read.string("fields.content")).isEqualTo("feature/<slug>");

        Mcp.Result updated = Mcp.call(Contract.toolFor("update"), args("address", ADDRESS,
            "conflict_token", read.string("conflict_token"),
            "fields", Map.of("content", "feature/<topic>")));
        assertEntryAnswer(updated, true);
        assertThat(updated.string("fields.content")).isEqualTo("feature/<topic>");
        assertThat(updated.string("conflict_token"))
            .as("the entry stays in force and carries a new conflict token")
            .isNotEqualTo(read.string("conflict_token"));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void query_answers_a_listing_whose_entries_have_the_answer_shape() throws Exception {
        create(KEY, "convention", "feature/<slug>");
        create("decision.storage-engine", "decision", "postgres");

        Mcp.Result listing = Mcp.call(Contract.toolFor("query"), args("scope", SCOPE));
        assertThat(listing.isError()).isFalse();
        assertThat(listing.structured()).containsOnlyKeys("entries", "truncated", "total",
            "page_size", "cursor", "order", "unaddressable");
        List<Map<String, Object>> entries = listing.json()
            .getList("result.structuredContent.entries");
        assertThat(entries).hasSize(2);
        for (Map<String, Object> entry : entries) {
            assertThat(entry).containsOnlyKeys("address", "fields", "conflict_token", "next");
        }

        Mcp.Result narrowed = Mcp.call(Contract.toolFor("query"), args("scope", SCOPE,
            "type", "decision", "text", "postgres", "page_size", 1));
        assertThat(narrowed.strings("entries.address"))
            .as("each declared narrowing argument has the effect its declaration states")
            .containsExactly("memory://" + SCOPE + "/decision/storage-engine");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void digest_answers_the_digest_shape() throws Exception {
        create(KEY, "convention", "feature/<slug>");
        Mcp.Result digest = Mcp.call(Contract.toolFor("digest"), args("scope", SCOPE,
            "types", List.of("convention")));
        assertThat(digest.isError()).isFalse();
        assertThat(digest.structured()).containsOnlyKeys("address", "scopes", "selected_types",
            "summary", "total_entries", "total_token_estimate", "sections", "unaddressable",
            "truncated");
        assertThat(digest.strings("sections.entries.flatten().address"))
            .containsExactly(ADDRESS);
        assertTextIsStructured(digest);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void withdraw_answers_the_outcome_and_the_one_call_open_after_it() throws Exception {
        String token = create(KEY, "convention", "feature/<slug>").string("conflict_token");

        Mcp.Result withdrawn = Mcp.call(Contract.toolFor("withdraw"),
            args("address", ADDRESS, "conflict_token", token));
        assertThat(withdrawn.isError()).isFalse();
        assertThat(withdrawn.structured())
            .containsOnlyKeys("address", "outcome", "address_released", "next");
        assertThat(withdrawn.string("outcome")).isIn("destroyed", "tombstoned");

        boolean released = withdrawn.json()
            .getBoolean("result.structuredContent.address_released");
        assertThat(withdrawn.strings("next.call"))
            .containsExactlyElementsOf(Contract.nextAfterWithdrawal(released));
        assertThat(withdrawn.strings("next.does"))
            .containsExactly(Contract.doesAfterWithdrawal(released));

        Mcp.Result again = create(KEY, "convention", "feature/<topic>");
        assertThat(again.isError())
            .as("the listed call is made, and succeeds")
            .isEqualTo(!released);
    }

    // ======================================================================
    // next lists what succeeds, and only that (section 6)
    // ======================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_writer_is_offered_read_update_and_withdraw_and_all_three_succeed() {
        plant(SubstrateDatabaseResource.SCOPE_ID);
        everyListedCallSucceedsAndOnlyThose(ADDRESS, true);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void a_reader_without_the_write_right_is_offered_read_alone_and_the_rest_is_refused() {
        plant(SubstrateDatabaseResource.SCOPE_ID);
        everyListedCallSucceedsAndOnlyThose(ADDRESS, false);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void in_a_locked_scope_read_alone_is_offered_and_the_rest_is_refused() {
        plant(SubstrateDatabaseResource.LOCKED_SCOPE_ID);
        everyListedCallSucceedsAndOnlyThose(
            "memory://" + LOCKED + "/convention/branch-names", false);
    }

    private static void everyListedCallSucceedsAndOnlyThose(String address,
                                                            boolean mayWrite) {
        Mcp.Result read = Mcp.call(Contract.toolFor("read"), args("address", address));
        assertThat(read.isError()).isFalse();
        List<String> offered = read.strings("next.call");
        assertThat(offered)
            .as("section 6, for a caller who %s write", mayWrite ? "may" : "may not")
            .containsExactlyElementsOf(Contract.nextOnAnEntry(mayWrite));

        String scope = address.substring("memory://".length(), address.indexOf('/', 10));
        Mcp.Result listed = Mcp.call(Contract.toolFor("query"), args("scope", scope));
        assertThat(listed.strings("entries[0].next.call"))
            .as("an entry in a listing offers what the same entry read alone offers")
            .containsExactlyElementsOf(offered);

        Mcp.Result updated = Mcp.call(Contract.toolFor("update"), args("address", address,
            "conflict_token", read.string("conflict_token"),
            "fields", Map.of("content", "changed by the probe")));
        assertThat(updated.isError())
            .as("memory_update is %s, so it must %s", offered.contains(Contract.toolFor("update"))
                ? "listed" : "not listed", offered.contains(Contract.toolFor("update"))
                ? "succeed" : "be refused")
            .isEqualTo(!offered.contains(Contract.toolFor("update")));

        String token = updated.isError()
            ? read.string("conflict_token")
            : updated.string("conflict_token");
        Mcp.Result withdrawn = Mcp.call(Contract.toolFor("withdraw"),
            args("address", address, "conflict_token", token));
        assertThat(withdrawn.isError())
            .as("memory_withdraw likewise")
            .isEqualTo(!offered.contains(Contract.toolFor("withdraw")));
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private static Mcp.Result create(String key, String type, String content) {
        String selector = key.substring(0, key.indexOf('.'));
        return Mcp.call(Contract.toolFor("create"), args("scope", SCOPE, "selector", selector,
            "fields", Map.of("key", key, "type", type, "content", content)));
    }

    private static void plant(String scopeId) {
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(scopeId, KEY, "convention",
            "feature/<slug>"));
    }

    private static void assertEntryAnswer(Mcp.Result answer, boolean mayWrite)
            throws Exception {
        assertThat(answer.isError()).as("%s", answer).isFalse();
        assertThat(answer.structured())
            .containsOnlyKeys("address", "fields", "conflict_token", "next");
        assertThat(answer.string("address")).startsWith("memory://");
        assertThat(answer.map("fields").keySet()).containsExactlyInAnyOrderElementsOf(ENTRY_FIELDS);
        assertThat(answer.string("conflict_token")).isNotBlank();
        assertThat(answer.strings("next.call"))
            .containsExactlyElementsOf(Contract.nextOnAnEntry(mayWrite));
        Map<String, String> does = Contract.doesOnAnEntry();
        List<Map<String, Object>> next = answer.json().getList("result.structuredContent.next");
        for (Map<String, Object> step : next) {
            assertThat(step.get("does")).isEqualTo(does.get((String) step.get("call")));
        }
        assertTextIsStructured(answer);
    }

    private static void assertTextIsStructured(Mcp.Result answer) throws Exception {
        assertThat(new ObjectMapper().readValue(answer.text(), Map.class))
            .as("a client that reads only the text content reads the same answer")
            .isEqualTo(answer.structured());
    }
}
