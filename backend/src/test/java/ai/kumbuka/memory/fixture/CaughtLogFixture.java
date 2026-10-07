package ai.kumbuka.memory.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

import org.jboss.logging.Logger;

/**
 * Exceptions caught in each way a catch clause is written, then logged.
 *
 * <p>The two exception types are named so that neither name ends in
 * {@code Exception} or {@code Error}. Nothing but the reading of the catch
 * clause itself can tell the guard that the variable holds an exception, so
 * each call here is reported only for as long as that reading works. Every
 * variable has its own name for the same reason: the guard collects names per
 * file, and a name declared elsewhere in the file with an exception type would
 * be reported whether the catch clause was read or not.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported.
 */
public class CaughtLogFixture {

    private static final Logger LOG = Logger.getLogger(CaughtLogFixture.class);

    /** A single type. */
    public void caughtPlainly(Runnable call) {
        try {
            call.run();
        } catch (Mishap slip) {
            LOG.errorf("call failed: %s", slip);
        }
    }

    /** A single type, final. */
    public void caughtFinal(Runnable call) {
        try {
            call.run();
        } catch (final Mishap stumble) {
            LOG.errorf("call failed: %s", stumble);
        }
    }

    /** Two types. */
    public void caughtInAMultiCatch(Runnable call) {
        try {
            call.run();
        } catch (Mishap | Breakage tumble) {
            LOG.errorf("call failed: %s", tumble);
        }
    }

    /** A clause broken over lines. */
    public void caughtOverLines(Runnable call) {
        try {
            call.run();
        } catch (
                final Mishap
                | Breakage lapse) {
            LOG.errorf("call failed: %s", lapse);
        }
    }

    /** An annotation without an argument. */
    public void caughtUnderAnAnnotation(Runnable call) {
        try {
            call.run();
        } catch (@Witnessed Mishap glitch) {
            LOG.errorf("call failed: %s", glitch);
        }
    }

    /** An annotation with an argument. */
    public void caughtUnderAnAnnotationWithAnArgument(Runnable call) {
        try {
            call.run();
        } catch (@Noted("on purpose") Mishap hiccup) {
            LOG.errorf("call failed: %s", hiccup);
        }
    }

    /** An annotation over two types. */
    public void caughtUnderAnAnnotationInAMultiCatch(Runnable call) {
        try {
            call.run();
        } catch (@Witnessed Mishap | Breakage snag) {
            LOG.errorf("call failed: %s", snag);
        }
    }

    /** An exception whose type name says nothing about being one. */
    public static class Mishap extends RuntimeException {
    }

    /** A second one, unrelated to the first, so the two can share a clause. */
    public static class Breakage extends RuntimeException {
    }

    /** An annotation without members, for a catch clause to carry. */
    @Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
    public @interface Witnessed {
    }

    /** An annotation with a member, for a catch clause to carry. */
    @Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
    public @interface Noted {
        String value();
    }
}
