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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conflict token is the entry's {@code updated_at} at microsecond
 * precision, written by the application from the injected clock as {@code
 * max(now, previous + 1 µs)} on every write that changes the entry, including
 * the one that lays it down, and left as it is by a write that changes nothing
 * (C10.1, C20.1, DEC-0041).
 *
 * <p>The clock is the test's: {@link SettableClock} holds one instant, so two
 * writes fall into the same microsecond by construction and the expected
 * tokens are written here from that instant, not read from the service. Each
 * token is also compared against a read that follows, so what is asserted is
 * the stored value and not only the answer.
 *
 * <p>RED STATE, observed on the tree before the clock (memory {@code
 * 482a26b}): the token of a new entry was the database's transaction time and
 * that of an update the platform clock, so the first assertion of each case
 * that sets the clock failed; the write that changes nothing kept its token
 * there too, as it must.
 */
@QuarkusTest
@TestProfile(SettableClock.Profile.class)
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConflictTokenIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final Actor PROBE = new Actor(SubstrateDatabaseResource.PROBE_SUBJECT);
    private static final EntryAddress ENTRY =
        EntryAddress.ofKey(SubstrateDatabaseResource.PROBE_SCOPE_SLUG, "decision.clocked");

    private static final Instant T = Instant.parse("2031-03-04T05:06:07.123456Z");

    @Inject MemoryService memory;
    @Inject TenantContext tenantContext;
    @Inject SettableClock clock;

    private AutoCloseable binding;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEmptyScope() {
        SurfaceFixture.clearEntries();
        binding = tenantContext.bind(TENANT);
        clock.set(T);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void two_writes_in_the_same_microsecond_yield_two_tokens() {
        EntryView created = create("first");
        assertThat(created.conflictToken())
            .as("the token of a new entry is the clock's instant at the microsecond")
            .isEqualTo(token(T));
        assertThat(created.createdAt())
            .as("and the entry was laid down at that instant, by the application")
            .isEqualTo(T);

        EntryView once = rewrite(created, "second");
        EntryView twice = rewrite(once, "third");

        assertThat(once.conflictToken())
            .as("a write in the same microsecond moves the token by one microsecond")
            .isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(twice.conflictToken()).isEqualTo(token(T.plus(2, ChronoUnit.MICROS)));
        assertThat(read().conflictToken())
            .as("and the row keeps the token the last write answered with")
            .isEqualTo(twice.conflictToken());
        assertThat(read().createdAt()).as("which moved no creation time").isEqualTo(T);
    }

    @Test
    void the_token_follows_the_clock_and_never_returns_to_an_earlier_value() {
        EntryView created = create("first");

        Instant later = T.plusSeconds(60);
        clock.set(later);
        EntryView forward = rewrite(created, "second");
        assertThat(forward.conflictToken()).as("the clock moved on, so does the token")
            .isEqualTo(token(later));

        clock.set(T.minusSeconds(3600));
        EntryView back = rewrite(forward, "third");
        assertThat(back.conflictToken())
            .as("a clock set back moves the token past the last one, never to the clock")
            .isEqualTo(token(later.plus(1, ChronoUnit.MICROS)));
        assertThat(read().conflictToken()).isEqualTo(back.conflictToken());
    }

    @Test
    void a_write_that_changes_nothing_keeps_the_token() {
        EntryView created = create("unchanged");
        clock.set(T.plusSeconds(5));

        EntryView same = rewrite(created, "unchanged");

        assertThat(same.conflictToken())
            .as("the content written is the content stored: nothing changed, neither does "
                + "the token")
            .isEqualTo(created.conflictToken());
        assertThat(read().conflictToken()).isEqualTo(created.conflictToken());
    }

    @Test
    void a_moment_finer_than_the_column_is_cut_to_it_before_it_becomes_a_token() {
        clock.set(T.plusNanos(789));

        EntryView created = create("first");

        assertThat(created.conflictToken())
            .as("the clock may tick in nanoseconds; the token is what the column keeps")
            .isEqualTo(token(T))
            .isEqualTo(read().conflictToken());
    }

    // -----------------------------------------------------------------------

    private EntryView create(String content) {
        return memory.create(PROBE, ENTRY, new Draft("decision", content, null));
    }

    private EntryView rewrite(EntryView entry, String content) {
        return memory.update(PROBE, ENTRY, entry.conflictToken(),
            new Patch(Optional.empty(), Optional.of(content), Optional.empty()));
    }

    private EntryView read() {
        return memory.read(PROBE, ENTRY);
    }

    private static String token(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
