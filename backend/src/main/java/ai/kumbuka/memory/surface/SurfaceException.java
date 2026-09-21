package ai.kumbuka.memory.surface;

/**
 * A refusal about the call itself rather than about the entry.
 *
 * <p>Kept apart from the domain's refusals because the two are answered
 * differently: a domain reason is mapped to a status by a table that the
 * compiler forces to stay complete, while these carry their own status —
 * there are few of them, each is about a property of HTTP, and a status is
 * the whole of what they add.
 *
 * <p>The envelope is the same one. {@code { reason, message }} with anything
 * machine-readable under {@code data} holds for every refusal on this surface,
 * whichever half produced it (DEC-0042).
 */
public class SurfaceException extends RuntimeException {

    /**
     * The conditions the transport refuses on.
     *
     * <p>Each carries its own status, because each is a statement about the
     * request as HTTP rather than about the entry it names.
     */
    public enum Reason {

        /** The body is not the JSON this verb takes. */
        PAYLOAD_MALFORMED(400),

        /** The verb named in colon notation is not one this depth carries. */
        VERB_NOT_CARRIED(405),

        /** The call names a writing verb at an address that is truncated. */
        ADDRESS_TRUNCATED(405);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final transient Reason reason;
    private final transient String allow;

    public SurfaceException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * @param allow what the address does offer, for the {@code Allow} header a
     *              405 is required to carry. A 405 without it refuses without
     *              saying what would have worked.
     */
    public SurfaceException(Reason reason, String message, String allow) {
        super(message);
        this.reason = reason;
        this.allow = allow;
    }

    public Reason reason() {
        return reason;
    }

    public String allow() {
        return allow;
    }
}
