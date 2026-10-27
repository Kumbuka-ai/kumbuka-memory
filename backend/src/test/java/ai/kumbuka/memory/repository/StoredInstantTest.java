/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.Memory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The precision rule, in the one form that can fail on any platform.
 *
 * <h2>Why the derivation is asked and not the clock</h2>
 *
 * {@code ConflictTokenPrecisionIT} is the acceptance, against a real column
 * and through the surface. Its red state can only be reached on Linux: a
 * macOS clock already ticks in microseconds, so with the fix removed it stays
 * green there. That is not a weakness of that probe — it is the shape of the
 * defect — but it does mean the gate is one a developer cannot observe
 * failing locally.
 *
 * <p>The first attempt at closing that here read the service's clock
 * a thousand times and asserted no sub-microsecond digit came back. Measured
 * on macOS on 2026-09-22, with the truncation removed: still green, three
 * tests, no failures. The clock never offered a finer digit, so nothing
 * observed whether one would have been dropped.
 *
 * <p>So the reduction is asked directly, with a moment supplied rather than
 * read. {@link StoredInstant#asStored} is the derivation every stamp of an
 * entry runs on ({@code Memory.laidDownAt} and {@code Memory.changedAt}, fed
 * from the injected clock), so this is not a second path built to be
 * testable — it is the same one, given an input the platform cannot flatten
 * first.
 */
class StoredInstantTest {

    /**
     * RED STATE, observed 2026-09-22 on macOS / Temurin 21.0.12, by replacing
     * the body of {@code asStored} with {@code return moment}:
     * {@code Tests run: 5, Failures: 2} — this case and
     * {@code the_declared_precision_is_the_one_that_is_applied}. The same
     * removal left the earlier, clock-reading version of this class green on
     * the same machine.
     */
    @Test
    void a_finer_moment_is_reduced_to_what_the_column_keeps() {
        Instant nanosecondPrecise = Instant.ofEpochSecond(1_774_000_000L, 123_456_789);

        assertThat(StoredInstant.asStored(nanosecondPrecise).getNano())
            .as("a moment written into a timestamptz column may carry no "
                + "sub-microsecond digit: the column rounds it away, and a token derived "
                + "from the in-memory value would then name a value the stored row never "
                + "had — which is refused as CONFLICT_TOKEN_STALE on the caller's next "
                + "write")
            .isEqualTo(123_456_000);
    }

    /** A moment already at the column's precision is handed back unchanged. */
    @Test
    void a_moment_the_column_can_hold_is_left_alone() {
        Instant microsecondPrecise = Instant.ofEpochSecond(1_774_000_000L, 123_456_000);

        assertThat(StoredInstant.asStored(microsecondPrecise))
            .as("the reduction is a reduction and not a rounding to something else: a "
                + "value the column keeps whole comes back whole")
            .isEqualTo(microsecondPrecise);
    }

    /** The declared precision is the one the derivation applies. */
    @Test
    void the_declared_precision_is_the_one_that_is_applied() {
        assertThat(StoredInstant.COLUMN_PRECISION).isEqualTo(ChronoUnit.MICROS);

        Instant nanosecondPrecise = Instant.ofEpochSecond(1_774_000_000L, 999_999_999);
        assertThat(StoredInstant.asStored(nanosecondPrecise))
            .isEqualTo(nanosecondPrecise.truncatedTo(StoredInstant.COLUMN_PRECISION));
    }

    /**
     * The stamps of an entry run the moment through that same derivation.
     *
     * <p>Since the time comes from the injected clock, a stamp is handed its
     * moment, so a nanosecond one is asked of it directly: a stamp that
     * stopped calling {@code asStored} is red here on any platform.
     */
    @Test
    void a_stamp_carries_no_digit_the_column_would_drop() {
        Instant nanosecondPrecise = Instant.ofEpochSecond(1_774_000_000L, 123_456_789);
        Memory entry = new Memory();

        entry.laidDownAt(nanosecondPrecise);
        assertThat(entry.createdAt.getNano()).isEqualTo(123_456_000);
        assertThat(entry.updatedAt).isEqualTo(entry.createdAt);

        entry.changedAt(nanosecondPrecise.plusSeconds(1));
        assertThat(entry.updatedAt.getNano()).isEqualTo(123_456_000);
    }

    /**
     * It is the moment it is given, and it never falls back.
     *
     * <p>The stamp of a change is {@code max(at, previous + 1 µs)} (C10.1): a
     * later moment is taken as it is, and one that is not later than the last
     * stamp moves the stamp on by one microsecond rather than back to it, so
     * no two changes of one entry share a token.
     */
    @Test
    void a_change_takes_its_moment_and_never_falls_back() {
        Instant laid = Instant.ofEpochSecond(1_774_000_000L, 123_456_000);
        Memory entry = new Memory();
        entry.laidDownAt(laid);

        entry.changedAt(laid.plusSeconds(60));
        assertThat(entry.updatedAt).as("a later moment is taken")
            .isEqualTo(laid.plusSeconds(60));

        entry.changedAt(laid);
        assertThat(entry.updatedAt).as("an earlier one moves the stamp on by one microsecond")
            .isEqualTo(laid.plusSeconds(60).plus(1, ChronoUnit.MICROS));

        entry.changedAt(laid.plusSeconds(60).plusNanos(500));
        assertThat(entry.updatedAt).as("and so does one inside the same microsecond")
            .isEqualTo(laid.plusSeconds(60).plus(2, ChronoUnit.MICROS));
        assertThat(entry.createdAt).as("no change moves the creation").isEqualTo(laid);
    }
}
