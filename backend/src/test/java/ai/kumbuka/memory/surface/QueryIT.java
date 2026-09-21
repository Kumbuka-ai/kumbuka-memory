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

import java.util.ArrayList;
import java.util.List;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7 — the selector is optional, and nothing is cut without saying so.
 *
 * <h2>Why "no silent truncation" needs more than one assertion</h2>
 *
 * A listing that carried only its entries would be indistinguishable from a
 * complete answer, and a caller cannot ask a question it does not know it has.
 * So the probe reads every member that makes the page legible — the page size
 * actually served, the total the predicate matches, whether more follows, the
 * cursor that fetches it and the order the entries are in — and then pages
 * through to the end and counts what it received. The count is the assertion
 * that matters: the members could all be present and wrong.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class QueryIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void twoSelectorsInOneScope() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "decision", "decision", 12);
        SurfaceFixture.plantMany(SubstrateDatabaseResource.SCOPE_ID,
            "convention", "convention", 7);
    }

    @Test
    void a_query_without_a_selector_reads_the_whole_scope() {
        Response all = call().get(API + "/" + SCOPE + "?page_size=50");

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.jsonPath().getInt("total"))
            .as("A7: the selector is optional, and its absence is not a wildcard bolted "
                + "on — a caller that does not yet know the estate's selectors needs the "
                + "whole scope in order to find out what they are")
            .isEqualTo(19);
        assertThat(all.jsonPath().getList("entries.fields.key", String.class))
            .contains("decision.item-1", "convention.item-1");
    }

    @Test
    void a_query_with_a_selector_reads_that_selector_alone() {
        Response conventions = call().get(API + "/" + SCOPE + "/convention?page_size=50");

        assertThat(conventions.jsonPath().getInt("total")).isEqualTo(7);
        assertThat(conventions.jsonPath().getList("entries.fields.key", String.class))
            .as("and the selector is matched as the key prefix up to the first dot, so a "
                + "longer selector beginning with the same letters is not swept in")
            .allSatisfy(key -> assertThat(key).startsWith("convention."));
    }

    @Test
    void a_page_says_how_large_it_is_what_it_is_part_of_and_in_what_order() {
        Response page = call().get(API + "/" + SCOPE + "?page_size=5");

        assertThat(page.jsonPath().getList("entries", java.util.Map.class)).hasSize(5);
        assertThat(page.jsonPath().getInt("page_size"))
            .as("the size actually served, so that a caller can see it was not reduced")
            .isEqualTo(5);
        assertThat(page.jsonPath().getInt("total"))
            .as("and the whole set the predicate matches, which does not shrink as the "
                + "caller pages")
            .isEqualTo(19);
        assertThat(page.jsonPath().getBoolean("truncated")).isTrue();
        assertThat(page.jsonPath().getString("cursor")).isNotBlank();
        assertThat(page.jsonPath().getString("order"))
            .as("named rather than merely deterministic: a caller paging with a cursor is "
                + "relying on an order, and one it has to infer from two pages is one it "
                + "will infer wrongly the first time a page is homogeneous")
            .isEqualTo("key ascending");
        assertThat(page.jsonPath().getInt("unaddressable"))
            .as("and how many entries of the scope this surface cannot show at all — "
                + "zero here, because create refuses a key with no selector")
            .isZero();
    }

    @Test
    void paging_with_the_cursor_reaches_every_entry_exactly_once() {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            Response page = call().get(API + "/" + SCOPE + "?page_size=4"
                + (cursor == null ? "" : "&after=" + cursor));
            seen.addAll(page.jsonPath().getList("entries.fields.key", String.class));
            cursor = page.jsonPath().getString("cursor");
            pages++;
            assertThat(pages).as("paging must terminate").isLessThan(20);
        } while (cursor != null);

        assertThat(seen)
            .as("everything, once. A cursor that repeated or skipped an entry would make "
                + "a complete read impossible, which is the failure a page size alone "
                + "cannot reveal")
            .hasSize(19)
            .doesNotHaveDuplicates()
            .isSorted();
    }

    @Test
    void a_page_size_beyond_the_limit_is_refused_and_not_quietly_reduced() {
        Response answer = call().get(API + "/" + SCOPE + "?page_size=5000");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("PAGE_SIZE_REJECTED");
        assertThat(answer.jsonPath().getString("message"))
            .as("a page silently made smaller than the one asked for is how a caller "
                + "comes to believe it has seen everything")
            .contains("refused rather than reduced");
    }

    @Test
    void a_predicate_this_verb_does_not_carry_is_refused_and_named() {
        Response answer = call().get(API + "/" + SCOPE + "?author=somebody");

        assertThat(answer.statusCode()).isEqualTo(422);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("PREDICATE_UNKNOWN");
        assertThat(answer.jsonPath().getList("data.offenders", String.class))
            .as("a filter that is silently dropped answers with the whole set and looks "
                + "exactly like a correct narrow one")
            .containsExactly("author");
    }

    @Test
    void the_type_and_the_text_predicates_narrow_what_they_say_they_narrow() {
        assertThat(call().get(API + "/" + SCOPE + "?type=convention&page_size=50")
                .jsonPath().getInt("total"))
            .isEqualTo(7);

        Response text = call()
            .queryParam("text", "NUMBER 1")
            .queryParam("page_size", 50)
            .get(API + "/" + SCOPE);
        assertThat(text.jsonPath().getList("entries.fields.key", String.class))
            .as("the text predicate is a substring of the content and ignores case")
            .contains("decision.item-1", "convention.item-1")
            .doesNotContain("decision.item-2");
    }

    @Test
    void a_cursor_this_verb_did_not_hand_out_is_refused() {
        Response answer = call().get(API + "/" + SCOPE + "?after=not-a-cursor!!");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("CURSOR_MALFORMED");
    }

    @Test
    void a_query_in_a_scope_the_caller_may_not_enter_is_the_single_not_found() {
        Response answer = call().get(API + "/"
            + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG);

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
    }
}
