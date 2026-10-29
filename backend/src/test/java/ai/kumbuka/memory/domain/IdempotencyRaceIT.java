/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

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

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two {@code create} under one idempotency key at once, with the same
 * values: the second waits for the first and answers the entry the first
 * laid down, as a repeat after the first had ended would (C10.4).
 *
 * <p>The overlap is forced, not hoped for. The first create runs in a
 * transaction left open after it has laid the entry down and written its key;
 * the second is sent over the surface on a thread of its own; the database is
 * asked whether it waits on the first ({@link LockWaits}); then the first
 * commits. Without the lock over the key the second would find no spent key
 * (the first has not committed) and go on to lay down an entry of its own,
 * meeting the first's insert at the unique index and answering
 * {@code ALREADY_EXISTS}: a retry under a key refused for being a retry.
 *
 * <p>RED STATE, observed 2026-10-29 with the key's lock taken out of {@code
 * MemoryService}: the second create answered 409 {@code ALREADY_EXISTS}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class IdempotencyRaceIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final Actor PROBE = new Actor(SubstrateDatabaseResource.PROBE_SUBJECT);
    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "decision.raced";
    private static final String IDEMPOTENCY_KEY = "the-one-retry";

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
        SurfaceFixture.run("DELETE FROM memory.idempotency_key");
    }

    @Test
    void the_second_of_two_overlapping_creates_under_one_key_answers_the_first() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> second;
            EntryView first;
            try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
                QuarkusTransaction.begin();
                try {
                    first = memory.create(PROBE, EntryAddress.ofKey(SCOPE, KEY),
                        new Draft("decision", "the one text", null),
                        IdempotencyKey.of(IDEMPOTENCY_KEY));
                    int holder = LockWaits.sessionOf(em);

                    second = pool.submit(IdempotencyRaceIT::createOverRest);

                    assertThat(LockWaits.waitsOn(holder, second))
                        .as("the second create met the first one's hold; otherwise this case "
                            + "shows nothing about an overlap")
                        .isTrue();
                } finally {
                    QuarkusTransaction.commit();
                }
            }
            Response answered = second.get(30, TimeUnit.SECONDS);

            assertThat(answered.statusCode()).as(answered.asString()).isEqualTo(201);
            assertThat(answered.jsonPath().getString("conflict_token"))
                .as("the entry the first laid down").isEqualTo(first.conflictToken());
            assertThat(SurfaceFixture.countRows("SELECT count(*) FROM memory.memory"))
                .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Response createOverRest() {
        return SurfaceFixture.call().header("Idempotency-Key", IDEMPOTENCY_KEY)
            .body(SurfaceFixture.fields("key", KEY, "type", "decision",
                "content", "the one text"))
            .post(SurfaceFixture.API + "/" + SCOPE + "/decision");
    }
}
