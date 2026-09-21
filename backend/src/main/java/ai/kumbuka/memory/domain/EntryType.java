package ai.kumbuka.memory.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The six kinds of entry, and the order a digest reports them in.
 *
 * <h2>Why an enum where the database has a check constraint</h2>
 *
 * The constraint is the backstop and this is the vocabulary. What the enum
 * buys that the constraint cannot is exhaustiveness at compile time: the
 * digest reports a section per type, and a seventh type added to the
 * constraint alone would produce a digest that silently omits it. A switch
 * over this enum with no {@code default} is what turns that into a build
 * failure.
 *
 * <h2>The declaration order IS the digest order</h2>
 *
 * Constraints first, then what was decided, then how it is done, then the
 * words, then where things stand, and the unsettled questions last. A reader
 * meets the bounds before the choices made inside them, and meets what is
 * still open only after everything that is settled — which is the whole
 * difference between a digest and a list.
 *
 * <p>{@link #values()} returns constants in declaration order, which is a
 * guarantee of the language rather than of this class, so the order is not
 * restated as a second list somewhere.
 */
public enum EntryType {

    CONSTRAINT("constraint"),
    DECISION("decision"),
    CONVENTION("convention"),
    GLOSSARY("glossary"),
    STATUS("status"),
    OPEN_QUESTION("open_question");

    /** The name on the wire and in the database. Never the enum constant's own. */
    private final String wire;

    EntryType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** The six, in the digest's order. */
    public static List<EntryType> inDigestOrder() {
        return List.of(values());
    }

    /** The six wire names, for a refusal that has to say what was expected. */
    public static List<String> wireNames() {
        return Arrays.stream(values()).map(EntryType::wire).toList();
    }

    /**
     * The type a wire name denotes, or empty.
     *
     * <p>Empty rather than a refusal: whether an unknown type is a malformed
     * call or a malformed row is not this class's to decide, and the two get
     * different refusals from the callers that do decide it.
     */
    public static Optional<EntryType> of(String wire) {
        return Arrays.stream(values()).filter(t -> t.wire.equals(wire)).findFirst();
    }
}
