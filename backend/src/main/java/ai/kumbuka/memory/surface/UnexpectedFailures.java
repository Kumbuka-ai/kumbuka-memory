package ai.kumbuka.memory.surface;

import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Where the report reference of an unforeseen failure is minted, and the only
 * place.
 *
 * <p>The caller is told to report the reference, so the reference is worth
 * exactly as much as the log line it finds. Minting it here, in the same call
 * that writes it to the log beside the failure, is what makes it impossible
 * to hand out a reference the log does not carry.
 *
 * <h2>The failure's shape, never its message</h2>
 *
 * The line carries the type and the stack frames of the failure and of every
 * cause, and no message of any of them. A message is text nobody in this
 * service wrote: a database that refuses a row names the row, and the row
 * holds an entry's content. Handed to the logger, the failure would be printed
 * whole — message and causes included — by whatever format the deployment
 * configures. So the failure is never handed to the logger; the line is
 * assembled here from the parts that cannot carry content.
 */
public final class UnexpectedFailures {

    /**
     * ERROR, because a failure nobody foresaw is ours and no retry of the
     * caller's fixes it.
     *
     * <p>The line carries the tool name, the reference and the frames. Not the
     * address and not the key: whether those may stand in a log line is not
     * decided, and a line written before it is decided cannot be taken back.
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
    public static String logged(String call, Throwable failure) {
        String reportId = UUID.randomUUID().toString();
        String frames = framesOf(failure);
        LOG.errorf("unexpected failure on %s, report %s%s", call, reportId, frames);
        return reportId;
    }

    /**
     * The type and the stack frames of a failure and of each of its causes,
     * one line per frame.
     *
     * <p>A frame names a class, a method, a file and a line, all of them
     * written in this or a library's source. A cause chain that loops back on
     * itself is followed once round.
     */
    static String framesOf(Throwable failure) {
        StringBuilder out = new StringBuilder();
        Set<Throwable> walked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable at = failure; at != null && walked.add(at); at = at.getCause()) {
            out.append(at == failure ? "\n" : "\ncaused by ").append(at.getClass().getName());
            for (StackTraceElement frame : at.getStackTrace()) {
                out.append("\n\tat ").append(frame);
            }
        }
        return out.toString();
    }
}
