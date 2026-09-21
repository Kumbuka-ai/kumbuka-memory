package ai.kumbuka.memory.domain;

/**
 * What withdrawing an entry does — the one thing about this service that an
 * edition may replace.
 *
 * <h2>The seam, and what it is for</h2>
 *
 * The community edition destroys the row: no tombstone, no version history, no
 * trace (the operator's decision of 2026-09-19). An enterprise edition has an
 * obligation the community edition does not — an audited record of what was
 * removed and when — and meeting it means leaving something behind, which is
 * the opposite behaviour under the same verb.
 *
 * <p>So the verb calls this and nothing else decides. An edition supplies its
 * own implementation as a CDI alternative from its own module, and
 * {@code kumbuka-memory} is not edited: the verb, the refusals, the check
 * order and the answer shape are the same in both editions, and only what
 * happens to the row differs.
 *
 * <h2>Why the outcome is answered rather than assumed</h2>
 *
 * A caller that withdrew an entry needs to know whether it is gone or merely
 * retired — the two lead to different next steps, and in the edition that
 * tombstones the address is still occupied afterwards. An implementation that
 * only performed the act would leave the verb to guess, and the verb would
 * guess whichever edition it was written in.
 */
public interface Withdrawal {

    /**
     * Withdraws an entry that has already passed every check.
     *
     * <p>Called inside the verb's transaction, with the entry attached. The
     * implementation decides what happens to the row and states which of the
     * two it did.
     */
    Outcome withdraw(Memory entry);

    /**
     * What became of the row.
     *
     * <p>What it does NOT say is whether the address is free afterwards. That
     * is read back from the table after the act, because it depends on how an
     * implementation retires a row and not on which of these two words it
     * chose — and because the answer's next steps are computed from it, so a
     * guess here would become a call offered to a caller and then refused.
     */
    enum Outcome {
        /** The row is gone. Nothing of the entry is kept. */
        DESTROYED,
        /** A record of the entry is kept, out of force. */
        TOMBSTONED
    }
}
