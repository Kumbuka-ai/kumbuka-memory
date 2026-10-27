/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.contract.Contract;
import ai.kumbuka.memory.contract.Mcp;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.memory.tenancy.TenantContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.memory.contract.Mcp.args;
import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static ai.kumbuka.memory.surface.SurfaceFixture.fields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two {@code create} on one key at once: exactly one lays the entry down,
 * and the other is refused {@code ALREADY_EXISTS} in the form a create onto
 * an occupied key is refused when the two do not overlap.
 *
 * <p>The race is forced, not hoped for. The first {@code create} runs in a
 * transaction left open after its insert; the second is sent over the surface
 * on a thread of its own, finds no occupant (the first has not committed),
 * and inserts; the database is asked whether that insert waits on the first
 * at the unique index ({@link LockWaits}); then the first commits, and the
 * index refuses the second. The refusal is compared against the one a
 * sequential create onto the same key answers right after, on the same
 * surface, so "in its form of today" is measured and not restated.
 *
 * <p>RED STATE, observed on the tree before the fix (memory {@code 482a26b}):
 * the unique violation reached REST as a 500 and MCP as
 * {@code UNEXPECTED_FAILURE}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class CreateRaceIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final Actor PROBE = new Actor(SubstrateDatabaseResource.PROBE_SUBJECT);
    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String SELECTOR = "decision";
    private static final String KEY = "decision.raced";

    @Inject MemoryService memory;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyScope() {
        SurfaceFixture.clearEntries();
    }

    @Test
    void the_loser_of_a_create_race_on_rest_is_refused_already_exists() throws Exception {
        Response lost = whileTheFirstHoldsItsInsert(CreateRaceIT::createOverRest);
        Response sequential = createOverRest();

        assertThat(sequential.statusCode()).as(sequential.asString()).isEqualTo(409);
        assertThat(lost.statusCode())
            .as("the loser of the race, answered: %s", lost.asString())
            .isEqualTo(sequential.statusCode());
        assertThat(lost.jsonPath().getString("reason")).isEqualTo("ALREADY_EXISTS");
        assertThat(lost.jsonPath().<Object>get("data"))
            .as("data as a sequential create onto the occupied key answers it")
            .isEqualTo(sequential.jsonPath().get("data"));
    }

    @Test
    void the_loser_of_a_create_race_on_mcp_is_refused_already_exists() throws Exception {
        Mcp.Result lost = whileTheFirstHoldsItsInsert(CreateRaceIT::createOverMcp);
        Mcp.Result sequential = createOverMcp();

        assertThat(sequential.reason()).as(sequential.toString()).isEqualTo("ALREADY_EXISTS");
        assertThat(lost.isError()).isTrue();
        assertThat(lost.reason())
            .as("the loser of the race, answered: %s", lost.structured())
            .isEqualTo("ALREADY_EXISTS");
        assertThat(lost.map("data"))
            .as("data as a sequential create onto the occupied key answers it")
            .isEqualTo(sequential.map("data"));
    }

    // -----------------------------------------------------------------------

    private static Response createOverRest() {
        return call().body(fields("key", KEY, "type", "decision", "content", "the second"))
            .post(API + "/" + SCOPE + "/" + SELECTOR);
    }

    private static Mcp.Result createOverMcp() {
        return Mcp.call(Contract.toolFor("create"), args("scope", SCOPE, "selector", SELECTOR,
            "fields", Map.of("key", KEY, "type", "decision", "content", "the second")));
    }

    /**
     * Lays the entry down in a transaction left open, sends {@code second} on
     * a thread of its own, asserts that the database has it waiting on the
     * first, commits the first, and answers what the second answered.
     */
    private <T> T whileTheFirstHoldsItsInsert(Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<T> secondCreate;
            try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
                QuarkusTransaction.begin();
                try {
                    memory.create(PROBE, EntryAddress.ofKey(SCOPE, KEY),
                        new Draft("decision", "the first", null));
                    int holder = LockWaits.sessionOf(em);

                    secondCreate = pool.submit(second);

                    assertThat(LockWaits.waitsOn(holder, secondCreate))
                        .as("the second create met the first one's insert at the unique "
                            + "index; otherwise this case shows nothing about a race")
                        .isTrue();
                } finally {
                    QuarkusTransaction.commit();
                }
            }
            return secondCreate.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
