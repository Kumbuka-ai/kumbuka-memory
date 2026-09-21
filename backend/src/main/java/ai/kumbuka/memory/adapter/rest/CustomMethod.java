package ai.kumbuka.memory.adapter.rest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The verbs this surface expresses in colon notation, and the depth each acts
 * at.
 *
 * <h2>Why colon notation at all</h2>
 *
 * A verb written as a trailing path segment could not be told apart from a
 * further id segment. In this service an id is always one segment — a key
 * holds no slash — so the ambiguity is narrower than in the sibling services,
 * but it is not gone: {@code /api/est/decision/storage/withdraw} is a
 * perfectly good address for an entry with the key
 * {@code decision.storage} under a scope, and nothing in the path says which
 * reading is meant. The colon appears in no address part, which is what makes
 * it decidable, and using the same notation as the siblings means a caller
 * learns one form for the platform.
 *
 * <h2>Why it is a table rather than one annotation per verb</h2>
 *
 * Measured against Quarkus REST 3.33.2 in the dispatch service on 2026-09-01:
 * a path template takes the whole segment it appears in, so
 * {@code @Path("{id}:withdraw")} registers as {@code {id}} and matches
 * anything, with the verb inside the variable. Routes per verb are not
 * available from the framework and would silently collapse into one. What is
 * registered instead is one binding per address depth, and the verb is split
 * off here. The outward form is unchanged and it is the outward form that is
 * probed.
 */
public enum CustomMethod {

    /**
     * The compound read of a whole scope, at scope depth.
     *
     * <p>At scope depth because that is what it reads: not one entry and not
     * one selector's worth, but everything in force in the scope. POST rather
     * than GET because it takes a body — the type override — and a body on a
     * GET is a thing intermediaries are entitled to drop.
     */
    DIGEST("digest", Depth.SCOPE),

    /** Taking one entry out of force, at item depth. */
    WITHDRAW("withdraw", Depth.ITEM);

    /** The address depth a verb acts at. */
    public enum Depth {
        /** {@code /api/{scope}} — a whole scope. */
        SCOPE,
        /** {@code /api/{scope}/{selector}} — one selector's entries. */
        COLLECTION,
        /** {@code /api/{scope}/{selector}/{id}} — one entry. */
        ITEM
    }

    /** The separator. It appears in no address part, which is the whole point. */
    public static final char SEPARATOR = ':';

    private final String verb;
    private final Depth depth;

    CustomMethod(String verb, Depth depth) {
        this.verb = verb;
        this.depth = depth;
    }

    public String verb() {
        return verb;
    }

    public Depth depth() {
        return depth;
    }

    /** The verbs of one depth. Empty for a depth that carries none. */
    public static List<CustomMethod> at(Depth depth) {
        return Arrays.stream(values()).filter(m -> m.depth == depth).toList();
    }

    /**
     * Splits a path segment into the address part and the verb, where there is
     * one.
     *
     * <p>Split at the <em>last</em> colon rather than the first, because the
     * address part is the thing that may grow and the verb is the thing that
     * may not.
     *
     * @return the split, or empty when the segment carries no colon at all —
     *         which is a plain address and not a malformed verb
     */
    public static Optional<Split> split(String segment, Depth depth) {
        int at = segment.lastIndexOf(SEPARATOR);
        if (at < 0) {
            return Optional.empty();
        }
        return Optional.of(new Split(
            segment.substring(0, at),
            segment.substring(at + 1),
            at(depth).stream()
                .filter(m -> m.verb.equals(segment.substring(at + 1)))
                .findFirst()
                .orElse(null)));
    }

    /**
     * A segment taken apart: what it addresses, and which verb it names.
     *
     * @param address the part before the colon
     * @param verb    the part after it, as written
     * @param method  the verb of this depth it names, or null for one this
     *                depth does not carry — which is a 405 and not a 404: the
     *                address resolved, the verb did not exist
     */
    public record Split(String address, String verb, CustomMethod method) {

        public boolean isKnown() {
            return method != null;
        }
    }
}
