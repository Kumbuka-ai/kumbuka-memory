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
import java.util.Map;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A6 — the digest does not cut, reports all six types, and comes out in the
 * order the contract fixes.
 *
 * <h2>The number twenty-five is not arbitrary</h2>
 *
 * The core this replaces caps {@code load_context} at twenty entries per type.
 * An estate that passed its twenty-first decision silently stopped seeing its
 * oldest ones, and nothing in the answer said so. So the population here is
 * twenty-five per type: enough that a cap at twenty would be visible, and the
 * assertion is on the count rather than on the absence of a cap, because an
 * absence is not observable and a count is.
 *
 * <p>The expectations come from the dispatch's contract text — the fixed type
 * order, oldest first inside a type, a summary for all six, the default
 * selection without open questions and without the global scope — and not from
 * what this service answers or from what the core does today.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class DigestIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final int PER_TYPE = 25;

    /** The order the contract fixes, restated here and taken from it. */
    private static final List<String> DIGEST_ORDER = List.of(
        "constraint", "decision", "convention", "glossary", "status", "open_question");

    /** The estate's default: every type but the unsettled one. */
    private static final List<String> DEFAULT_SELECTION = List.of(
        "constraint", "decision", "convention", "glossary", "status");

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void aPopulatedScope() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "constraint", "constraint", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "decision", "decision", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "convention", "convention", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "glossary", "glossary", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "status", "status", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "openquestion", "open_question", PER_TYPE);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.GLOBAL_SCOPE_ID,
            "convention", "convention", 3);
    }

    @Test
    void every_entry_of_every_selected_type_is_carried_whole() {
        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.statusCode()).isEqualTo(200);
        assertThat(digest.jsonPath().getBoolean("truncated"))
            .as("a digest never cuts, and it says so rather than leaving a caller to "
                + "assume it")
            .isFalse();

        for (String type : DEFAULT_SELECTION) {
            assertThat(entriesOf(digest, type))
                .as("A6: %d entries of type '%s' were laid down and all of them are here. "
                    + "The core this replaces caps each type at twenty", PER_TYPE, type)
                .hasSize(PER_TYPE);
        }
    }

    @Test
    void the_summary_answers_for_all_six_types_and_says_which_are_carried() {
        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.jsonPath().getList("summary.type", String.class))
            .as("the summary covers every type this service knows, whatever the "
                + "selection — so a reader can see that there are %d open questions here "
                + "which this digest does not carry, and can ask for them", PER_TYPE)
            .isEqualTo(DIGEST_ORDER);

        assertThat(tallyOf(digest, "open_question").get("count"))
            .isEqualTo(PER_TYPE);
        assertThat(tallyOf(digest, "open_question").get("carried"))
            .as("counted in the summary and not carried in the content: a digest is what "
                + "is IN FORCE, and an open question is the opposite of that")
            .isEqualTo(false);

        assertThat(digest.jsonPath().getInt("total_entries"))
            .as("and the sum is over all six, not over the selected ones")
            .isEqualTo(6 * PER_TYPE);
        assertThat(digest.jsonPath().getInt("total_token_estimate"))
            .as("the estimate is answered so a caller can decide whether the digest fits "
                + "in what it is about to do with it")
            .isPositive();
    }

    @Test
    void the_sections_come_out_in_the_fixed_type_order_and_oldest_first_inside_one() {
        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.jsonPath().getList("sections.type", String.class))
            .as("constraints before decisions before conventions before the glossary "
                + "before the status: a reader meets the bounds before the choices made "
                + "inside them")
            .isEqualTo(DEFAULT_SELECTION);

        List<String> decisions = entriesOf(digest, "decision").stream()
            .map(entry -> String.valueOf(entry.get("key")))
            .toList();
        assertThat(decisions)
            .as("inside a type, by the moment the entry was laid down, oldest first — "
                + "not by change time descending, which is what the core does and which "
                + "makes a digest read as a changelog")
            .startsWith("decision.item-1", "decision.item-2", "decision.item-3")
            .endsWith("decision.item-" + PER_TYPE);
    }

    @Test
    void the_default_selection_leaves_out_open_questions_and_the_global_scope() {
        Response digest = call().post(API + "/" + SCOPE + ":digest");

        assertThat(digest.jsonPath().getList("selected_types", String.class))
            .as("the estate's selection is state in its own table, seeded by V4: every "
                + "type but the unsettled one")
            .isEqualTo(DEFAULT_SELECTION);
        assertThat(digest.jsonPath().getList("scopes", String.class))
            .as("and include_global is off by default, so the digest of a project scope "
                + "covers that scope alone")
            .containsExactly(SCOPE);
        assertThat(entriesOf(digest, "convention"))
            .as("the three conventions of the global scope are therefore not in it")
            .hasSize(PER_TYPE);
    }

    @Test
    void a_passed_type_selection_overrides_the_estates_one_for_that_answer() {
        Response digest = call().body("{\"types\":[\"open_question\"]}")
            .post(API + "/" + SCOPE + ":digest");

        assertThat(digest.statusCode()).isEqualTo(200);
        assertThat(digest.jsonPath().getList("sections.type", String.class))
            .containsExactly("open_question");
        assertThat(entriesOf(digest, "open_question")).hasSize(PER_TYPE);

        assertThat(call().post(API + "/" + SCOPE + ":digest")
                .jsonPath().getList("selected_types", String.class))
            .as("an override is for one answer. The estate's selection is unchanged, "
                + "because there is no write surface for it — the runtime role holds "
                + "SELECT on that table and nothing else")
            .isEqualTo(DEFAULT_SELECTION);
    }

    @Test
    void a_type_the_service_does_not_carry_is_refused_rather_than_dropped() {
        Response digest = call().body("{\"types\":[\"decision\",\"decisions\"]}")
            .post(API + "/" + SCOPE + ":digest");

        assertThat(digest.statusCode()).isEqualTo(422);
        assertThat(digest.jsonPath().getString("reason")).isEqualTo("TYPE_UNKNOWN");
        assertThat(digest.jsonPath().getList("data.offenders", String.class))
            .as("named, because a section missing from a digest reads as 'there are none "
                + "of these' and a misspelling would answer that")
            .containsExactly("decisions");
    }

    @Test
    void the_digest_of_a_scope_the_caller_may_not_enter_is_the_single_not_found() {
        Response digest = call().post(API + "/"
            + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG + ":digest");

        assertThat(digest.statusCode()).isEqualTo(404);
        assertThat(digest.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // Reading the answer.
    //
    // The sections and the summary are looked up by type in Java rather than
    // by a path expression. A GPath filter reads compactly and silently
    // answers something else when the shape moves; a lookup that finds nothing
    // fails here with the type it was looking for.
    // ------------------------------------------------------------------

    private static List<Map<String, Object>> entriesOf(Response digest, String type) {
        return sectionsOf(digest).stream()
            .filter(section -> type.equals(section.get("type")))
            .findFirst()
            .map(section -> castEntries(section.get("entries")))
            .orElseThrow(() -> new AssertionError(
                "the digest carries no section for '" + type + "'; it carries "
                    + sectionsOf(digest).stream().map(s -> s.get("type")).toList()));
    }

    private static Map<String, Object> tallyOf(Response digest, String type) {
        return digest.jsonPath().getList("summary", Map.class).stream()
            .map(DigestIT::castTally)
            .filter(tally -> type.equals(tally.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the summary says nothing about '" + type + "'"));
    }

    private static List<Map<String, Object>> sectionsOf(Response digest) {
        return digest.jsonPath().getList("sections", Map.class).stream()
            .map(DigestIT::castTally)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castTally(Object raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castEntries(Object raw) {
        return (List<Map<String, Object>>) raw;
    }
}
