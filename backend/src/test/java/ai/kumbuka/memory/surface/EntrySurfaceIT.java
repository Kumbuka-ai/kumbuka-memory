package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 — the six verbs over REST, against the wire form DEC-0040 to DEC-0042
 * prescribe.
 *
 * <p>Every expectation here is taken from those three nodes and from the
 * dispatch's own contract text, never from what this service happens to
 * answer. Where the two would differ, the node wins and this suite goes red.
 *
 * <h2>Why the probe is over HTTP and not against the verb surface</h2>
 *
 * The form IS the contract: the path a caller writes, the method, the header
 * the conflict token travels in, the members of the answer. A probe against
 * {@link VerbSurface} would measure the layer below the claim and would stay
 * green through a change to any of them.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class EntrySurfaceIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String SELECTOR = "decision";
    private static final String KEY = "decision.storage-engine";
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/storage-engine";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyScope() {
        SurfaceFixture.clearEntries();
    }

    // ==================================================================
    // create
    // ==================================================================

    @Test
    void create_answers_201_with_the_complete_address_the_fields_the_token_and_the_next_steps() {
        Response created = call()
            .body(fields("key", KEY, "type", "decision", "content", "postgres, for the "
                + "row-level security the isolation rests on"))
            .post(API + "/" + SCOPE + "/" + SELECTOR);

        assertThat(created.statusCode())
            .as("a verb that brings an object into being answers 201, and the Location "
                + "names where it now stands")
            .isEqualTo(201);
        assertThat(created.header("Location"))
            .endsWith(API + "/" + SCOPE + "/decision/storage-engine");

        assertThat(created.jsonPath().getString("address"))
            .as("every address a service returns is COMPLETE and can be handed back to "
                + "the next call unchanged (DEC-0040) — a form without its scheme or its "
                + "scope appears nowhere")
            .isEqualTo(ADDRESS);
        assertThat(created.jsonPath().getString("fields.key")).isEqualTo(KEY);
        assertThat(created.jsonPath().getString("fields.scope")).isEqualTo(SCOPE);
        assertThat(created.jsonPath().getString("fields.type")).isEqualTo("decision");
        assertThat(created.jsonPath().getBoolean("fields.private")).isFalse();

        assertThat(created.jsonPath().getString("conflict_token"))
            .as("the conflict token is answered top-level, BESIDE the fields and never "
                + "inside them: it guards the write and is not a value of the object")
            .isNotBlank();
        assertThat(created.jsonPath().getMap("fields"))
            .doesNotContainKey("conflict_token");
        assertThat(created.header("ETag"))
            .as("and the same value travels as the entity tag, which is where HTTP "
                + "already puts the precondition of a write")
            .contains(created.jsonPath().getString("conflict_token"));

        assertThat(created.jsonPath().getList("next.call", String.class))
            .as("every answer about an object names the calls open to this caller "
                + "(REQ-0152 via DEC-0040), each with the complete address")
            .containsExactlyInAnyOrder("read " + ADDRESS, "update " + ADDRESS,
                "withdraw " + ADDRESS);
        assertThat(created.jsonPath().getList("next.does", String.class))
            .allSatisfy(does -> assertThat(does).isNotBlank());
    }

    @Test
    void create_onto_an_occupied_address_is_already_exists_and_carries_that_address() {
        create(KEY, "decision", "the first one");

        Response second = call()
            .body(fields("key", KEY, "type", "decision", "content", "a second one"))
            .post(API + "/" + SCOPE + "/" + SELECTOR);

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.jsonPath().getString("reason"))
            .as("A4: create only ever lays down a new entry. There is no upsert and no "
                + "'existed' flag — the operator decided on 2026-09-19 that a conflict is "
                + "ALREADY_EXISTS with the address of what stands there")
            .isEqualTo("ALREADY_EXISTS");
        assertThat(second.jsonPath().getString("data.address"))
            .as("and the address is complete, so a caller that meant to change the entry "
                + "has somewhere to send the change")
            .isEqualTo(ADDRESS);
        assertThat(second.jsonPath().getString("message"))
            .as("the message names the remedy, which DEC-0042 requires where there is one")
            .contains("update");

        assertThat(call().get(API + "/" + SCOPE + "/decision/storage-engine")
                .jsonPath().getString("fields.content"))
            .as("and nothing was overwritten")
            .isEqualTo("the first one");
    }

    @Test
    void a_key_with_no_selector_and_the_reserved_selector_are_both_refused_by_name() {
        Response noSelector = call()
            .body(fields("key", "storage", "type", "decision", "content", "x"))
            .post(API + "/" + SCOPE + "/" + SELECTOR);

        assertThat(noSelector.statusCode()).isEqualTo(400);
        assertThat(noSelector.jsonPath().getString("reason"))
            .as("A8: a key without a dot has no selector, so the entry would have no "
                + "address to be read back by")
            .isEqualTo("SELECTOR_ABSENT");
        assertThat(noSelector.jsonPath().getString("message"))
            .as("and the refusal says what to write instead")
            .contains("<selector>.<id>");

        Response reserved = call()
            .body(fields("key", "system.notes", "type", "convention", "content", "x"))
            .post(API + "/" + SCOPE + "/system");

        assertThat(reserved.statusCode()).isEqualTo(422);
        assertThat(reserved.jsonPath().getString("reason"))
            .as("A8: 'system' is reserved for entries this service lays down itself")
            .isEqualTo("SELECTOR_RESERVED");
    }

    @Test
    void a_body_that_sets_a_field_the_verb_does_not_write_is_refused_and_names_it() {
        Response answer = call()
            .body("{\"fields\":{\"key\":\"" + KEY + "\",\"type\":\"decision\","
                + "\"content\":\"x\",\"scope\":\"somewhere-else\",\"state\":\"draft\"}}")
            .post(API + "/" + SCOPE + "/" + SELECTOR);

        assertThat(answer.statusCode()).isEqualTo(422);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("FIELD_IMMUTABLE");
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .as("named rather than ignored: a caller overruled in silence finds out from "
                + "the data instead of from the answer")
            .containsExactlyInAnyOrder("scope", "state");
    }

    // ==================================================================
    // read
    // ==================================================================

    @Test
    void read_answers_the_same_shape_on_the_canonical_and_on_the_technical_address() {
        String token = create(KEY, "decision", "postgres").jsonPath()
            .getString("conflict_token");

        Response canonical = call().get(API + "/" + SCOPE + "/decision/storage-engine");
        assertThat(canonical.statusCode()).isEqualTo(200);
        assertThat(canonical.jsonPath().getString("address")).isEqualTo(ADDRESS);
        assertThat(canonical.jsonPath().getString("conflict_token")).isEqualTo(token);

        Response technical = call().get(API + "/"
            + SurfaceFixture.logicalIdOf(SubstrateDatabaseResource.SCOPE_ID, KEY));

        assertThat(technical.statusCode())
            .as("the technical address memory://<uuid> names the primary object and is "
                + "accepted by read")
            .isEqualTo(200);
        assertThat(technical.jsonPath().getString("address"))
            .as("and the answer carries the CANONICAL address: the uuid goes in and never "
                + "comes back out")
            .isEqualTo(ADDRESS);
        assertThat(technical.asString())
            .as("A5 in the one place a uuid is a legitimate thing to send")
            .doesNotContainIgnoringCase(SurfaceFixture
                .logicalIdOf(SubstrateDatabaseResource.SCOPE_ID, KEY).toString());
    }

    @Test
    void an_address_that_names_nothing_is_the_single_not_found_with_no_data() {
        Response answer = call().get(API + "/" + SCOPE + "/decision/nothing-here");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
        assertThat(answer.jsonPath().getMap("data"))
            .as("the not-found class carries no data at all (DEC-0042), because anything "
                + "in it would be a difference the three cases could be told apart by")
            .isNull();
    }

    // ==================================================================
    // update
    // ==================================================================

    @Test
    void update_needs_the_current_token_and_answers_the_entry_with_a_new_one() {
        String token = create(KEY, "decision", "postgres").jsonPath()
            .getString("conflict_token");
        String item = API + "/" + SCOPE + "/decision/storage-engine";

        Response missing = call().body(fields("content", "postgres 16")).patch(item);
        assertThat(missing.statusCode()).isEqualTo(409);
        assertThat(missing.jsonPath().getString("reason"))
            .isEqualTo("CONFLICT_TOKEN_MISSING");

        Response stale = call().header("If-Match", "1999-01-01T00:00:00Z")
            .body(fields("content", "postgres 16")).patch(item);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.jsonPath().getString("reason")).isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(stale.jsonPath().getString("data.conflict_token"))
            .as("a stale token is refused WITH the current token and the entry as this "
                + "caller's own read would answer it (DEC-0041), so the write can be "
                + "re-formed from the refusal instead of from a second read")
            .isEqualTo(token);
        assertThat(stale.jsonPath().getString("data.fields.content")).isEqualTo("postgres");
        assertThat(stale.jsonPath().getString("data.address")).isEqualTo(ADDRESS);

        Response changed = call().header("If-Match", token)
            .body(fields("content", "postgres 16")).patch(item);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.jsonPath().getString("fields.content")).isEqualTo("postgres 16");
        assertThat(changed.jsonPath().getString("conflict_token"))
            .as("a write that changed something moves the token")
            .isNotEqualTo(token);
    }

    @Test
    void an_update_that_changes_nothing_writes_nothing_and_keeps_the_token() {
        String token = create(KEY, "decision", "postgres").jsonPath()
            .getString("conflict_token");

        Response repeated = call().header("If-Match", token)
            .body(fields("content", "postgres"))
            .patch(API + "/" + SCOPE + "/decision/storage-engine");

        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.jsonPath().getString("conflict_token"))
            .as("DEC-0041: an update whose values equal the current projection writes "
                + "nothing, allocates no new token and does not change the entry's change "
                + "time — so a retry that arrives twice is not a second version")
            .isEqualTo(token);
    }

    @Test
    void update_refuses_a_field_that_is_fixed_for_the_life_of_the_entry() {
        String token = create(KEY, "decision", "postgres").jsonPath()
            .getString("conflict_token");

        Response answer = call().header("If-Match", token)
            .body(fields("key", "decision.something-else"))
            .patch(API + "/" + SCOPE + "/decision/storage-engine");

        assertThat(answer.statusCode()).isEqualTo(422);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("FIELD_IMMUTABLE");
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .containsExactly("key");
    }

    // ==================================================================
    // withdraw
    // ==================================================================

    @Test
    void withdraw_needs_the_token_destroys_the_entry_and_says_the_address_is_free() {
        String token = create(KEY, "decision", "postgres").jsonPath()
            .getString("conflict_token");
        String item = API + "/" + SCOPE + "/decision/storage-engine";

        assertThat(call().post(item + ":withdraw").statusCode())
            .as("a withdrawal is a write and carries the conflict token like every other "
                + "one (DEC-0041)")
            .isEqualTo(409);

        Response withdrawn = call().header("If-Match", token).post(item + ":withdraw");
        assertThat(withdrawn.statusCode()).isEqualTo(200);
        assertThat(withdrawn.jsonPath().getString("address")).isEqualTo(ADDRESS);
        assertThat(withdrawn.jsonPath().getString("outcome"))
            .as("the community edition forgets: an erasure obligation honoured by setting "
                + "a boolean is not an erasure")
            .isEqualTo("destroyed");
        assertThat(withdrawn.jsonPath().getBoolean("address_released")).isTrue();
        assertThat(withdrawn.jsonPath().getList("next.call", String.class))
            .as("and the answer says what is open next, which after a destroy is laying "
                + "a new entry down at the freed address")
            .containsExactly("create memory://" + SCOPE + "/decision");

        assertThat(call().get(item).statusCode())
            .as("and the entry is gone")
            .isEqualTo(404);
    }

    // ==================================================================
    // What the address space does NOT offer
    // ==================================================================

    @Test
    void a_verb_at_the_wrong_depth_answers_405_and_says_what_the_address_does_offer() {
        create(KEY, "decision", "postgres");

        Response plainItemPost = call().post(API + "/" + SCOPE + "/decision/storage-engine");
        assertThat(plainItemPost.statusCode())
            .as("POST is not something an entry offers: what the verb set lacks is not "
                + "offered even where the convention expects it")
            .isEqualTo(405);
        assertThat(plainItemPost.header("Allow"))
            .as("a 405 without Allow refuses without saying what would have worked")
            .isEqualTo("GET, PATCH, POST");

        Response digestAtTheWrongDepth = call()
            .post(API + "/" + SCOPE + "/decision:digest");
        assertThat(digestAtTheWrongDepth.statusCode()).isEqualTo(405);
        assertThat(digestAtTheWrongDepth.jsonPath().getString("reason"))
            .as("the address resolved and the verb did not exist at that depth — 405 and "
                + "not 404, which would send the caller looking for the object")
            .isEqualTo("VERB_NOT_CARRIED");
    }

    @Test
    void a_filter_on_an_address_that_names_one_entry_is_refused_rather_than_ignored() {
        create(KEY, "decision", "postgres");

        Response answer = call()
            .get(API + "/" + SCOPE + "/decision/storage-engine?type=convention");

        assertThat(answer.statusCode()).isEqualTo(422);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("PREDICATE_UNKNOWN");
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .containsExactly("type");
    }

    // ------------------------------------------------------------------

    private static Response create(String key, String type, String content) {
        Response created = call()
            .body(fields("key", key, "type", type, "content", content))
            .post(API + "/" + SCOPE + "/" + key.substring(0, key.indexOf('.')));
        assertThat(created.statusCode())
            .as("the fixture's own create must succeed, or every assertion after it is "
                + "about an entry that is not there")
            .isEqualTo(201);
        return created;
    }
}
