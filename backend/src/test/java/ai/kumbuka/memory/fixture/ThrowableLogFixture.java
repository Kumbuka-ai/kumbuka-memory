package ai.kumbuka.memory.fixture;

import org.jboss.logging.Logger;

/**
 * Log calls that hand an exception over, for the guard to find.
 *
 * <p>Each is the shape somebody reaches for when a failure needs explaining:
 * pass the exception along so the trace shows up. Each prints the
 * exception's message and the messages of its causes, and a database's
 * message names the row it rejected, which holds an entry's content.
 *
 * <p>The first five are visible in the compiled class, as calls to a logging
 * method that takes a {@code Throwable}. The rest are typed {@code Object} or
 * {@code String} once compiled, and only the source shows them.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported.
 */
public class ThrowableLogFixture {

    private static final Logger LOG = Logger.getLogger(ThrowableLogFixture.class);

    private static final java.util.logging.Logger JUL =
        java.util.logging.Logger.getLogger(ThrowableLogFixture.class.getName());

    /** The exception before the format string. */
    public void handedOverFirst(RuntimeException failure) {
        LOG.errorf(failure, "call %s failed", "memory_read");
    }

    /** The exception after the message. */
    public void handedOverLast(RuntimeException failure) {
        LOG.error("call failed", failure);
    }

    /** The exception beside a level. */
    public void handedOverAtALevel(RuntimeException failure) {
        LOG.log(Logger.Level.ERROR, "call failed", failure);
    }

    /** The exception with a message-format pattern. */
    public void handedOverWithAPattern(RuntimeException failure) {
        LOG.warnv(failure, "call {0} failed", "memory_read");
    }

    /** The exception through the platform's own logging. */
    public void handedOverThroughTheJdk(RuntimeException failure) {
        JUL.log(java.util.logging.Level.SEVERE, "call failed", failure);
    }

    /** The exception as a format parameter, printed through its toString. */
    public void handedOverAsAParameter(RuntimeException failure) {
        LOG.debugf("call failed: %s", failure);
    }

    /** The message itself. */
    public void itsMessage(RuntimeException failure) {
        LOG.infof("call failed: %s", failure.getMessage());
    }

    /** The message, after a semicolon inside the format string, which ends nothing. */
    public void itsMessagePastASemicolon(RuntimeException failure) {
        LOG.errorf("commit failed; report %s", failure.getMessage());
    }

    /** The exception reached through an accessor other than its typed reason. */
    public void throughAnAccessor(RuntimeException failure) {
        LOG.debugf("call failed: %s", failure.getCause());
    }

    /** An exception caught in a multi-catch, turned into text. */
    public void caughtAndPrinted(Runnable call) {
        try {
            call.run();
        } catch (IllegalStateException | IllegalArgumentException broken) {
            LOG.debugf("call failed: %s", String.valueOf(broken));
        }
    }

    /** An exception made on the spot. */
    public void madeOnTheSpot() {
        LOG.tracef("call failed: %s", new IllegalStateException("made here"));
    }
}
