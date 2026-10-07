package ai.kumbuka.memory.fixture;

import java.util.List;

import org.jboss.logging.Logger;

/**
 * Variables declared with an exception type, the types {@code Exception}
 * and {@code Error} themselves among them, then logged.
 *
 * <p>These are the names a type check by suffix misses when the suffix is the
 * whole name. Each variable has its own name, so that each call is reported by
 * its own declaration and by nothing else in the file. The root type, a
 * longer name ending in {@code Error}, and the loop variable stand beside
 * them for the forms the check has always read.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported.
 */
public class DeclaredLogFixture {

    private static final Logger LOG = Logger.getLogger(DeclaredLogFixture.class);

    private final Exception lastProblem = new IllegalStateException("kept");

    /** A parameter. */
    public void aParameter(Exception problem) {
        LOG.errorf("unforeseen: %s", problem);
    }

    /** A local. */
    public void aLocal() {
        Error fault = new AssertionError("made here");
        LOG.errorf("fatal: %s", fault);
    }

    /** A parameter of the root type. */
    public void aThrowable(Throwable trouble) {
        LOG.errorf("unforeseen: %s", trouble);
    }

    /** A local of a type whose name ends in Error. */
    public void aLocalOfAnErrorSubtype() {
        AssertionError breach = new AssertionError("made here");
        LOG.errorf("fatal: %s", breach);
    }

    /** A field. */
    public void aField() {
        LOG.warnf("last seen: %s", lastProblem);
    }

    /** A loop variable. */
    public void aLoopVariable(List<RuntimeException> failures) {
        for (RuntimeException pending : failures) {
            LOG.warnf("pending: %s", pending);
        }
    }
}
