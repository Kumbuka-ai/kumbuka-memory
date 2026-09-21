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

import java.time.Instant;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A10 — the two ways a write is refused, and the reading that survives both.
 *
 * <h2>Why two codes and not one</h2>
 *
 * A lock is the scope's own state: it is lifted where it was set, and the same
 * caller with the same token gets through once it is. A missing write right is
 * about this caller, and no state change lifts it — the remedy is the
 * membership, which somebody else administers. A caller told the wrong one of
 * those goes to the wrong person, which is worse than being told nothing.
 *
 * <p>The codes are the ones 187.4 and 187.5 declare in the worklist and the
 * dispatch services. If either of those moved to an existing code after its
 * own measurement, this service follows — which is a thing to be done when it
 * is known, and not guessed at here.
 *
 * <h2>Why the read is asserted every time</h2>
 *
 * Without it every refusal below would hold just as well against a service
 * that answers nothing to anybody in those scopes. The claim is that the
 * write is refused AND the scope stays legible, and only the second half
 * distinguishes a right from an absence.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeRightsIT {

    private static final String OPEN = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String LOCKED = SubstrateDatabaseResource.LOCKED_SCOPE_SLUG;
    private static final String KEY = "decision.storage-engine";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEntryInEachScope() {
        SurfaceFixture.clearEntries();
        plant(SubstrateDatabaseResource.SCOPE_ID);
        plant(SubstrateDatabaseResource.LOCKED_SCOPE_ID);
    }

    // ==================================================================
    // The write right
    // ==================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void a_caller_without_the_write_right_reads_the_scope_and_writes_nothing_in_it() {
        Response read = call().get(API + "/" + OPEN + "/decision/storage-engine");
        assertThat(read.statusCode())
            .as("the write right is not about seeing the scope: the directory answered "
                + "for it, so this caller already has it")
            .isEqualTo(200);
        assertThat(read.jsonPath().getList("next.call", String.class))
            .as("and the answer offers only what this caller may actually do — a listed "
                + "call that is then refused is exactly what DEC-0040 forbids")
            .containsExactly("read memory://" + OPEN + "/decision/storage-engine");

        String token = read.jsonPath().getString("conflict_token");

        assertRefused(call().body(fields("key", "decision.another", "type", "decision",
                "content", "x")).post(API + "/" + OPEN + "/decision"),
            403, "SCOPE_READ_ONLY");
        assertRefused(call().header("If-Match", token).body(fields("content", "x"))
                .patch(API + "/" + OPEN + "/decision/storage-engine"),
            403, "SCOPE_READ_ONLY");
        assertRefused(call().header("If-Match", token)
                .post(API + "/" + OPEN + "/decision/storage-engine:withdraw"),
            403, "SCOPE_READ_ONLY");
    }

    // ==================================================================
    // The lock
    // ==================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_locked_scope_reads_and_refuses_every_write_as_locked() {
        Response read = call().get(API + "/" + LOCKED + "/decision/storage-engine");
        assertThat(read.statusCode())
            .as("a lock freezes writing and leaves reading alone")
            .isEqualTo(200);
        assertThat(read.jsonPath().getList("next.call", String.class))
            .containsExactly("read memory://" + LOCKED + "/decision/storage-engine");

        String token = read.jsonPath().getString("conflict_token");

        assertRefused(call().body(fields("key", "decision.another", "type", "decision",
                "content", "x")).post(API + "/" + LOCKED + "/decision"),
            409, "SCOPE_LOCKED");
        assertRefused(call().header("If-Match", token).body(fields("content", "x"))
                .patch(API + "/" + LOCKED + "/decision/storage-engine"),
            409, "SCOPE_LOCKED");
        assertRefused(call().header("If-Match", token)
                .post(API + "/" + LOCKED + "/decision/storage-engine:withdraw"),
            409, "SCOPE_LOCKED");
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void the_lock_is_answered_before_the_write_right_so_both_codes_are_reachable() {
        Response locked = call().body(fields("key", "decision.another", "type", "decision",
                "content", "x")).post(API + "/" + LOCKED + "/decision");

        assertThat(locked.jsonPath().getString("reason"))
            .as("the view derives can_write as NOT locked AND …, so a locked scope always "
                + "arrives with the write right already false. Checking the write right "
                + "first would answer every locked scope with SCOPE_READ_ONLY and leave "
                + "SCOPE_LOCKED a code that exists and can never be produced")
            .isEqualTo("SCOPE_LOCKED");
        assertThat(locked.jsonPath().getString("message"))
            .as("and the message sends the caller to where the lock is lifted, not to the "
                + "membership")
            .contains("lifted where it was set");
    }

    // ------------------------------------------------------------------

    private static void assertRefused(Response answer, int status, String reason) {
        assertThat(answer.statusCode()).isEqualTo(status);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo(reason);
        assertThat(answer.jsonPath().getString("message"))
            .as("every refusal says what was wrong and what to do instead")
            .isNotBlank();
    }

    private static void plant(String scopeId) {
        SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID, scopeId, false,
            SubstrateDatabaseResource.PROBE_SUBJECT,
            "decision", KEY, "postgres", Instant.now()));
    }
}
