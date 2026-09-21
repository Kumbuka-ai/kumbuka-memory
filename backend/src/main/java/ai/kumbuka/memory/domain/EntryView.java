package ai.kumbuka.memory.domain;

import java.time.Instant;

/**
 * One entry, as a caller reads it.
 *
 * <p>The shape a verb answers with, and the shape a stale-token refusal
 * carries (DEC-0041): both are produced by the same projection, because a
 * refusal that showed a different set of fields from the read it tells the
 * caller to repeat would be describing a second object.
 *
 * <h2>No uuid, anywhere</h2>
 *
 * Not one field here carries a generated identifier. The address is speaking,
 * the scope is a slug, the type and the state are names, and the row's
 * {@code logical_id} does not appear. The entity is where the uuids live and
 * the entity does not leave the transaction.
 *
 * <h2>Why the conflict token sits beside the fields and not in them</h2>
 *
 * It guards the write and is not written (DEC-0040). Inside the field map it
 * would be indistinguishable from a value of the entry, and a caller handing
 * the map back would be handing back a field the entry does not have.
 */
public record EntryView(
    EntryAddress address,
    String scope,
    String key,
    String type,
    String content,
    String reference,
    String state,
    boolean isPrivate,
    Instant createdAt,
    Instant updatedAt,
    String conflictToken,
    boolean writable) {

    /**
     * The projection of a stored entry, for a caller who may already see it.
     *
     * @param scopeSlug the scope's name, resolved — never the stored uuid
     * @param writable  whether this caller may write in that scope, which is
     *                  what decides the calls the answer offers next
     */
    public static EntryView of(Memory entry, String scopeSlug, boolean writable) {
        return new EntryView(
            EntryAddress.ofKey(scopeSlug, entry.key),
            scopeSlug,
            entry.key,
            entry.type,
            entry.content,
            entry.reference,
            entry.state,
            entry.isPrivate,
            entry.createdAt,
            entry.updatedAt,
            tokenOf(entry),
            writable);
    }

    /**
     * The entry's conflict token: the moment it last changed.
     *
     * <p>One value, derived in one place. A token computed at the answer and
     * again at the refusal would be two derivations that agree until the day
     * one of them is changed.
     */
    public static String tokenOf(Memory entry) {
        return entry.updatedAt.toString();
    }
}
