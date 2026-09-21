package ai.kumbuka.memory.domain;

import java.util.List;

/**
 * Everything in force in a scope, and an honest account of what is not here.
 *
 * <h2>The summary covers all six types; the content covers the chosen ones</h2>
 *
 * A digest that reported only what it carries could not be distinguished from
 * a complete one. The summary therefore answers for every type the service
 * knows — count and token estimate — whatever the selection is, so that a
 * reader can see that there are eleven open questions in this scope which this
 * digest does not carry, and can ask for them.
 *
 * <p>There is no truncation anywhere in it. The chosen types are loaded whole,
 * and the answer says how large that is rather than deciding for the caller
 * that it is too large.
 *
 * @param scope         the scope this digest was asked for
 * @param scopes        every scope it actually covers, by name
 * @param selectedTypes the types whose content is carried
 * @param summary       count and token estimate per type, all six, in order
 * @param totalEntries  how many entries the summary counts together
 * @param totalTokens   the summed token estimate of all six types
 * @param sections      the content, by type, in the digest's order
 * @param unaddressable how many entries this surface cannot show at all
 */
public record DigestView(
    String scope,
    List<String> scopes,
    List<String> selectedTypes,
    List<TypeTally> summary,
    long totalEntries,
    long totalTokens,
    List<Section> sections,
    long unaddressable) {

    /**
     * The divisor that turns characters into an estimate of tokens.
     *
     * <p>Four, and it is an estimate and named one. The point of the number is
     * that a caller can decide whether a digest will fit in what it is about
     * to do with it, and for that a rough figure computed the same way every
     * time is worth more than an exact one that costs a tokeniser.
     */
    public static final int CHARACTERS_PER_TOKEN = 4;

    /** The estimate for a given number of characters. One derivation, one place. */
    public static long tokensFor(long characters) {
        return characters / CHARACTERS_PER_TOKEN;
    }

    /**
     * How much of one type there is.
     *
     * @param type    the type, by name
     * @param count   how many entries of it are in force in the covered scopes
     * @param tokens  the estimate for their content together
     * @param carried whether this digest carries their content
     */
    public record TypeTally(String type, long count, long tokens, boolean carried) {
    }

    /** The entries of one type, oldest first. */
    public record Section(String type, List<Entry> entries) {
    }

    /**
     * One entry in a digest.
     *
     * <p>No reference. A digest is what is in force, read whole; a provenance
     * pointer is for the caller that has the entry in front of it and wants to
     * know where it came from, and carrying one per entry would add a line of
     * noise to every entry of every digest for a question almost nobody is
     * asking at that moment.
     */
    public record Entry(EntryAddress address, String key, String content) {
    }
}
