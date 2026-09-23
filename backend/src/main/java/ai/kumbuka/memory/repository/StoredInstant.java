package ai.kumbuka.memory.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A moment in the precision the database keeps it in.
 *
 * <h2>The defect this exists to close</h2>
 *
 * The conflict token is the entry's change time rendered as a string
 * ({@code EntryView.tokenOf}), so the token a write answers with is whatever
 * the change time held in memory at that moment. {@code Instant.now()} takes
 * its resolution from the platform clock: microseconds on macOS, NANOSECONDS
 * on Linux — and a {@code timestamptz} column keeps microseconds. An
 * application-set change time on Linux therefore carried three digits the
 * stored row never had, the write answered with a token for a value that was
 * never in the table, and the caller's next write was refused
 * {@code CONFLICT_TOKEN_STALE} against a token it had just been handed.
 *
 * <p>It was invisible on a developer's machine and certain in production,
 * which is the shape of defect a probe has to be written for deliberately.
 *
 * <h2>Why the precision is fixed here rather than at each assignment</h2>
 *
 * There is one assignment today and there will be more. A rule applied at
 * each of them is a rule that holds until somebody adds the next one, and the
 * omission would not fail anywhere near the line that caused it — it would
 * fail at a caller's SECOND write, in another service, against a token this
 * one handed out. So the precision is a property of writing a moment at all,
 * stated once, with the column it belongs to named beside it.
 *
 * <h2>Why truncation at the write and not a read-back</h2>
 *
 * Both close the gap. Truncation decides what goes INTO the row; a read-back
 * would let the database round the value — {@code timestamptz} rounds rather
 * than truncates — and then discover afterwards what the rounding produced.
 * The value written would still be one the application did not choose, and it
 * would cost a second statement on every update to learn it. Truncating first
 * means there is nothing left to round.
 *
 * <p>{@link #COLUMN_PRECISION} is an assertion about the column, not a
 * setting: {@code ConflictTokenPrecisionIT} compares the token of every
 * writing answer against the token of a read that follows it, so a column
 * that kept a different precision would turn that probe red rather than
 * letting this constant quietly disagree with the schema.
 */
public final class StoredInstant {

    /**
     * The resolution PostgreSQL keeps a {@code timestamptz} in.
     *
     * <p>Microseconds, for every column of this schema that carries a moment:
     * {@code memory.created_at}, {@code memory.updated_at},
     * {@code memory.valid_from}, {@code memory.valid_until}.
     */
    public static final ChronoUnit COLUMN_PRECISION = ChronoUnit.MICROS;

    private StoredInstant() {
    }

    /** Now, as the row will hold it. */
    public static Instant now() {
        return asStored(Instant.now());
    }

    /**
     * A moment reduced to what the column keeps of it.
     *
     * <p>Separate from {@link #now()}, and the separation is what makes the
     * rule testable at all. Asserted through {@code now()} the reduction is
     * invisible wherever the platform clock is already coarse — measured on
     * macOS on 2026-09-22: with the truncation removed, a probe reading
     * {@code now()} a thousand times stayed green, because that clock ticks
     * in microseconds and there was never a finer digit to drop. A probe that
     * cannot fail on the machine a developer runs it on is not a gate there.
     *
     * <p>Given a moment of its own the derivation answers for itself on any
     * platform, which is what {@code StoredInstantTest} asks it.
     */
    public static Instant asStored(Instant moment) {
        return moment.truncatedTo(COLUMN_PRECISION);
    }
}
