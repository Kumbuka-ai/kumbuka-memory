package ai.kumbuka.memory.domain;

/**
 * Where an entry stands: {@code memory://<scope>/<selector>/<id>}.
 *
 * <h2>The address is the key, taken apart</h2>
 *
 * There is no second column and no allocation. The stored {@code key} is split
 * at its FIRST dot: what stands before it is the selector, what stands after
 * it — dots and all — is the id. Joining the two back gives the key, exactly.
 * That is why the split is at the first dot rather than the last: the id is
 * the part that may grow segments, and a split at the last dot would make
 * {@code decision.storage.postgres} a selector of {@code decision.storage},
 * which is not what anybody wrote.
 *
 * <p>The id is therefore always speaking. It is never the row's
 * {@code logical_id}, and no uuid ever appears in one.
 *
 * <h2>What this record does not do</h2>
 *
 * It does not validate. Whether a selector is of the permitted shape, and
 * whether the whole key is of the shape the database stores, is decided at the
 * surface where the grammar lives — this is the arithmetic of an address and
 * knows only how the two halves relate.
 */
public record EntryAddress(String scope, String selector, String id) {

    /** The scheme. It is the routing decision and never part of a path. */
    public static final String SCHEME = "memory";

    /** The separator between selector and id inside a key. */
    public static final char SEPARATOR = '.';

    public EntryAddress {
        if (scope == null || selector == null || id == null) {
            throw new IllegalArgumentException(
                "an address has a scope, a selector and an id; none of them is optional");
        }
    }

    /** The stored key this address is the decomposition of. */
    public String key() {
        return selector + SEPARATOR + id;
    }

    /**
     * The complete address, as every call takes it and every answer carries it.
     *
     * <p>Complete, never shortened: a caller hands what it reads straight back
     * to the next call (DEC-0040), and a form without its scheme or its scope
     * appears nowhere, prose included.
     */
    public String canonical() {
        return SCHEME + "://" + scope + "/" + selector + "/" + id;
    }

    @Override
    public String toString() {
        return canonical();
    }

    /**
     * The address a key denotes in a scope.
     *
     * @throws MemoryException {@code SELECTOR_ABSENT} when the key carries no
     *         dot, so that there is no selector to address it by
     */
    public static EntryAddress ofKey(String scope, String key) {
        int at = key == null ? -1 : key.indexOf(SEPARATOR);
        if (at <= 0 || at == key.length() - 1) {
            throw new MemoryException(MemoryException.Reason.SELECTOR_ABSENT,
                "a key needs a selector and an id, written '<selector>.<id>' — the part "
                    + "before the first dot addresses the kind of entry and the rest names "
                    + "this one. '" + key + "' carries no such split, so the entry would "
                    + "have no address to be read back by. Write it as, for example, "
                    + "'decision.storage-engine'.");
        }
        return new EntryAddress(scope, key.substring(0, at), key.substring(at + 1));
    }

    /**
     * Whether a key can be addressed at all.
     *
     * <p>Asked instead of catching the refusal above, because a row read out
     * of the table is not a call being refused: an entry whose key predates
     * this surface has no address, and what happens to it is a reporting
     * decision rather than a refusal to a caller.
     */
    public static boolean isAddressable(String key) {
        int at = key == null ? -1 : key.indexOf(SEPARATOR);
        return at > 0 && at < key.length() - 1;
    }
}
