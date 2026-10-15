/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7 and the paging frame — the selector is optional, nothing is cut without
 * saying so, and a cursor continues the one listing it came from.
 *
 * <h2>Why "no silent truncation" needs more than one assertion</h2>
 *
 * A listing that carried only its entries would be indistinguishable from a
 * complete answer, and a caller cannot ask a question it does not know it has.
 * So the probe reads the member that says more follows — {@code next_cursor},
 * present while there is more and absent on the last page — and then pages
 * through to the end and counts what it received. The count is the assertion
 * that matters: the member could be present and wrong.
 *
 * <h2>The red runs these probes were built against, each observed once on
 * 2026-10-11 with the fix removed</h2>
 *
 * <ul>
 *   <li>keyset comparison removed from {@code MemoryRepository.page}: the walks
 *       repeat their first page and fail "paging must terminate";</li>
 *   <li>key removed as second key of the {@code created_at} keyset: the tie walk
 *       skips the entries that share the cursor's moment;</li>
 *   <li>keyset replaced by an offset carried in the cursor: the walk with an
 *       insertion between two pages sees one entry twice;</li>
 *   <li>binding check removed from {@code MemoryService.query}: the four
 *       foreign-cursor probes answer 200;</li>
 *   <li>text unescaped in its {@code LIKE}: {@code 5%} matches {@code 50}; and
 *       with only the escape character left undoubled, {@code really!} no longer
 *       finds {@code really! and then more}.
 *       The selector's own narrowing is probed in {@code SelectorNarrowingIT}.</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class QueryIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEYS = "objects.fields.key";

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

    // ======================================================================
    // The selector
    // ======================================================================

    @Test
    void a_query_without_a_selector_reads_the_whole_scope() {
        Response all = call().get(API + "/" + SCOPE + "?page_size=50");

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.jsonPath().getList(KEYS, String.class))
            .as("A7: the selector is optional, and its absence is not a wildcard bolted "
                + "on — a caller that does not yet know the estate's selectors needs the "
                + "whole scope in order to find out what they are")
            .hasSize(19)
            .contains("decision.item-1", "convention.item-1");
    }

    @Test
    void a_query_with_a_selector_reads_that_selector_alone() {
        Response conventions = call().get(API + "/" + SCOPE + "/convention?page_size=50");

        assertThat(conventions.jsonPath().getList(KEYS, String.class))
            .as("and the selector is the key's part before the first dot, so a longer "
                + "selector beginning with the same letters is not swept in")
            .hasSize(7)
            .allSatisfy(key -> assertThat(key).startsWith("convention."));
    }

    @Test
    void a_selector_no_key_can_carry_is_refused_before_anything_is_read() {
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "a-b.one", "decision", "under a hyphen"));

        Response underscored = call().get(API + "/" + SCOPE + "/a_b?page_size=50");

        assertThat(underscored.statusCode())
            .as("the selector grammar admits '_', the key grammar does not, and the verb "
                + "checks a selector by the key it would begin: a_b is refused here, so the "
                + "statement's own narrowing is probed in SelectorNarrowingIT")
            .isEqualTo(400);
        assertThat(underscored.jsonPath().getString("reason")).isEqualTo("KEY_MALFORMED");
        assertThat(call().get(API + "/" + SCOPE + "/a-b?page_size=50")
                .jsonPath().getList(KEYS, String.class))
            .containsExactly("a-b.one");
    }

    // ======================================================================
    // The page
    // ======================================================================

    @Test
    void a_page_says_how_large_it_is_and_whether_more_follows() {
        Response page = call().get(API + "/" + SCOPE + "?page_size=5");

        assertThat(page.jsonPath().getList("objects", Map.class)).hasSize(5);
        assertThat(page.jsonPath().getString("next_cursor"))
            .as("more follows, so the one continuation member is there")
            .isNotBlank();
        assertThat(page.jsonPath().getMap("$"))
            .as("next_cursor is the only continuation: no flag, no total, no order beside "
                + "it, and the request is not repeated (ADR-0049)")
            .doesNotContainKeys("truncated", "total", "cursor", "order", "page_size",
                "entries");
        assertThat(page.jsonPath().getInt("unaddressable"))
            .as("and how many entries of the scope this surface cannot show at all — "
                + "zero here, because create refuses a key with no selector")
            .isZero();
    }

    @Test
    void the_last_page_carries_no_next_cursor_at_all() {
        Response page = call().get(API + "/" + SCOPE + "/convention?page_size=7");

        assertThat(page.jsonPath().getList(KEYS, String.class)).hasSize(7);
        assertThat(page.jsonPath().getMap("$"))
            .as("absent, not null: the last page is the one without the member")
            .doesNotContainKey("next_cursor");
    }

    // ======================================================================
    // The walk, in every declared order
    // ======================================================================

    @Test
    void paging_by_key_reaches_every_entry_exactly_once_in_key_order() {
        List<String> seen = walk("?page_size=4", page -> { });

        assertThat(seen)
            .as("everything, once. A cursor that repeated or skipped an entry would make "
                + "a complete read impossible, which is the failure a page size alone "
                + "cannot reveal")
            .hasSize(19)
            .doesNotHaveDuplicates()
            .isSorted();
    }

    @Test
    void paging_by_key_descending_reaches_every_entry_once_in_reverse_key_order() {
        List<String> seen = walk("?page_size=4&sort=id&direction=desc", page -> { });

        assertThat(seen).hasSize(19).doesNotHaveDuplicates()
            .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void paging_by_creation_reaches_every_entry_once_in_the_order_it_was_laid_down() {
        List<String> ascending = walk("/decision?page_size=5&sort=created_at", page -> { });
        List<String> descending =
            walk("/decision?page_size=5&sort=created_at&direction=desc", page -> { });

        // plantMany lays the entries down one second apart, item-1 first, so
        // the creation order is the numeric order of the suffix — which is not
        // the key order: item-10 sorts before item-2 by key.
        List<String> laidDown = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            laidDown.add("decision.item-" + n);
        }
        assertThat(ascending).containsExactlyElementsOf(laidDown);
        assertThat(descending).containsExactlyElementsOf(laidDown.reversed());
    }

    @Test
    void entries_that_share_their_creation_moment_are_ordered_and_continued_by_key() {
        SurfaceFixture.clearEntries();
        Instant same = Instant.parse("2026-03-01T12:00:00.123456Z");
        for (String id : List.of("e", "b", "d", "a", "c")) {
            SurfaceFixture.plant(new SurfaceFixture.Planted(SubstrateDatabaseResource.TENANT_ID,
                SubstrateDatabaseResource.SCOPE_ID, false, SubstrateDatabaseResource.PROBE_SUBJECT,
                "decision", "tie." + id, "tied", same));
        }

        List<String> seen = walk("?page_size=2&sort=created_at", page -> { });

        assertThat(seen)
            .as("five entries with one moment: the key breaks the tie, so the order is total "
                + "and the page boundary falls between two of them without losing one")
            .containsExactly("tie.a", "tie.b", "tie.c", "tie.d", "tie.e");
    }

    @Test
    void an_entry_laid_down_during_a_walk_moves_no_other_entry_between_pages() {
        List<String> seen = walk("?page_size=4", new Consumer<>() {
            private boolean inserted;

            @Override
            public void accept(Response page) {
                if (!inserted) {
                    inserted = true;
                    // Sorts before everything the first page carried, so an
                    // offset would now point one entry earlier than before.
                    SurfaceFixture.plant(SurfaceFixture.Planted.shared(
                        SubstrateDatabaseResource.SCOPE_ID, "aaa.early", "decision", "late"));
                }
            }
        });

        assertThat(seen)
            .as("keyset, not offset: the new entry stands before the cursor and is not "
                + "seen, and none of the nineteen is seen twice or missed")
            .hasSize(19)
            .doesNotHaveDuplicates()
            .doesNotContain("aaa.early");
    }

    // ======================================================================
    // The cursor belongs to its query
    // ======================================================================

    @Test
    void a_cursor_from_another_selector_is_refused_as_foreign() {
        String cursor = firstCursor("/decision?page_size=2");

        assertForeign(call().get(API + "/" + SCOPE + "/convention?after=" + cursor));
    }

    @Test
    void a_cursor_from_another_scope_is_refused_as_foreign() {
        String cursor = firstCursor("/decision?page_size=2");

        assertForeign(call().get(API + "/" + SubstrateDatabaseResource.GLOBAL_SCOPE_SLUG
            + "/decision?after=" + cursor));
    }

    @Test
    void a_cursor_from_another_filter_is_refused_as_foreign() {
        String cursor = firstCursor("/decision?page_size=2");

        assertForeign(call().get(API + "/" + SCOPE + "/decision?type=decision&after=" + cursor));
        assertForeign(call().get(API + "/" + SCOPE + "/decision?text=entry&after=" + cursor));
    }

    @Test
    void a_cursor_from_another_order_or_direction_is_refused_as_foreign() {
        String cursor = firstCursor("/decision?page_size=2");

        assertForeign(call().get(API + "/" + SCOPE
            + "/decision?sort=created_at&after=" + cursor));
        assertForeign(call().get(API + "/" + SCOPE
            + "/decision?direction=desc&after=" + cursor));
    }

    @Test
    void a_cursor_continues_its_own_query_in_another_page_size() {
        String cursor = firstCursor("/decision?page_size=2&type=decision");

        Response next = call().get(API + "/" + SCOPE
            + "/decision?page_size=3&type=decision&after=" + cursor);

        assertThat(next.statusCode())
            .as("the page size is not part of the binding: the position is the same one")
            .isEqualTo(200);
        assertThat(next.jsonPath().getList(KEYS, String.class)).hasSize(3);
    }

    @Test
    void a_cursor_this_verb_did_not_hand_out_is_refused() {
        Response answer = call().get(API + "/" + SCOPE + "?after=not-a-cursor!!");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("CURSOR_MALFORMED");

        // The form this replaces: Base64url of a bare key. It decodes, and is
        // still not a cursor of this verb.
        Response bareKey = call().get(API + "/" + SCOPE + "?after=ZGVjaXNpb24uaXRlbS0x");
        assertThat(bareKey.jsonPath().getString("reason")).isEqualTo("CURSOR_MALFORMED");
    }

    // ======================================================================
    // The order and the page size, refused rather than replaced
    // ======================================================================

    @Test
    void an_order_over_an_undeclared_field_is_refused_and_named() {
        Response byContent = call().get(API + "/" + SCOPE + "?sort=content");

        assertThat(byContent.statusCode()).isEqualTo(400);
        assertThat(byContent.jsonPath().getString("reason")).isEqualTo("SORT_UNDECLARED");
        assertThat(byContent.jsonPath().getList("data.offenders", String.class))
            .as("the argument, never its value (DEC-0042)")
            .containsExactly("sort");
    }

    @Test
    void a_direction_that_is_neither_of_the_two_is_an_invalid_argument() {
        Response sideways = call().get(API + "/" + SCOPE + "?direction=sideways");

        assertThat(sideways.statusCode()).isEqualTo(400);
        assertThat(sideways.jsonPath().getString("reason"))
            .as("the two directions are the argument's value set, not an order a scope "
                + "declares, so this is no SORT_UNDECLARED (ADR-0049)")
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(sideways.jsonPath().getList("data.offenders", String.class))
            .containsExactly("direction");
    }

    @Test
    void a_page_size_beyond_the_limit_is_refused_and_not_quietly_reduced() {
        Response answer = call().get(API + "/" + SCOPE + "?page_size=5000");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("PAGE_SIZE_REJECTED");
        assertThat(answer.jsonPath().getString("message"))
            .as("a page silently made smaller than the one asked for is how a caller "
                + "comes to believe it has seen everything")
            .contains("from 1 to 200");
    }

    @Test
    void an_argument_this_verb_does_not_take_is_refused_and_named() {
        Response answer = call().get(API + "/" + SCOPE + "?author=somebody");

        assertThat(answer.statusCode())
            .as("decidable without a scope: this service declares no filter fields per "
                + "scope, so an unknown name is an unknown argument (DEC-0042)")
            .isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(answer.jsonPath().getList("data.accepts", String.class))
            .as("a filter that is silently dropped answers with the whole set and looks "
                + "exactly like a correct narrow one; the refusal names the arguments the "
                + "query takes, and never the one that arrived (DEC-0043 R19)")
            .containsExactly("type", "text", "after", "sort", "direction", "page_size");
        assertThat(answer.asString()).doesNotContain("author");
    }

    // ======================================================================
    // The narrowing predicates
    // ======================================================================

    @Test
    void the_type_and_the_text_predicates_narrow_what_they_say_they_narrow() {
        assertThat(call().get(API + "/" + SCOPE + "?type=convention&page_size=50")
                .jsonPath().getList(KEYS, String.class))
            .hasSize(7);

        Response text = call()
            .queryParam("text", "NUMBER 1")
            .queryParam("page_size", 50)
            .get(API + "/" + SCOPE);
        assertThat(text.jsonPath().getList(KEYS, String.class))
            .as("the text predicate is a substring of the content and ignores case")
            .contains("decision.item-1", "convention.item-1")
            .doesNotContain("decision.item-2");
    }

    @Test
    void the_wildcards_of_like_in_a_text_stand_for_themselves() {
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "metric.literal", "decision", "a share of 5% exactly"));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "metric.number", "decision", "a share of 50 and more"));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "metric.under", "decision", "snake_case and nothing else"));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "metric.bang", "decision", "really! and then more"));

        assertThat(textMatches("5%"))
            .as("'%' in the text is a percent sign, not 'anything'")
            .containsExactly("metric.literal");
        assertThat(textMatches("e_c"))
            .as("'_' in the text is an underscore, not 'any one character'")
            .containsExactly("metric.under");
        assertThat(textMatches("really!"))
            .as("and the escape character itself stands for itself: undoubled, it would "
                + "escape the closing wildcard and the substring would become a suffix")
            .containsExactly("metric.bang");
    }

    @Test
    void a_query_in_a_scope_the_caller_may_not_enter_is_the_single_not_found() {
        Response answer = call().get(API + "/"
            + SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG);

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    /**
     * Every key of a listing, page by page until {@code next_cursor} is absent.
     *
     * @param pathAndQuery below the scope, with a query string already begun
     * @param betweenPages run after each page, before the next is asked for
     */
    private static List<String> walk(String pathAndQuery, Consumer<Response> betweenPages) {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = call().get(API + "/" + SCOPE + pathAndQuery
                + (cursor == null ? "" : "&after=" + cursor));
            assertThat(page.statusCode()).as("%s", page.asString()).isEqualTo(200);
            seen.addAll(page.jsonPath().getList(KEYS, String.class));
            cursor = page.jsonPath().getString("next_cursor");
            betweenPages.accept(page);
            pages++;
            assertThat(pages).as("paging must terminate").isLessThan(30);
        } while (cursor != null);
        return seen;
    }

    private static String firstCursor(String pathAndQuery) {
        String cursor = call().get(API + "/" + SCOPE + pathAndQuery)
            .jsonPath().getString("next_cursor");
        assertThat(cursor).as("the first page of %s hands out a cursor", pathAndQuery)
            .isNotBlank();
        return cursor;
    }

    private static void assertForeign(Response answer) {
        assertThat(answer.statusCode()).as("%s", answer.asString()).isEqualTo(400);
        assertThat(answer.jsonPath().getString("reason"))
            .as("a cursor applied to a query it was not handed out for answers that "
                + "query's page from a position of another — measured before this probe: "
                + "zero entries, read as the end of the listing")
            .isEqualTo("CURSOR_FOREIGN");
    }

    private static List<String> textMatches(String text) {
        return call().queryParam("text", text).queryParam("page_size", 50)
            .get(API + "/" + SCOPE).jsonPath().getList(KEYS, String.class);
    }
}
