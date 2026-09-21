package ai.kumbuka.memory.domain;

/**
 * What a {@code query} was narrowed to, and how much of it to answer.
 *
 * <p>A record rather than a parameter list: the predicates are the thing that
 * grows, and a verb whose signature grew with them would have every caller
 * edited for each one.
 *
 * @param type     the kind of entry, or null for every kind
 * @param text     a substring of the content, case-insensitively, or null
 * @param after    the cursor a previous page handed out, or null for the first
 * @param pageSize how many entries this page carries at most
 */
public record QueryFilter(EntryType type, String text, String after, int pageSize) {

    /** The page size a caller gets without asking. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * The largest page this verb serves.
     *
     * <p>A limit and not a cap: a request for more is refused and named, never
     * quietly reduced. A silently reduced page is what makes a caller believe
     * it has seen everything.
     */
    public static final int MAX_PAGE_SIZE = 200;

    public QueryFilter {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new MemoryException(MemoryException.Reason.PAGE_SIZE_REJECTED,
                "a page carries between 1 and " + MAX_PAGE_SIZE + " entries and "
                    + pageSize + " is outside that. The request is refused rather than "
                    + "reduced to the limit: a page silently made smaller than the one "
                    + "asked for is how a caller comes to believe it has seen everything. "
                    + "Ask for at most " + MAX_PAGE_SIZE + " and page with the cursor "
                    + "the answer carries.");
        }
    }
}
