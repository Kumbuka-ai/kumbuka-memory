package ai.kumbuka.memory.fixture.allowed;

import org.jboss.logging.Logger;

/**
 * The two ways a failure may be logged, for the guard to pass.
 *
 * <p>The typed reason of a refusal, read off the exception that carries it:
 * a constant out of a closed set. And the shape of a failure — its type and
 * its frames — assembled into a local first, without a message.
 *
 * <p>It lives in the test sources and is wired into nothing.
 */
public class AllowedFailureLogFixture {

    private static final Logger LOG = Logger.getLogger(AllowedFailureLogFixture.class);

    /** Stands in for a typed refusal, so the fixture needs no domain import. */
    public static class RefusalException extends RuntimeException {

        public enum Reason { SCOPE_LOCKED }

        public Reason reason() {
            return Reason.SCOPE_LOCKED;
        }
    }

    public void theTypedReason(RefusalException refused) {
        LOG.debugf("refused: %s", refused.reason());
    }

    /**
     * The typed reason again, behind a literal holding a comma and the
     * variable's own name. Split at that comma, the literal would fall apart
     * and its words would be read as code.
     */
    public void theTypedReasonBehindACommaInALiteral(RefusalException refused) {
        LOG.debugf("refused, refused: %s", refused.reason());
    }

    public void theShapeOfAFailure(RuntimeException failure) {
        String frames = failure.getClass().getName();
        LOG.errorf("unexpected failure on %s%s", "memory_read", frames);
    }
}
