package ai.kumbuka.memory.platform;

/**
 * What a call is about to do in a scope.
 *
 * <p>Two values and not a boolean. The directory refuses a write for two
 * different reasons and refuses a read for neither, so the parameter decides
 * which checks run at all — a boolean flag argument at that call site would be
 * a value whose meaning the reader has to look up, and the rule sheet names
 * boolean flag arguments as the thing to avoid.
 */
public enum Access {
    /** Reading. Visibility in the directory's answer is the whole permission. */
    READ,
    /** Writing: {@code create}, {@code update}, {@code withdraw}. */
    WRITE
}
