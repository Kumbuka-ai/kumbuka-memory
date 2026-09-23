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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant that is not the one this database was migrated for.
 *
 * <h2>The case, as it arises</h2>
 *
 * The enterprise composition serves many tenants from one database. V4 seeds
 * a digest selection for the tenant bound when the migration ran, and every
 * tenant admitted afterwards has none — so {@code digest} answered it
 * {@code 500 no digest selection is stored}. Measured while building
 * {@code Kumbuka-ai/ee-memory} #1, and this is the probe for the fix.
 *
 * <h2>Why the run under test is a second Quarkus, and why it deletes a row</h2>
 *
 * The acting tenant is a property of the installation
 * ({@code memory.tenant-id}), not of a call, so a second tenant cannot be
 * reached by addressing one — it needs a service configured for it. Hence the
 * profile.
 *
 * <p>That configuration also runs the migrations, so V4 seeds a row for THIS
 * tenant here, which a deployment's second tenant never had. The row is
 * removed in setup to reach the state the defect is about: a tenant with no
 * selection of its own. Deleting it is how the case is staged, not what is
 * asserted — what is asserted is the answer that follows.
 *
 * <p>{@code DigestPreferenceIT} covers the same fall-through under the
 * ordinary tenant and much more cheaply. This class is what makes the claim
 * about a SECOND tenant rather than about a tenant whose row was removed: a
 * different tenant id, a different scope, a different subject, and a service
 * that resolves all three for itself.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(SecondTenantDigestIT.SecondTenant.class)
@TestSecurity(user = SubstrateDatabaseResource.OTHER_TENANT_SUBJECT)
class SecondTenantDigestIT {

    private static final String SCOPE = SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG;

    /** The selection V5's platform row carries, which is V4's. */
    private static final List<String> PLATFORM_SELECTION = List.of(
        "constraint", "decision", "convention", "glossary", "status");

    /**
     * A service configured for the estate the substrate stages as the
     * neighbour.
     *
     * <h2>Why the port is left to the operating system</h2>
     *
     * A profile makes this class a SECOND Quarkus in one JVM, and the two
     * changeovers are the whole cost of it. Measured 2026-09-22: on the fixed
     * test port the start failed with
     * {@code QuarkusBindException: Port already bound: 8081} — and the damage
     * was not confined to this class. The failed start left the assembled
     * configuration pointing at the container THIS class had migrated, under
     * THIS tenant, so two later classes read a database seeded for an estate
     * they knew nothing about: {@code PlatformDefaultRowIT} saw only the
     * platform row where it expected the tenant's as well, and
     * {@code WithdrawalSeamIT} could not resolve its scope at all. Both are
     * green on their own, before and after.
     *
     * <p>Port 0 asks the operating system for a free one, which cannot
     * collide with a listener that has not finished going away. It is the
     * cheaper half of the fix; the other half is that a probe which can take
     * the rest of a suite down with it is worth exactly one setting to
     * prevent.
     */
    public static class SecondTenant implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "memory.tenant-id", SubstrateDatabaseResource.OTHER_TENANT_ID,
                "quarkus.http.test-port", "0");
        }
    }

    @BeforeAll
    static void aTenantThatWasNeverSeeded() {
        PlatformFixture.grantDirectoryAccess();

        // The state a deployment's later tenant is in from the start. Here it
        // has to be produced, because the migration that seeded V4's row ran
        // under this very tenant — an artefact of configuring one service for
        // one estate, not of the estate.
        SurfaceFixture.run("DELETE FROM memory.digest_preference WHERE tenant_id = '"
            + SubstrateDatabaseResource.OTHER_TENANT_ID + "'");
    }

    @Test
    void a_second_tenant_is_served_the_platform_selection_rather_than_a_refusal() {
        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.statusCode())
            .as("before V5 this was 500 'no digest selection is stored'. After the "
                + "production window of sprint 189 an assistant loads its memory through "
                + "this verb, so that refusal is a session that starts with no memory")
            .isEqualTo(200);
        assertThat(digest.jsonPath().getList("selected_types", String.class))
            .as("and what it is served is the platform's row, which carries V4's "
                + "selection value for value")
            .isEqualTo(PLATFORM_SELECTION);
        assertThat(digest.jsonPath().getList("scopes", String.class))
            .as("include_global is off in the platform row, so the digest of a project "
                + "scope covers that scope alone")
            .containsExactly(SCOPE);
    }

    /**
     * The platform row did not bring another estate's entries with it.
     *
     * <p>The row is shared; nothing else is. A selection visible across
     * tenants would be worth very little if it also made the entries
     * selected by it visible, so the digest this tenant gets is asserted to
     * be empty rather than merely to exist — tenant A's scope carries no
     * entries in this run either, which is why the count and not the absence
     * is what is read.
     */
    @Test
    void the_shared_selection_does_not_make_another_estates_entries_visible() {
        SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.TENANT_ID, SubstrateDatabaseResource.SCOPE_ID,
            false, SubstrateDatabaseResource.PROBE_SUBJECT,
            "decision", "decision.not-yours", "an entry of the other estate",
            java.time.Instant.now()));

        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.statusCode()).isEqualTo(200);
        assertThat(digest.jsonPath().getLong("total_entries"))
            .as("the selection is shared and the estate is not: V4's policy still binds "
                + "memory.memory, and V5 touched neither it nor that table")
            .isZero();
    }
}
