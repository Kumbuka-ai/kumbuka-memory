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
 * The token a write answers with is the token the stored row has.
 *
 * <h2>The defect</h2>
 *
 * The conflict token is the entry's change time rendered as a string, and the
 * change time of an update was set by the application with
 * {@code Instant.now()}. That call takes its resolution from the platform
 * clock — microseconds on macOS, nanoseconds on Linux — while a
 * {@code timestamptz} column keeps microseconds. On Linux the answer to an
 * update therefore carried a token with three digits the row never had, and
 * the caller's next write was refused {@code CONFLICT_TOKEN_STALE} against
 * the very token it had just been handed. Every caller broke on its second
 * write, in production, and nowhere else.
 *
 * <h2>Why this probe is green on macOS with the fix removed</h2>
 *
 * Because the macOS clock is already as coarse as the column. That is not a
 * weakness of the probe, it is the shape of the defect, and it is why the red
 * state of this class is recorded from a CI run on Linux rather than from a
 * developer's machine. {@code StoredInstantTest} carries the same rule in a
 * form that is red on any platform; this one carries the acceptance, against
 * a real column and through the surface.
 *
 * <h2>What is asserted</h2>
 *
 * Two things, and the second is the general form of the first:
 *
 * <ol>
 *   <li>{@code create -> update -> withdraw}, where the withdrawal presents
 *       the token the update answered with. It is the sequence every caller
 *       performs and the one that failed.</li>
 *   <li>For each writing answer, the token it carries against the token of a
 *       read that immediately follows it. A read projects the stored row, so
 *       the comparison is between what the write said and what was kept.</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class ConflictTokenPrecisionIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String SELECTOR = "decision";
    private static final String KEY = "decision.storage-engine";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage-engine";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyScope() {
        SurfaceFixture.clearEntries();
    }

    /**
     * The sequence a caller performs, with the token it was given.
     *
     * <p>The withdrawal is the second write and is where the defect surfaced:
     * it presents the token from the update's own answer, so a token that
     * never matched the row is refused here with nothing else having gone
     * wrong.
     */
    @Test
    void a_write_that_follows_an_update_is_accepted_with_the_token_the_update_answered() {
        create();

        Response updated = call().header("If-Match", tokenOfCurrentEntry())
            .body(fields("content", "postgres 16, for the jsonb path"))
            .patch(ITEM);
        assertThat(updated.statusCode()).isEqualTo(200);

        String tokenFromTheUpdate = updated.jsonPath().getString("conflict_token");

        Response withdrawn = call().header("If-Match", tokenFromTheUpdate)
            .post(ITEM + ":withdraw");

        assertThat(withdrawn.statusCode())
            .as("the token an update hands out has to be the one the stored row carries, "
                + "or the caller's next write is refused against a token this service "
                + "produced one call earlier. Refusal reason, when this is red: %s",
                withdrawn.jsonPath().getString("reason"))
            .isEqualTo(200);
    }

    /**
     * The general rule, for every answer that writes.
     *
     * <p>{@code withdraw} is not here and cannot be: it answers what it did
     * rather than a projection of an entry, so it carries no token — and
     * after it there is no row left to read one from.
     */
    @Test
    void every_writing_answer_carries_the_token_a_following_read_answers() {
        Response created = create();
        assertThat(created.jsonPath().getString("conflict_token"))
            .as("the token of the answer to 'create' against the token of a read of the "
                + "row it laid down")
            .isEqualTo(tokenOfCurrentEntry());

        Response updated = call().header("If-Match", tokenOfCurrentEntry())
            .body(fields("content", "postgres 16, for the jsonb path"))
            .patch(ITEM);
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.jsonPath().getString("conflict_token"))
            .as("the token of the answer to 'update' against the token of a read of the "
                + "row it changed — this is the one that differed, because 'update' is "
                + "the only write whose change time the application sets rather than the "
                + "column default")
            .isEqualTo(tokenOfCurrentEntry());

        Response second = call().header("If-Match", tokenOfCurrentEntry())
            .body(fields("type", "convention"))
            .patch(ITEM);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.jsonPath().getString("conflict_token"))
            .as("and again on a second update, so that what is asserted is the rule and "
                + "not one lucky moment")
            .isEqualTo(tokenOfCurrentEntry());
    }

    /**
     * The refusal's token is the stored one too.
     *
     * <p>A stale-token refusal carries the current token so the caller can
     * re-form its write from the refusal (DEC-0041). If that token were the
     * in-memory value rather than the stored one, the re-formed write would
     * be refused as well and the caller would have no way out at all.
     */
    @Test
    void the_token_a_stale_refusal_hands_back_is_one_the_next_write_is_accepted_with() {
        create();

        call().header("If-Match", tokenOfCurrentEntry())
            .body(fields("content", "postgres 16, for the jsonb path"))
            .patch(ITEM)
            .then().statusCode(200);

        Response stale = call().header("If-Match", "1999-01-01T00:00:00Z")
            .body(fields("content", "postgres 17"))
            .patch(ITEM);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.jsonPath().getString("reason")).isEqualTo("CONFLICT_TOKEN_STALE");

        Response retried = call()
            .header("If-Match", stale.jsonPath().getString("data.conflict_token"))
            .body(fields("content", "postgres 17"))
            .patch(ITEM);

        assertThat(retried.statusCode())
            .as("the way out of a lost race is the token the refusal carried; a refusal "
                + "that handed back an unusable token would leave the caller with no "
                + "sequence that ever succeeds")
            .isEqualTo(200);
    }

    // ------------------------------------------------------------------

    private static Response create() {
        Response created = call()
            .body(fields("key", KEY, "type", "decision", "content", "postgres"))
            .post(API + "/" + SCOPE + "/" + SELECTOR);
        assertThat(created.statusCode())
            .as("the fixture's own create must succeed, or every assertion after it is "
                + "about an entry that is not there")
            .isEqualTo(201);
        return created;
    }

    /** The token of the entry as it stands, read through the surface. */
    private static String tokenOfCurrentEntry() {
        Response read = call().get(ITEM);
        assertThat(read.statusCode()).isEqualTo(200);
        return read.jsonPath().getString("conflict_token");
    }
}
