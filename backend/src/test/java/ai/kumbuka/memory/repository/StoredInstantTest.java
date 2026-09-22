package ai.kumbuka.memory.repository;

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
 * <p>The first attempt at closing that here read {@link StoredInstant#now()}
 * a thousand times and asserted no sub-microsecond digit came back. Measured
 * on macOS on 2026-09-22, with the truncation removed: still green, three
 * tests, no failures. The clock never offered a finer digit, so nothing
 * observed whether one would have been dropped.
 *
 * <p>So the reduction is asked directly, with a moment supplied rather than
 * read. {@link StoredInstant#asStored} is the derivation {@code now()} itself
 * runs on, so this is not a second path built to be testable — it is the same
 * one, given an input the platform cannot flatten first.
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
     * {@code now()} runs the clock through that same derivation.
     *
     * <p>Green on a microsecond platform whether or not it does, which is why
     * it is not the gate — it is here so that a future {@code now()} which
     * stopped calling {@code asStored} is at least red on Linux without
     * needing a database.
     */
    @Test
    void now_carries_no_digit_the_column_would_drop() {
        for (int reading = 0; reading < 1000; reading++) {
            assertThat(StoredInstant.now().getNano() % 1000).isZero();
        }
    }

    /** It is still a clock: a later reading is later. */
    @Test
    void it_still_moves_forward() throws InterruptedException {
        Instant first = StoredInstant.now();
        Thread.sleep(5);
        assertThat(StoredInstant.now()).isAfter(first);
    }
}
