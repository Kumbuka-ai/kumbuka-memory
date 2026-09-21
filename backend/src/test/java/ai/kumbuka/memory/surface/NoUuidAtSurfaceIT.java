package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 — no generated identifier reaches a caller, on any answer of any verb or
 * any refusal.
 *
 * <h2>Why one sweep and not an assertion per answer</h2>
 *
 * The rule is about the surface and not about a field. A per-answer assertion
 * would be a list of the places somebody thought of, and the whole point is
 * the place nobody thought of: an address that fell back to a logical id, a
 * refusal that named a scope by the value it resolved, an error handler that
 * echoed what it was given. So every answer this suite can provoke is
 * collected and read as text.
 *
 * <p>The technical address is in the battery deliberately. It is the one call
 * where a uuid is a legitimate thing to SEND, which makes it the one call
 * where an answer could most plausibly hand it back.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class NoUuidAtSurfaceIT {

    /**
     * A uuid in any of its ordinary spellings, upper or lower case.
     *
     * <p>Written out rather than taken from {@link UUID}, which has no pattern
     * to borrow. It is deliberately loose about the version nibble: a value
     * that merely LOOKS like a generated identifier is as much of a leak as
     * one that is, because a reader cannot tell the difference either.
     */
    private static final Pattern UUID_SHAPED = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "decision.storage-engine";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage-engine";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void the_detection_finds_a_uuid_where_there_is_one() {
        assertThat(UUID_SHAPED.matcher(
                "{\"address\":\"memory://" + UUID.randomUUID() + "\"}").find())
            .as("RED STATE, observed on every run: without this, the sweep below would be "
                + "a search that found nothing rather than a surface that contains "
                + "nothing, and the two look identical from the outside")
            .isTrue();
    }

    @Test
    void no_answer_and_no_refusal_of_any_verb_carries_a_generated_identifier() {
        List<Answer> answers = everyAnswerThisSurfaceCanGive();

        assertThat(answers)
            .as("the sweep must have had something to read")
            .hasSizeGreaterThan(14);
        assertThat(answers).anySatisfy(answer ->
            assertThat(answer.status()).isBetween(200, 299));
        assertThat(answers).anySatisfy(answer ->
            assertThat(answer.status()).isGreaterThanOrEqualTo(400));

        List<String> leaking = answers.stream()
            .filter(answer -> UUID_SHAPED.matcher(answer.body()).find())
            .map(Answer::describe)
            .toList();

        assertThat(leaking)
            .as("A5: outward there are only complete addresses and no uuid where people "
                + "work with an entry. The id is speaking, the scope is a slug, the type "
                + "and the state are names, and the row's logical id does not leave the "
                + "transaction")
            .isEmpty();
    }

    /**
     * Every shape this surface produces, success and refusal alike.
     *
     * <p>Assembled in one method so that the battery is a list a reader can
     * check against the verb set, rather than something spread across a dozen
     * test methods where an omission is invisible.
     */
    private static List<Answer> everyAnswerThisSurfaceCanGive() {
        SurfaceFixture.clearEntries();
        List<Answer> answers = new ArrayList<>();

        answers.add(answerOf("create", call()
            .body(fields("key", KEY, "type", "decision", "content", "postgres"))
            .post(API + "/" + SCOPE + "/decision")));

        Response read = call().get(ITEM);
        answers.add(answerOf("read", read));
        String token = read.jsonPath().getString("conflict_token");

        UUID logicalId = SurfaceFixture.logicalIdOf(SubstrateDatabaseResource.SCOPE_ID, KEY);
        answers.add(answerOf("read by technical address", call().get(API + "/" + logicalId)));

        answers.add(answerOf("query over the scope", call().get(API + "/" + SCOPE)));
        answers.add(answerOf("query in a selector", call().get(API + "/" + SCOPE + "/decision")));
        answers.add(answerOf("digest", call().post(API + "/" + SCOPE + ":digest")));

        answers.add(answerOf("create onto an occupied address", call()
            .body(fields("key", KEY, "type", "decision", "content", "again"))
            .post(API + "/" + SCOPE + "/decision")));
        answers.add(answerOf("create with no selector in the key", call()
            .body(fields("key", "storage", "type", "decision", "content", "x"))
            .post(API + "/" + SCOPE + "/decision")));
        answers.add(answerOf("create onto the reserved selector", call()
            .body(fields("key", "system.x", "type", "decision", "content", "x"))
            .post(API + "/" + SCOPE + "/system")));
        answers.add(answerOf("create with an unknown type", call()
            .body(fields("key", "decision.other", "type", "musing", "content", "x"))
            .post(API + "/" + SCOPE + "/decision")));

        answers.add(answerOf("update with no token", call()
            .body(fields("content", "x")).patch(ITEM)));
        answers.add(answerOf("update with a stale token", call()
            .header("If-Match", "1999-01-01T00:00:00Z")
            .body(fields("content", "x")).patch(ITEM)));
        answers.add(answerOf("update with an immutable field", call()
            .header("If-Match", token).body(fields("key", "decision.other")).patch(ITEM)));

        answers.add(answerOf("read of an absent address", call()
            .get(API + "/" + SCOPE + "/decision/nothing-here")));
        answers.add(answerOf("read of an absent technical address", call()
            .get(API + "/" + UUID.randomUUID())));
        answers.add(answerOf("query with an unknown predicate", call()
            .get(API + "/" + SCOPE + "?author=x")));
        answers.add(answerOf("a verb at the wrong depth", call()
            .post(API + "/" + SCOPE + "/decision:digest")));
        answers.add(answerOf("a scope of another tenant", call()
            .get(API + "/" + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG)));

        answers.add(answerOf("withdraw", call().header("If-Match", token)
            .post(ITEM + ":withdraw")));

        return List.copyOf(answers);
    }

    private static Answer answerOf(String what, Response response) {
        return new Answer(what, response.statusCode(), response.asString());
    }

    /** One answer, kept with the call that produced it so a failure names it. */
    private record Answer(String call, int status, String body) {

        String describe() {
            return call + " answered " + status + ": " + body;
        }
    }
}
