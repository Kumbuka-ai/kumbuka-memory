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
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Two writers present the same conflict token: exactly one writes, and the
 * other is refused (C10.2).
 *
 * <p>The comparison of the presented token and the write happen under the
 * row lock, refreshed under it (C11.1). Each case makes the two writers
 * overlap for real: the first holds its write in an open transaction while
 * the second is started, and the database is asked whether the second waits
 * on the first ({@link LockWaits}) before the first commits. Without the lock
 * the second reads the committed row, finds its token current, and writes
 * too.
 *
 * <p>RED STATE, observed on the tree before the lock (memory {@code 482a26b}):
 * the second {@code update} wrote over the first, and the {@code withdraw}
 * removed the entry the first writer had just changed; both waited on the
 * first writer only at their own statement, after the comparison had already
 * passed. A {@code withdraw} first and an {@code update} second ended in an
 * unexpected failure of the persistence layer instead of the not-found.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConcurrentWriteIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final Actor PROBE = new Actor(SubstrateDatabaseResource.PROBE_SUBJECT);
    private static final EntryAddress CONTESTED =
        EntryAddress.ofKey(SubstrateDatabaseResource.PROBE_SCOPE_SLUG, "decision.contested");

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
    void two_updates_with_one_token_exactly_one_writes() throws Exception {
        String token = created();

        Throwable second = whileTheFirstHoldsItsWrite(
            () -> memory.update(PROBE, CONTESTED, token, content("first writer")),
            () -> memory.update(PROBE, CONTESTED, token, content("second writer")));

        assertRefused(second, MemoryException.Reason.CONFLICT_TOKEN_STALE);
        assertThat(current().content())
            .as("the first writer's change stands").isEqualTo("first writer");
    }

    @Test
    void an_update_and_a_withdrawal_with_one_token_exactly_one_writes() throws Exception {
        String token = created();

        Throwable second = whileTheFirstHoldsItsWrite(
            () -> memory.update(PROBE, CONTESTED, token, content("first writer")),
            () -> memory.withdraw(PROBE, CONTESTED, token));

        assertRefused(second, MemoryException.Reason.CONFLICT_TOKEN_STALE);
        assertThat(current().content())
            .as("the entry the first writer changed is still there, with its change")
            .isEqualTo("first writer");
    }

    /**
     * The other order: the withdrawal holds, the update waits. The update
     * then finds no entry and is answered the single not-found, the answer a
     * caller gets for an entry withdrawn before it asked.
     */
    @Test
    void an_update_that_waited_on_a_withdrawal_finds_the_entry_gone() throws Exception {
        String token = created();

        Throwable second = whileTheFirstHoldsItsWrite(
            () -> memory.withdraw(PROBE, CONTESTED, token),
            () -> memory.update(PROBE, CONTESTED, token, content("too late")));

        assertRefused(second, MemoryException.Reason.ENTRY_ABSENT);
    }

    // -----------------------------------------------------------------------

    private String created() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            return memory.create(PROBE, CONTESTED, new Draft("decision", "as laid down", null))
                .conflictToken();
        }
    }

    private EntryView current() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            return memory.read(PROBE, CONTESTED);
        }
    }

    private static Patch content(String content) {
        return new Patch(Optional.empty(), Optional.of(content), Optional.empty());
    }

    /**
     * Runs {@code first} in a transaction left open, starts {@code second} on
     * a thread of its own, asserts that the database has it waiting on the
     * first, commits the first, and answers what the second threw, or null
     * where it returned.
     */
    private Throwable whileTheFirstHoldsItsWrite(Callable<?> first, Callable<?> second)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> secondWrite;
            try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
                QuarkusTransaction.begin();
                try {
                    first.call();
                    int holder = LockWaits.sessionOf(em);

                    secondWrite = pool.submit(() -> onItsOwnThread(second));

                    assertThat(LockWaits.waitsOn(holder, secondWrite))
                        .as("the second writer met the first one's lock; otherwise this case "
                            + "shows nothing about the two overlapping")
                        .isTrue();
                } finally {
                    QuarkusTransaction.commit();
                }
            }
            Throwable thrown = catchThrowable(() -> secondWrite.get(30, TimeUnit.SECONDS));
            return thrown instanceof ExecutionException failed ? failed.getCause() : thrown;
        } finally {
            pool.shutdownNow();
        }
    }

    private Object onItsOwnThread(Callable<?> call) throws Exception {
        var request = Arc.container().requestContext();
        request.activate();
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            return call.call();
        } finally {
            request.terminate();
        }
    }

    private static void assertRefused(Throwable second, MemoryException.Reason reason) {
        assertThat(second)
            .as("the second writer must be refused %s, not write", reason)
            .isInstanceOf(MemoryException.class);
        assertThat(((MemoryException) second).reason()).isEqualTo(reason);
    }
}
