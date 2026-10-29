/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NOT_FOUND} is one answer, byte for byte, whatever its cause (C8.4,
 * DEC-0042): an entry that is not there, a scope the caller may not see, and
 * an address no scope answers to.
 *
 * <p>Three addresses, each well formed: a key nothing was laid down under in
 * the caller's own scope; a scope that exists in another tenant, which the
 * caller's directory never shows; and a scope that exists nowhere. On each
 * surface -- the generic one, the generic one asked for the assistant
 * surface's projection, and the MCP adapter -- the three answers are
 * compared with each other, byte for byte, and the envelope carries no
 * {@code data}: a difference of one byte would tell a caller which of the
 * three it hit.
 *
 * <p>The rule on the envelope is {@link #notFoundOffences}: {@code data} is
 * absent, not merely free of the caller's values. An empty {@code data}, or
 * one that carries only {@code attempted}, tells nothing of the cause and
 * still breaks the envelope C8.4 fixes, and it is how this service answered on
 * its assistant surface before.
 *
 * <p>RED STATE, observed 2026-10-29 with a violation planted for the run: an
 * unresolved scope answered {@code NOT_FOUND} with a {@code data} member, and
 * the cases of every surface turned red. The rule's own red state runs on
 * every build in {@link #the_check_reports_a_not_found_that_carries_data}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class NotFoundIdentityIT {

    /** A scope of another tenant: it exists, and this caller's directory does not show it. */
    static final String INVISIBLE_SCOPE = SubstrateDatabaseResource.OTHER_TENANT_SCOPE_SLUG;

    private static final String SURFACE = "Kumbuka-Surface";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void noEntries() {
        SurfaceFixture.clearEntries();
    }

    /** The three causes, as complete addresses under one selector. */
    static List<String> causes() {
        return List.of(
            "memory://" + SubstrateDatabaseResource.PROBE_SCOPE_SLUG + "/decision/nothing-here",
            "memory://" + INVISIBLE_SCOPE + "/decision/storage-engine",
            "memory://probe-scope-nowhere/decision/storage-engine");
    }

    @Test
    void over_rest_absent_invisible_and_unroutable_answer_the_same_bytes() {
        List<Response> answers = causes().stream()
            .map(address -> SurfaceFixture.call().get(path(address)))
            .toList();
        assertOneNotFound(answers);
    }

    @Test
    void over_rest_on_the_assistant_surface_they_answer_the_same_bytes_too() {
        List<Response> answers = causes().stream()
            .map(address -> SurfaceFixture.call().header(SURFACE, "assistant")
                .get(path(address)))
            .toList();
        assertOneNotFound(answers);
        assertThat(answers.get(0).asString())
            .as("and the header changes nothing about the not-found class")
            .isEqualTo(SurfaceFixture.call().get(path(causes().get(0))).asString());
    }

    /**
     * A route no resource carries is the framework's 404, and it answers the
     * same bytes as an absent and an invisible entry (C8.3, C8.4).
     */
    @Test
    void the_framework_s_not_found_answers_the_same_bytes_as_the_three_causes() {
        Response framework = SurfaceFixture.call()
            .get(SurfaceFixture.API + "/probe-scope/decision/storage-engine/more");
        Response absent = SurfaceFixture.call().get(path(causes().get(0)));
        assertThat(framework.statusCode()).isEqualTo(404);
        assertThat(framework.asString()).as("one answer, byte for byte")
            .isEqualTo(absent.asString());
    }

    @Test
    void over_mcp_absent_invisible_and_unroutable_answer_the_same_bytes() {
        List<Mcp.Result> answers = causes().stream()
            .map(address -> Mcp.call("memory_read", args("address", address)))
            .toList();
        for (Mcp.Result answer : answers) {
            assertThat(notFoundOffences(answer.structured()))
                .as("NOT_FOUND carries no data (C8.4)").isEmpty();
        }
        assertThat(answers.stream().map(Mcp.Result::text).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
        assertThat(answers.get(0).text())
            .as("and the same bytes as the generic surface")
            .isEqualTo(SurfaceFixture.call().get(path(causes().get(0))).asString());
    }

    @Test
    void a_write_to_any_of_the_three_answers_the_same_bytes_as_a_read() {
        List<Mcp.Result> writes = causes().stream()
            .map(address -> Mcp.call("memory_withdraw", args("address", address,
                "conflict_token", "t")))
            .toList();
        for (Mcp.Result write : writes) {
            assertThat(notFoundOffences(write.structured()))
                .as("a write's NOT_FOUND carries no data either (C8.4)").isEmpty();
        }
        assertThat(writes.stream().map(Mcp.Result::text).distinct().toList())
            .hasSize(1);
    }

    /** The red state of the rule, observed on every build. */
    @Test
    void the_check_reports_a_not_found_that_carries_data() {
        Map<String, Object> planted = new LinkedHashMap<>();
        planted.put("reason", "NOT_FOUND");
        planted.put("message", "Nothing is here.");
        planted.put("data", Map.of("attempted", "memory_read"));
        assertThat(notFoundOffences(planted))
            .as("RED STATE, observed: a NOT_FOUND with data.attempted, which carries no value "
                + "of the caller, must be reported for the member itself")
            .containsExactly("NOT_FOUND carries data");
        assertThat(notFoundOffences(Map.of("reason", "NOT_FOUND", "message", "m",
                "data", Map.of())))
            .as("and so must an empty data").containsExactly("NOT_FOUND carries data");
    }

    private static void assertOneNotFound(List<Response> answers) {
        for (Response answer : answers) {
            assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(404);
            assertThat(notFoundOffences(answer.jsonPath().getMap("$")))
                .as("NOT_FOUND carries no data (C8.4)").isEmpty();
        }
        assertThat(answers.stream().map(Response::asString).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
    }

    /**
     * What is wrong with an envelope that should be {@code NOT_FOUND}: another
     * reason, a {@code data} member of any content, or a member beside the two.
     */
    static List<String> notFoundOffences(Map<String, Object> envelope) {
        List<String> offences = new ArrayList<>();
        if (!"NOT_FOUND".equals(envelope.get("reason"))) {
            offences.add("the reason is " + envelope.get("reason"));
        }
        if (envelope.containsKey("data")) {
            offences.add("NOT_FOUND carries data");
        }
        envelope.keySet().stream()
            .filter(key -> !List.of("reason", "message", "data").contains(key))
            .forEach(key -> offences.add("NOT_FOUND carries " + key));
        return offences;
    }

    /** The REST path of a complete address. */
    static String path(String address) {
        return SurfaceFixture.API + "/" + address.substring("memory://".length());
    }
}
