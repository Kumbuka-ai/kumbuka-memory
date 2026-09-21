package ai.kumbuka.memory.domain;

/**
 * Who is acting.
 *
 * <p>One field, and a type around it rather than a bare string. The subject is
 * passed to the scope directory, written into {@code owner_subject}, and
 * compared against it on every private read; a {@code String} in all three
 * places is a value any other string can be handed to by mistake, and the
 * mistake is an authorship record with the wrong name on it.
 *
 * <p>There is no capacity here and no role. ADR-0034 has each recipient derive
 * the acting identity from its own validated security identity, and this
 * service's permissions come from the platform's read contract rather than
 * from a claim in the token — so a role would be a second answer to a question
 * the contract already answers.
 */
public record Actor(String subject) {

    public Actor {
        if (subject == null || subject.isBlank()) {
            throw new MemoryException(MemoryException.Reason.ACTOR_UNKNOWN,
                "the token authenticated but names no subject to act as. Authorship is "
                    + "derived from the token's stable subject and never from the call, "
                    + "so there is nothing to record as the author and nothing to compare "
                    + "a private entry's owner against.");
        }
    }
}
