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
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A changed row whose change did not pass {@link Memory#changedAt} is refused
 * when it is flushed, so the conflict token moves on every write that changes
 * an entry, by mechanism rather than by convention (C10.1).
 *
 * <p>The unstamped write is made the way a careless one would be: the entry
 * is read in a transaction, a field is set, and the persistence context is
 * flushed. A create, an update through the verb and an update that changes
 * nothing are the writes the service makes, and none of them is refused.
 *
 * <p>RED STATE, observed on memory {@code c3c1104} (before the stamp was
 * enforced): the unstamped change was flushed and committed, the content read
 * back was the changed one and the token was the old one, so the first case
 * failed at its first assertion; the second case passed there as well.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class UnstampedWriteIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final Actor PROBE = new Actor(SubstrateDatabaseResource.PROBE_SUBJECT);
    private static final String KEY = "decision.stamped";
    private static final EntryAddress ENTRY =
        EntryAddress.ofKey(SubstrateDatabaseResource.PROBE_SCOPE_SLUG, KEY);

    @Inject MemoryService memory;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    private AutoCloseable binding;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyScope() {
        SurfaceFixture.clearEntries();
        binding = tenantContext.bind(TENANT);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void a_change_that_did_not_pass_the_stamp_is_refused_at_the_flush() {
        EntryView created = create("as laid down");

        Throwable thrown = catchThrowable(() -> inOneTransaction(() -> {
            Memory row = stored();
            row.content = "changed behind the stamp";
            em.flush();
        }));

        assertThat(thrown)
            .as("a changed row whose write did not pass changedAt() is refused at the flush")
            .isNotNull()
            .hasStackTraceContaining("written without changedAt()");
        EntryView after = memory.read(PROBE, ENTRY);
        assertThat(after.content())
            .as("and nothing of it is stored")
            .isEqualTo("as laid down");
        assertThat(after.conflictToken()).isEqualTo(created.conflictToken());
    }

    @Test
    void create_update_and_a_write_that_changes_nothing_pass_the_flush() {
        EntryView created = create("first");
        assertThat(created.content()).as("a create is a persist, not a refused update")
            .isEqualTo("first");

        EntryView changed =
            memory.update(PROBE, ENTRY, created.conflictToken(), content("second"));
        assertThat(changed.content()).as("an update through the verb is stamped")
            .isEqualTo("second");
        assertThat(changed.conflictToken()).isNotEqualTo(created.conflictToken());

        EntryView same = memory.update(PROBE, ENTRY, changed.conflictToken(), content("second"));
        assertThat(same.conflictToken())
            .as("a write that changes nothing flushes nothing and keeps its token")
            .isEqualTo(changed.conflictToken());

        EntryView again = memory.update(PROBE, ENTRY, same.conflictToken(), content("third"));
        assertThat(again.content())
            .as("and the stamp of one write does not stand in for the next")
            .isEqualTo("third");
        assertThat(memory.read(PROBE, ENTRY).content()).isEqualTo("third");
    }

    // -----------------------------------------------------------------------

    private EntryView create(String content) {
        return memory.create(PROBE, ENTRY, new Draft("decision", content, null));
    }

    private static Patch content(String content) {
        return new Patch(Optional.empty(), Optional.of(content), Optional.empty());
    }

    /**
     * The stored row, read in the caller's transaction. The read through the
     * verb first binds the tenant onto the transaction, which the row-level
     * policy needs before the entity manager may see the row.
     */
    private Memory stored() {
        memory.read(PROBE, ENTRY);
        return em.createQuery("SELECT m FROM Memory m WHERE m.key = :key", Memory.class)
            .setParameter("key", KEY)
            .getSingleResult();
    }

    private static void inOneTransaction(Runnable work) {
        QuarkusTransaction.begin();
        try {
            work.run();
        } catch (RuntimeException failed) {
            QuarkusTransaction.rollback();
            throw failed;
        }
        QuarkusTransaction.commit();
    }
}
