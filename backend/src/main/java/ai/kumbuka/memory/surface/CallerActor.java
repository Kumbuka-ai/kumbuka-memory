package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Actor;
import ai.kumbuka.memory.domain.MemoryException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Derives the acting identity from the validated token, and from nothing else.
 *
 * <p>ADR-0034: every recipient validates the token itself and derives the
 * acting identity from its own validated security identity, never from a field
 * of the call. A surface that accepted an author would let a caller sign
 * somebody else's name to an entry — and in this service the author is not
 * decoration: it is what a private entry's visibility is decided on, so a
 * forged one would be a way to read another person's memory.
 *
 * <p>There is no capacity and no role here, and the absence is deliberate.
 * What this caller may do comes from the platform's read contract, per scope,
 * for this subject. A role in the token would be a second answer to a question
 * the contract already answers, and the two would eventually disagree.
 */
@RequestScoped
public class CallerActor {

    /**
     * The one refusal on this surface worth WARN, and why it is the exception.
     *
     * <p>Every other refusal is a caller being told no, which is the surface
     * working. This one is not about the call: a token that authenticated and
     * carries no subject is a realm misconfiguration — the principal claim is
     * mis-pinned or the claim is absent — no caller can fix it, and nothing
     * else would say so. The caller sees a refusal and falls silent, and the
     * realm stays wrong.
     *
     * <p>The subject is not logged. Which token it was belongs to the audit
     * log under its own rules; what an operator needs from this line is that
     * the realm is handing out tokens this service cannot act on at all.
     */
    private static final Logger LOG = Logger.getLogger(CallerActor.class);

    @Inject SecurityIdentity identity;

    /**
     * @return the actor this request acts as
     * @throws MemoryException with {@code ACTOR_UNKNOWN} when the validated
     *         identity carries no subject
     */
    public Actor current() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null
            || identity.getPrincipal().getName() == null
            || identity.getPrincipal().getName().isBlank()) {
            LOG.warnf("a token authenticated with no subject to act as: %s",
                MemoryException.Reason.ACTOR_UNKNOWN);
            throw new MemoryException(MemoryException.Reason.ACTOR_UNKNOWN,
                "this token names no subject to act as. The principal is pinned to the "
                    + "token's 'sub' claim, which is stable; a realm that issues a token "
                    + "without one gives this service nothing to record as the author of "
                    + "a write and nothing to compare a private entry's owner against.");
        }
        return new Actor(identity.getPrincipal().getName());
    }
}
