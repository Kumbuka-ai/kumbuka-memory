package ai.kumbuka.memory.fixture;

/**
 * A place where a log call begins and is never closed, for the guard to name.
 *
 * <p>It is written in a comment, because a call the compiler accepts always
 * closes. The guard reads a comment as code, so the comment below is a place
 * where a log call begins, and reading on from it never reaches the
 * parenthesis that would close it. The guard has to name this file and this
 * line. Passing over it would make the call one that does not exist.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported.
 */
public class UnclosedLogFixture {

    // LOG.infof("begun and never closed",
}
