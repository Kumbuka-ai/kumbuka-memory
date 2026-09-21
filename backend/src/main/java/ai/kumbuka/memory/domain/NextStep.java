package ai.kumbuka.memory.domain;

/**
 * One call the caller may make on an object from the state it is now in.
 *
 * <p>Computed for the calling identity, from the object's state and what the
 * platform's read contract says this caller may do in its scope — the same
 * answer that permits and refuses the call itself (DEC-0040). A second table
 * kept only for the answer would drift from the one that decides what
 * succeeds, and a listed call that is then refused is exactly what the rule
 * exists to prevent.
 *
 * <p>Not a value of the object: two callers reading the same entry see
 * different lists, so it travels beside {@code fields} and never inside it.
 *
 * @param call what to send, with the complete address, ready to be handed back
 * @param does one sentence saying what it does
 */
public record NextStep(String call, String does) {
}
