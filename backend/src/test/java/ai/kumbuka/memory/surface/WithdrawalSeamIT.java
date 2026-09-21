package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A9 — the second half: the withdrawal is an extension point, and swapping it
 * changes what happens to the row and nothing else.
 *
 * <p>The community edition's own behaviour — a hard delete — is asserted in
 * {@link EntrySurfaceIT}. This suite asserts the property that makes it an
 * extension point rather than an implementation detail: another implementation
 * takes effect without a line of {@code kumbuka-memory} changing, and the verb
 * around it is the same verb.
 *
 * <p>The alternative is enabled through a test profile, which is the mechanism
 * a module outside this repository would use for the same purpose. If the seam
 * were not a seam — if the verb called the delete directly — the profile would
 * have nothing to switch and this suite would report a destroyed row.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(WithdrawalSeamIT.TombstoneEdition.class)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class WithdrawalSeamIT {

    /** The edition that keeps a record. Nothing else about the service changes. */
    public static class TombstoneEdition implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(TombstoneWithdrawal.class);
        }
    }

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "decision.storage-engine";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage-engine";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntry() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID, SubstrateDatabaseResource.SCOPE_ID,
            false, SubstrateDatabaseResource.PROBE_SUBJECT,
            "decision", KEY, "postgres", Instant.now()));
    }

    @Test
    void another_edition_supplies_the_act_and_the_verb_around_it_is_unchanged() {
        String token = call().get(ITEM).jsonPath().getString("conflict_token");

        Response withdrawn = call().header("If-Match", token).post(ITEM + ":withdraw");

        assertThat(withdrawn.statusCode())
            .as("the same verb, at the same address, with the same precondition")
            .isEqualTo(200);
        assertThat(withdrawn.jsonPath().getString("address"))
            .isEqualTo("memory://" + SCOPE + "/decision/storage-engine");
        assertThat(withdrawn.jsonPath().getString("outcome"))
            .as("A9: what happened to the row is the edition's answer, and it reaches the "
                + "caller as itself rather than as the community edition's")
            .isEqualTo("tombstoned");

        assertThat(rowsAtTheKey())
            .as("and the row is still in the table, which is the whole difference and the "
                + "one this service did not have to be edited to produce")
            .isOne();
    }

    @Test
    void the_next_steps_are_read_off_the_table_and_not_off_the_outcome() {
        String token = call().get(ITEM).jsonPath().getString("conflict_token");
        Response withdrawn = call().header("If-Match", token).post(ITEM + ":withdraw");

        boolean released = withdrawn.jsonPath().getBoolean("address_released");
        assertThat(withdrawn.jsonPath().getList("next.call", String.class))
            .as("every call an answer lists must be one that actually succeeds "
                + "(DEC-0040). So whether the address is free is read back after the act "
                + "rather than inferred from the word the edition returned — this "
                + "edition's tombstone leaves the key free, which an inference from "
                + "'tombstoned' would have got wrong")
            .containsExactly(released
                ? "create memory://" + SCOPE + "/decision"
                : "read memory://" + SCOPE + "/decision/storage-engine");

        Response afterwards = call()
            .body(SurfaceFixture.fields("key", KEY, "type", "decision", "content", "again"))
            .post(API + "/" + SCOPE + "/decision");

        assertThat(afterwards.statusCode())
            .as("and the listed call is then made, which is the only way to know the list "
                + "was not merely plausible")
            .isEqualTo(released ? 201 : 409);
    }

    private static long rowsAtTheKey() {
        return SurfaceFixture.countRows(
            "SELECT count(*) FROM memory.memory WHERE key = '" + KEY + "'");
    }
}
