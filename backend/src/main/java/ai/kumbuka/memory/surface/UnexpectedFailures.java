package ai.kumbuka.memory.surface;

import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Where the report reference of an unforeseen failure is minted, and the only
 * place.
 *
 * <p>The caller is told to report the reference, so the reference is worth
 * exactly as much as the log line it finds. Minting it here, in the same call
 * that writes it to the log beside the failure, is what makes it impossible
 * to hand out a reference the log does not carry.
 */
public final class UnexpectedFailures {

    /**
     * ERROR, because a failure nobody foresaw is ours and no retry of the
     * caller's fixes it.
     *
     * <p>The line carries the tool name and the reference, and the failure
     * with its trace. Not the address and not the key: whether those may stand
     * in a log line is not decided, and a line written before it is decided
     * cannot be taken back.
     */
    private static final Logger LOG = Logger.getLogger(UnexpectedFailures.class);

    private UnexpectedFailures() {
    }

    /**
     * Logs an unforeseen failure and returns the reference it was logged under.
     *
     * @param call    the tool the caller called
     * @param failure what went wrong
     */
    public static String record(String call, Throwable failure) {
        String reportId = UUID.randomUUID().toString();
        LOG.errorf(failure, "unexpected failure on %s, report %s", call, reportId);
        return reportId;
    }
}
