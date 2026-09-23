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

import java.util.List;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which selection a digest runs on, when more than one could apply.
 *
 * <h2>The three candidates</h2>
 *
 * After V5 a digest of a scope has up to three selections to choose from, and
 * takes the first that exists:
 *
 * <ol>
 *   <li>this tenant's row for this scope;</li>
 *   <li>this tenant's row for no scope in particular — what V4 seeds;</li>
 *   <li>the platform's row, under no tenant and no scope — what V5 seeds.</li>
 * </ol>
 *
 * <p>The order is written into the statement rather than left to the rows.
 * Without it the answer would be whichever row the executor returned first:
 * stable in a suite, stable until a plan change moved it, and then an estate
 * that set its own selection would silently be served the platform's.
 *
 * <h2>Why every case here plants its own rows</h2>
 *
 * The seeded state cannot distinguish the cases: V5's row carries V4's
 * selection value for value, deliberately, so a digest served by either
 * answers the same bytes. A probe against the seeded state would pass whether
 * the precedence held or not. So each case makes the rows differ, and what is
 * asserted is which of the differing selections came back.
 *
 * <p>The state {@code @BeforeEach} restores is the state V4 and V5 leave
 * behind. That it IS that state is asserted elsewhere and not here:
 * {@code PlatformDefaultRowIT} reads the platform row as the migration wrote
 * it, and {@code DigestIT} reads the tenant's seeded selection through the
 * surface without planting anything at all.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class DigestPreferenceIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String TABLE = "memory.digest_preference";
    private static final String NULL_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String TENANT = SubstrateDatabaseResource.TENANT_ID;

    /** The selection V4 and V5 both seed. */
    private static final List<String> SEEDED_SELECTION = List.of(
        "constraint", "decision", "convention", "glossary", "status");

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    /** The table as the two migrations leave it, restored before every case. */
    @BeforeEach
    void theSeededSelections() {
        SurfaceFixture.run("DELETE FROM " + TABLE);
        plant(TENANT, NULL_UUID, SEEDED_SELECTION, false);
        plant(NULL_UUID, NULL_UUID, SEEDED_SELECTION, false);
    }

    // ==================================================================
    // The precedence
    // ==================================================================

    @Test
    void a_tenant_with_a_selection_of_its_own_is_served_that_one() {
        SurfaceFixture.run("UPDATE " + TABLE + " SET types = ARRAY['decision']::TEXT[]"
            + " WHERE tenant_id = '" + TENANT + "'");

        assertThat(selectedTypes(digestOfTheScope()))
            .as("the estate's own selection stands before the platform's. Reversed, a "
                + "tenant that deliberately chose what its digest carries would be "
                + "served the platform's choice instead — and nothing in the answer "
                + "would say which one it ran on")
            .containsExactly("decision");
    }

    @Test
    void a_selection_for_the_scope_stands_before_the_tenants_own() {
        SurfaceFixture.run("UPDATE " + TABLE + " SET types = ARRAY['decision']::TEXT[]"
            + " WHERE tenant_id = '" + TENANT + "'");
        plant(TENANT, SubstrateDatabaseResource.SCOPE_ID, List.of("glossary"), true);

        Response digest = digestOfTheScope();

        assertThat(selectedTypes(digest))
            .as("most specific first: a row for this very scope, then the tenant's "
                + "default, then the platform's")
            .containsExactly("glossary");
        assertThat(digest.jsonPath().getList("scopes", String.class))
            .as("and the whole row is taken, not only its types — include_global comes "
                + "from the same row and reaches the global scope here")
            .contains(SCOPE, SubstrateDatabaseResource.GLOBAL_SCOPE_SLUG);
    }

    @Test
    void a_tenant_with_no_row_of_its_own_is_served_the_platform_selection() {
        SurfaceFixture.run("DELETE FROM " + TABLE + " WHERE tenant_id = '" + TENANT + "'");

        // The platform row is given a selection of its own first. Left at the
        // seeded value it is byte for byte what V4 writes, so an answer
        // carrying it would prove only that SOME row was found — including a
        // tenant row this case believes it deleted.
        SurfaceFixture.run("UPDATE " + TABLE + " SET types = ARRAY['status']::TEXT[]"
            + " WHERE tenant_id = '" + NULL_UUID + "'");

        Response digest = digestOfTheScope();

        assertThat(digest.statusCode())
            .as("this is the defect V5 closes: before it, a tenant with no row of its "
                + "own was answered 500 'no digest selection is stored' — and after the "
                + "production window of sprint 189 that is a session that starts with "
                + "no memory at all")
            .isEqualTo(200);
        assertThat(selectedTypes(digest))
            .as("and what came back is the platform row specifically, not merely a row")
            .containsExactly("status");
    }

    // ==================================================================
    // What is left when there is nothing to find
    // ==================================================================

    /**
     * The refusal is unchanged where no selection exists at all.
     *
     * <p>V5 adds a candidate; it does not turn the missing-selection refusal
     * into a Java constant by the back door. A database with neither seed is
     * a broken database and still says so.
     */
    @Test
    void a_digest_with_no_selection_anywhere_is_still_refused() {
        SurfaceFixture.run("DELETE FROM " + TABLE);

        assertThat(digestOfTheScope().statusCode()).isEqualTo(500);
    }

    /**
     * MEASUREMENT (dispatch 188.9, Pflicht 2): does a digest that names its
     * own types avoid the stored selection?
     *
     * <p>It does not, and this records that rather than asserting it as a
     * contract. The stored selection is read before the override is
     * considered, because {@code include_global} comes from the same row and
     * an override names types only — so a call that named its types was
     * refused for exactly the same reason as one that did not. Every caller
     * of a tenant without a row was affected, whether or not it knew which
     * types it wanted.
     *
     * <p>After V5 the question is moot for a real estate: the platform row is
     * always there, so both forms are answered. The behaviour is left as it
     * is, which is what the dispatch asked for.
     */
    @Test
    void an_override_does_not_avoid_the_lookup_of_the_stored_selection() {
        SurfaceFixture.run("DELETE FROM " + TABLE);

        Response withOwnTypes = call().body("{\"types\":[\"decision\"]}")
            .post(API + "/" + SCOPE + ":digest");

        assertThat(withOwnTypes.statusCode())
            .as("measured, not decided: the selection is read before the override is "
                + "looked at, so naming the types is no way around a missing row")
            .isEqualTo(500);
    }

    // ------------------------------------------------------------------

    private static Response digestOfTheScope() {
        return call().post(API + "/" + SCOPE + ":digest");
    }

    private static List<String> selectedTypes(Response digest) {
        assertThat(digest.statusCode())
            .as("a digest that did not answer has no selection to compare")
            .isEqualTo(200);
        return digest.jsonPath().getList("selected_types", String.class);
    }

    /** One selection row, written as the superuser — the table has no write surface. */
    private static void plant(String tenantId, String scopeId, List<String> types,
                              boolean includeGlobal) {
        String array = types.stream()
            .map(type -> "'" + type + "'")
            .reduce((a, b) -> a + "," + b)
            .orElseThrow();
        SurfaceFixture.run("INSERT INTO " + TABLE
            + " (tenant_id, scope_id, types, include_global) VALUES ('"
            + tenantId + "', '" + scopeId + "', ARRAY[" + array + "]::TEXT[], "
            + includeGlobal + ")");
    }
}
