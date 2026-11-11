/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.contract.Mcp;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.memory.contract.Mcp.args;
import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tags on entries (V11): the two tag verbs, the field {@code tags} on read,
 * the filter {@code tag} on query, the tag narrowing of the digest, and the
 * tenant boundary around all of them.
 *
 * <p>The vocabulary is the platform's, staged by the substrate as the
 * contract publishes it ({@code platform.tag_access}): in the probe scope of
 * tenant A {@code component:memory} and {@code release:v1} in force and
 * {@code legacy} withdrawn; in tenant B's scope a {@code component:memory} of
 * its own and {@code beta-only}. Every expectation is written from the order
 * of sprint 210, not read off an answer of the service.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class TaggingIT {

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "decision.storage";
    private static final String ITEM = API + "/" + SCOPE + "/decision/storage";
    private static final String ADDRESS = "memory://" + SCOPE + "/decision/storage";

    private static final String COMPONENT = SubstrateDatabaseResource.TAG_COMPONENT;
    private static final String RELEASE = SubstrateDatabaseResource.TAG_RELEASE;
    private static final String RETIRED = SubstrateDatabaseResource.TAG_RETIRED;

    /** A tag the tenant's global scope declares, and the probe scope does not. */
    private static final String GLOBAL_ONLY = "global-only";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
        PlatformFixture.declareTag(SubstrateDatabaseResource.GLOBAL_SCOPE_ID, GLOBAL_ONLY,
            "declared");
    }

    @BeforeEach
    void oneEntry() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "decision", "Entries live in PostgreSQL."));
    }

    // ------------------------------------------------------------------
    // tag_attach, tag_detach and read
    // ------------------------------------------------------------------

    @Test
    void an_entry_without_tags_reads_an_empty_list() {
        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class))
            .as("read carries tags, and an entry without any carries the empty list")
            .isEmpty();
    }

    @Test
    void attach_answers_the_address_and_every_tag_and_read_carries_them_sorted() {
        Response first = attach(RELEASE);
        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
        assertThat(first.jsonPath().getString("address")).isEqualTo(ADDRESS);
        assertThat(first.jsonPath().getList("tags", String.class)).containsExactly(RELEASE);

        Response second = attach(COMPONENT);
        assertThat(second.jsonPath().getList("tags", String.class))
            .as("the answer carries the complete list, not the one tag attached")
            .containsExactly(COMPONENT, RELEASE);

        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class))
            .as("read carries the tokens, sorted ascending")
            .containsExactly(COMPONENT, RELEASE);
    }

    @Test
    void attach_leaves_the_conflict_token_and_the_change_time_unchanged() {
        Response before = call().get(ITEM);
        String token = before.jsonPath().getString("conflict_token");
        String changed = before.jsonPath().getString("fields.updated_at");

        assertThat(attach(COMPONENT).statusCode()).isEqualTo(200);
        assertThat(detach(COMPONENT).statusCode()).isEqualTo(200);
        assertThat(attach(RELEASE).statusCode()).isEqualTo(200);

        Response after = call().get(ITEM);
        assertThat(after.jsonPath().getString("conflict_token"))
            .as("an assignment is a relation and not a field of the entry: the conflict "
                + "token stays where it was")
            .isEqualTo(token);
        assertThat(after.jsonPath().getString("fields.updated_at"))
            .as("and so does the change time")
            .isEqualTo(changed);
        assertThat(after.header("ETag")).contains(token);
    }

    @Test
    void attaching_an_attached_tag_succeeds_and_writes_nothing() {
        attach(COMPONENT);
        long rows = assignments();

        Response again = attach(COMPONENT);
        assertThat(again.statusCode()).as(again.asString()).isEqualTo(200);
        assertThat(again.jsonPath().getList("tags", String.class)).containsExactly(COMPONENT);
        assertThat(assignments()).as("a no-op: one assignment, as before").isEqualTo(rows);
    }

    @Test
    void detaching_a_tag_the_entry_lacks_succeeds_and_writes_nothing() {
        attach(RELEASE);

        Response detached = detach(COMPONENT);
        assertThat(detached.statusCode()).as(detached.asString()).isEqualTo(200);
        assertThat(detached.jsonPath().getList("tags", String.class)).containsExactly(RELEASE);
        assertThat(assignments()).isEqualTo(1);
    }

    @Test
    void detach_removes_the_assignment() {
        attach(RELEASE);
        attach(COMPONENT);

        Response detached = detach(RELEASE);
        assertThat(detached.statusCode()).isEqualTo(200);
        assertThat(detached.jsonPath().getList("tags", String.class)).containsExactly(COMPONENT);
        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class))
            .containsExactly(COMPONENT);
    }

    @Test
    void an_unknown_token_is_refused_as_undeclared_naming_the_field_tag() {
        Response refused = attach("no-such-tag");

        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("VALUE_UNDECLARED");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("tag");
        assertThat(refused.asString()).doesNotContain("no-such-tag");
    }

    @Test
    void a_withdrawn_tag_is_refused_on_attach_and_the_message_says_it_is_withdrawn() {
        Response refused = attach(RETIRED);

        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("VALUE_WITHDRAWN");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("tag");
        assertThat(refused.jsonPath().getString("message")).contains("withdrawn");
        assertThat(assignments()).isZero();
    }

    @Test
    void a_withdrawn_tag_still_attached_is_read_and_can_be_detached() {
        assignDirectly(SubstrateDatabaseResource.TENANT_ID, logicalId(),
            SubstrateDatabaseResource.TAG_RETIRED_ID);

        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class))
            .as("withdrawing a tag forbids new assignments; the standing one stays readable")
            .containsExactly(RETIRED);

        Response detached = detach(RETIRED);
        assertThat(detached.statusCode()).as(detached.asString()).isEqualTo(200);
        assertThat(detached.jsonPath().getList("tags", String.class)).isEmpty();
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.MUTED_SUBJECT)
    void a_caller_without_the_write_right_is_refused_both_tag_verbs() {
        for (Response refused : List.of(attach(COMPONENT), detach(COMPONENT))) {
            assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(403);
            assertThat(refused.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        }
        assertThat(assignments()).isZero();
    }

    @Test
    void update_leaves_the_tags_alone() {
        attach(COMPONENT);
        String token = call().get(ITEM).jsonPath().getString("conflict_token");

        Response updated = call().header("If-Match", token)
            .body(SurfaceFixture.fields("content", "Entries live in PostgreSQL 16."))
            .patch(ITEM);
        assertThat(updated.statusCode()).as(updated.asString()).isEqualTo(200);
        assertThat(updated.jsonPath().getList("fields.tags", String.class))
            .containsExactly(COMPONENT);

        Response named = call().header("If-Match",
                updated.jsonPath().getString("conflict_token"))
            .body("{\"fields\":{\"content\":\"x\",\"tags\":[\"release:v1\"]}}")
            .patch(ITEM);
        assertThat(named.statusCode()).as(named.asString()).isEqualTo(422);
        assertThat(named.jsonPath().getList("data.offenders", String.class))
            .as("tags are written by the tag verbs alone, and naming them in an update is "
                + "refused rather than dropped")
            .containsExactly("tags");
    }

    @Test
    void the_assistant_surface_carries_the_same_verbs() {
        Mcp.Result attached = Mcp.call("memory_tag_attach", args("address", ADDRESS,
            "tag", COMPONENT));
        assertThat(attached.isError()).as("%s", attached).isFalse();
        assertThat(attached.string("address")).isEqualTo(ADDRESS);
        assertThat(attached.strings("tags")).containsExactly(COMPONENT);

        Mcp.Result read = Mcp.call("memory_read", args("address", ADDRESS));
        assertThat(read.strings("fields.tags")).containsExactly(COMPONENT);

        Mcp.Result detached = Mcp.call("memory_tag_detach", args("address", ADDRESS,
            "tag", COMPONENT));
        assertThat(detached.strings("tags")).isEmpty();

        Mcp.Result refused = Mcp.call("memory_tag_attach", args("address", ADDRESS,
            "tag", RETIRED));
        assertThat(refused.reason()).isEqualTo("VALUE_WITHDRAWN");
        assertThat(refused.strings("data.offenders")).containsExactly("tag");
    }

    // ------------------------------------------------------------------
    // The filter of query
    // ------------------------------------------------------------------

    @Test
    void query_with_a_tag_lists_only_the_entries_that_carry_it() {
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "decision.queue", "decision", "A queue is a table."));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "convention.naming", "convention", "Names are nouns."));
        attach(COMPONENT);
        attachTo(API + "/" + SCOPE + "/convention/naming", COMPONENT);

        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + COMPONENT)))
            .containsExactly("convention.naming", KEY);
        assertThat(keysOf(call().get(API + "/" + SCOPE + "/decision?tag=" + COMPONENT)))
            .as("combined with the selector by AND")
            .containsExactly(KEY);
        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + COMPONENT
                + "&type=convention")))
            .as("and with the type")
            .containsExactly("convention.naming");
        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + RELEASE)))
            .as("a declared tag nothing carries lists nothing")
            .isEmpty();
    }

    @Test
    void query_with_an_unknown_token_answers_an_empty_page_and_no_refusal() {
        attach(COMPONENT);

        Response page = call().get(API + "/" + SCOPE + "?tag=no-such-tag");
        assertThat(page.statusCode()).as(page.asString()).isEqualTo(200);
        assertThat(keysOf(page)).isEmpty();
        assertThat(page.jsonPath().getString("next_cursor")).isNull();
    }

    @Test
    void query_pages_through_a_tag_and_binds_the_cursor_to_it() {
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "decision.queue", "decision", "A queue is a table."));
        attach(COMPONENT);
        attachTo(API + "/" + SCOPE + "/decision/queue", COMPONENT);

        Response first = call().get(API + "/" + SCOPE + "?tag=" + COMPONENT + "&page_size=1");
        assertThat(keysOf(first)).containsExactly("decision.queue");
        String cursor = first.jsonPath().getString("next_cursor");
        assertThat(cursor).isNotNull();

        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + COMPONENT
                + "&page_size=1&after=" + cursor)))
            .containsExactly(KEY);
        Response foreign = call().get(API + "/" + SCOPE + "?page_size=1&after=" + cursor);
        assertThat(foreign.jsonPath().getString("reason"))
            .as("a cursor of the tagged listing does not continue the untagged one")
            .isEqualTo("CURSOR_FOREIGN");
    }

    // ------------------------------------------------------------------
    // The tenant boundary
    // ------------------------------------------------------------------

    @Test
    void another_tenants_assignment_is_seen_neither_in_read_nor_through_the_filter() {
        // Tenant B's row, planted as the superuser, on tenant A's own entry and
        // with tenant A's own tag: the sharpest form, because only the tenant
        // of the assignment row separates it from one of A's.
        assignDirectly(SubstrateDatabaseResource.OTHER_TENANT_ID, logicalId(),
            SubstrateDatabaseResource.TAG_RELEASE_ID);
        // And tenant B's own entry with tenant B's tag of the same token.
        UUID foreign = SurfaceFixture.plant(new SurfaceFixture.Planted(
            SubstrateDatabaseResource.OTHER_TENANT_ID,
            SubstrateDatabaseResource.OTHER_TENANT_SCOPE_ID, false,
            SubstrateDatabaseResource.OTHER_TENANT_SUBJECT, "decision", "decision.beta",
            "a foreign entry", Instant.now()));
        assignDirectly(SubstrateDatabaseResource.OTHER_TENANT_ID, foreign,
            SubstrateDatabaseResource.OTHER_TENANT_COMPONENT_ID);
        assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.entry_tag"))
            .as("both foreign assignments are really in the table")
            .isEqualTo(2);

        assertThat(call().get(ITEM).jsonPath().getList("fields.tags", String.class))
            .as("tenant B's assignment on this entry is not tenant A's tag")
            .isEmpty();
        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + RELEASE)))
            .as("and the filter finds nothing through it")
            .isEmpty();
        assertThat(keysOf(call().get(API + "/" + SCOPE + "?tag=" + COMPONENT)))
            .as("nor through tenant B's tag of the same token")
            .isEmpty();
    }

    @Test
    void a_token_of_a_foreign_or_another_scope_cannot_be_attached() {
        Response foreignTenant = attach(SubstrateDatabaseResource.TAG_OTHER_TENANT);
        assertThat(foreignTenant.jsonPath().getString("reason"))
            .as("a tag of another tenant's scope is in no vocabulary this caller can see")
            .isEqualTo("VALUE_UNDECLARED");

        Response otherScope = attach(GLOBAL_ONLY);
        assertThat(otherScope.jsonPath().getString("reason"))
            .as("a tag is resolved in the scope of the entry, and a visible scope's "
                + "vocabulary is not this one's")
            .isEqualTo("VALUE_UNDECLARED");
        assertThat(assignments()).isZero();
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.OTHER_TENANT_SUBJECT)
    void another_tenants_caller_cannot_tag_this_tenants_entry() {
        Response refused = attach(COMPONENT);
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(404);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
        assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.entry_tag"))
            .isZero();
    }

    // ------------------------------------------------------------------
    // withdraw
    // ------------------------------------------------------------------

    @Test
    void withdraw_removes_the_assignments_of_the_entry() {
        attach(COMPONENT);
        attach(RELEASE);
        assertThat(assignments()).isEqualTo(2);

        String token = call().get(ITEM).jsonPath().getString("conflict_token");
        Response withdrawn = call().header("If-Match", token).post(ITEM + ":withdraw");
        assertThat(withdrawn.statusCode()).as(withdrawn.asString()).isEqualTo(200);

        assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.entry_tag"))
            .as("the assignments go with the entry, by the cascade of V11")
            .isZero();
    }

    // ------------------------------------------------------------------
    // The digest
    // ------------------------------------------------------------------

    @Test
    void a_digest_with_tags_carries_every_constraint_and_only_the_matching_decisions() {
        SurfaceFixture.clearEntries();
        for (int i = 1; i <= 3; i++) {
            SurfaceFixture.plant(SurfaceFixture.Planted.shared(
                SubstrateDatabaseResource.SCOPE_ID, "constraint.c" + i, "constraint",
                "constraint number " + i));
        }
        for (int i = 1; i <= 4; i++) {
            SurfaceFixture.plant(SurfaceFixture.Planted.shared(
                SubstrateDatabaseResource.SCOPE_ID, "decision.d" + i, "decision",
                "decision number " + i));
        }
        attachTo(API + "/" + SCOPE + "/decision/d1", COMPONENT);
        attachTo(API + "/" + SCOPE + "/decision/d3", RELEASE);
        attachTo(API + "/" + SCOPE + "/constraint/c2", RELEASE);

        Response digest = call()
            .body("{\"types\":[\"constraint\",\"decision\"],\"tags\":[\"" + COMPONENT
                + "\",\"no-such-tag\"]}")
            .post(API + "/" + SCOPE + ":digest");
        assertThat(digest.statusCode()).as(digest.asString()).isEqualTo(200);

        assertThat(keysIn(digest, "constraint"))
            .as("every constraint, whatever its tags")
            .containsExactly("constraint.c1", "constraint.c2", "constraint.c3");
        assertThat(keysIn(digest, "decision"))
            .as("of the decisions, only those that carry one of the tags; the unknown token "
                + "keeps nothing and refuses nothing")
            .containsExactly("decision.d1");

        Map<String, Object> constraints = tallyOf(digest, "constraint");
        Map<String, Object> decisions = tallyOf(digest, "decision");
        assertThat(constraints.get("count")).isEqualTo(3);
        assertThat(decisions.get("count"))
            .as("the summary counts what the digest carries")
            .isEqualTo(1);
        assertThat(decisions.get("carried")).isEqualTo(true);
        assertThat(decisions.get("token_estimate"))
            .isEqualTo("decision number 1".length() / 4);
        assertThat(digest.jsonPath().getInt("total_entries")).isEqualTo(4);
    }

    @Test
    void a_digest_with_an_empty_list_of_tags_is_the_digest_without_tags() {
        attach(COMPONENT);
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "decision.queue", "decision", "A queue is a table."));

        Response without = call().post(API + "/" + SCOPE + ":digest");
        Response empty = call().body("{\"tags\":[]}").post(API + "/" + SCOPE + ":digest");
        assertThat(empty.statusCode()).as(empty.asString()).isEqualTo(200);
        assertThat(empty.jsonPath().getMap("$"))
            .as("no tags and an empty list answer exactly what the digest answers today")
            .isEqualTo(without.jsonPath().getMap("$"));
        assertThat(keysIn(without, "decision")).containsExactly(KEY, "decision.queue");

        Mcp.Result overMcp = Mcp.call("memory_digest", args("scope", SCOPE, "tags", List.of()));
        assertThat(overMcp.isError()).as("%s", overMcp).isFalse();
        assertThat(overMcp.structured()).isEqualTo(without.jsonPath().getMap("$"));
    }

    // ------------------------------------------------------------------

    private static Response attach(String tag) {
        return attachTo(ITEM, tag);
    }

    private static Response attachTo(String item, String tag) {
        return call().body("{\"tag\":\"" + tag + "\"}").post(item + ":tag_attach");
    }

    private static Response detach(String tag) {
        return call().body("{\"tag\":\"" + tag + "\"}").post(ITEM + ":tag_detach");
    }

    private static UUID logicalId() {
        return SurfaceFixture.logicalIdOf(SubstrateDatabaseResource.SCOPE_ID, KEY);
    }

    private static long assignments() {
        return SurfaceFixture.countRows("SELECT count(*) FROM memory.entry_tag "
            + "WHERE entry_logical_id = '" + logicalId() + "'");
    }

    /** An assignment written as the superuser, under the tenant named. */
    private static void assignDirectly(String tenant, UUID entry, String tagId) {
        SurfaceFixture.run("INSERT INTO memory.entry_tag (tenant_id, entry_logical_id, tag_id, "
            + "attached_by) VALUES ('" + tenant + "', '" + entry + "', '" + tagId
            + "', 'planted')");
    }

    private static List<String> keysOf(Response page) {
        assertThat(page.statusCode()).as(page.asString()).isEqualTo(200);
        return page.jsonPath().getList("objects.fields.key", String.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysIn(Response digest, String type) {
        return digest.jsonPath().getList("sections", Map.class).stream()
            .filter(section -> type.equals(section.get("type")))
            .findFirst()
            .map(section -> ((List<Map<String, Object>>) section.get("entries")).stream()
                .map(entry -> String.valueOf(entry.get("key")))
                .toList())
            .orElseThrow(() -> new AssertionError("the digest carries no section " + type));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tallyOf(Response digest, String type) {
        return digest.jsonPath().getList("summary", Map.class).stream()
            .map(tally -> (Map<String, Object>) tally)
            .filter(tally -> type.equals(tally.get("type")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the summary says nothing of " + type));
    }
}
