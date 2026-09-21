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
import java.util.UUID;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2 and A3 — what a caller may not see is indistinguishable from what is not
 * there, on both address forms and down to the bytes.
 *
 * <h2>Why byte-identical and not merely "both 404"</h2>
 *
 * A code that matches while a message differs is an oracle just the same: a
 * caller comparing two answers learns which address named something real. So
 * the comparison here is over the whole response body, and the message that
 * makes it possible is a constant rather than a format — anything interpolated
 * into it, a slug or an address or a count, would reintroduce the difference
 * the single code exists to close.
 *
 * <h2>Why the technical address is probed separately every time</h2>
 *
 * It is the form where no scope is checked before the row is found. Every way
 * it can fail has to arrive at the same answer as the canonical form: a
 * foreign tenant's row is not found at all, because the policy and the ORM
 * filter both bind; another author's private row is filtered by the statement;
 * and a row in a scope this caller cannot enter is found and then refused. The
 * three take three different paths through the code and must be one answer on
 * the wire.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IsolationIT {

    private static final String FOREIGN_KEY = "decision.beta-storage";
    private static final String PRIVATE_KEY = "decision.my-own-note";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyTable() {
        SurfaceFixture.clearEntries();
    }

    // ==================================================================
    // A2 — another tenant
    // ==================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_foreign_tenants_entry_is_not_found_on_both_address_forms_byte_for_byte() {
        UUID foreign = SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.OTHER_TENANT_ID,
            SubstrateDatabaseResource.OTHER_TENANT_SCOPE_ID,
            false, SubstrateDatabaseResource.OTHER_TENANT_SUBJECT,
            "decision", FOREIGN_KEY, "the other estate's decision", Instant.now()));

        Response canonical = call().get(API + "/"
            + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG + "/decision/beta-storage");
        Response technical = call().get(API + "/" + foreign);
        Response absent = call().get(API + "/"
            + SubstrateDatabaseResource.PROBE_SCOPE_SLUG + "/decision/nothing-at-all");

        assertThat(canonical.statusCode()).isEqualTo(404);
        assertThat(technical.statusCode()).isEqualTo(404);

        assertThat(canonical.asString())
            .as("A2: an entry of another tenant answers exactly what an address naming "
                + "nothing answers. The scope is in another estate, the row is behind a "
                + "policy, and neither fact reaches the caller")
            .isEqualTo(absent.asString());
        assertThat(technical.asString())
            .as("and the technical address, where no scope is checked before the row is "
                + "looked for, arrives at the same bytes by a different route")
            .isEqualTo(absent.asString());
    }

    // ==================================================================
    // A3 — another author's private entry
    // ==================================================================

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_private_entry_is_readable_by_its_author() {
        plantPrivateEntryOfTheProbeSubject();

        Response mine = call().get(API + "/"
            + SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG + "/decision/my-own-note");

        assertThat(mine.statusCode())
            .as("the other half of A3, without which the refusal below would hold just as "
                + "well against a service that answers nothing to anybody")
            .isEqualTo(200);
        assertThat(mine.jsonPath().getBoolean("fields.private")).isTrue();
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.SECOND_SUBJECT)
    void another_authors_private_entry_is_not_found_on_both_address_forms_byte_for_byte() {
        UUID hers = plantPrivateEntryOfTheProbeSubject();

        Response canonical = call().get(API + "/"
            + SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG + "/decision/my-own-note");
        Response technical = call().get(API + "/" + hers);
        Response absent = call().get(API + "/"
            + SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG + "/decision/nothing-at-all");

        assertThat(canonical.statusCode()).isEqualTo(404);
        assertThat(technical.statusCode()).isEqualTo(404);

        assertThat(canonical.asString())
            .as("A3: the private scope is ONE container per tenant, so this caller sees "
                + "the scope and not the entry. The privacy sits on the row, on "
                + "owner_subject — which is why the same address means different data for "
                + "two callers, deliberately")
            .isEqualTo(absent.asString());
        assertThat(technical.asString()).isEqualTo(absent.asString());
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.SECOND_SUBJECT)
    void the_same_address_carries_each_callers_own_entry_in_a_private_scope() {
        plantPrivateEntryOfTheProbeSubject();
        SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID,
            SubstrateDatabaseResource.PRIVATE_SCOPE_ID,
            true, SubstrateDatabaseResource.SECOND_SUBJECT,
            "decision", PRIVATE_KEY, "the second subject's own note", Instant.now()));

        Response mine = call().get(API + "/"
            + SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG + "/decision/my-own-note");

        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.jsonPath().getString("fields.content"))
            .as("two authors' identical private keys coexist — uq_memory_private_key is "
                + "per scope AND author — and each caller reads their own at the one "
                + "address. That is the contract, not a collision")
            .isEqualTo("the second subject's own note");
    }

    private static UUID plantPrivateEntryOfTheProbeSubject() {
        return SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID,
            SubstrateDatabaseResource.PRIVATE_SCOPE_ID,
            true, SubstrateDatabaseResource.PROBE_SUBJECT,
            "decision", PRIVATE_KEY, "the probe subject's own note", Instant.now()));
    }
}
