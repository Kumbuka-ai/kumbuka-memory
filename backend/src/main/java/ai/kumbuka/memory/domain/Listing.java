package ai.kumbuka.memory.domain;

import java.util.List;

/**
 * One page of a query, and everything needed to know it is not the whole set.
 *
 * <p>Nothing here is silently applied. The page size that was actually served,
 * the total the predicate matches, whether there is more, the cursor that
 * fetches it, and the order the entries are in all travel with the entries —
 * because a listing that carried only the entries would be indistinguishable
 * from a complete answer, and a caller cannot ask a question it does not know
 * it has.
 *
 * @param entries      the page, in {@link #order}
 * @param total        how many entries the predicate matches, cursor excluded
 * @param truncated    whether more entries follow this page
 * @param cursor       what to pass as {@code after} for the next page, or null
 * @param pageSize     how many entries this page was allowed to carry
 * @param unaddressable how many entries of the scope this surface cannot show
 */
public record Listing(List<EntryView> entries, long total, boolean truncated,
                      String cursor, int pageSize, long unaddressable) {

    /**
     * The order, named in the answer.
     *
     * <p>Named rather than merely deterministic: a caller paging with a cursor
     * is relying on an order, and one it has to infer from two pages is one it
     * will infer wrongly the first time a page is homogeneous.
     */
    public static final String KEY_ASCENDING = "key ascending";

    public String order() {
        return KEY_ASCENDING;
    }
}
