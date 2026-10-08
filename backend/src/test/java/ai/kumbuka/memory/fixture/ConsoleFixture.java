package ai.kumbuka.memory.fixture;

/**
 * Writes to the console, for the guard to find.
 *
 * <p>A stack trace printed to standard error carries the exception's message
 * and the messages of its causes, exactly like a log call handed the
 * exception, and standard output and standard error leave the container by
 * the same road as the log. A guard on log calls that left these open would
 * be a guard on one door of two.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported.
 */
public class ConsoleFixture {

    public void printTheTrace(RuntimeException failure) {
        failure.printStackTrace();
    }

    public void writeToStandardError(String line) {
        System.err.println(line);
    }

    public void writeToStandardOutput(String line) {
        System.out.println(line);
    }
}
