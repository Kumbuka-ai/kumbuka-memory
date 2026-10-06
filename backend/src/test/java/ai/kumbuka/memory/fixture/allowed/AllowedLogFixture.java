package ai.kumbuka.memory.fixture.allowed;

import java.util.UUID;

import org.jboss.logging.Logger;

import ai.kumbuka.memory.domain.MemoryException;

/**
 * Every shape the logging convention allows, for the guard to pass over.
 *
 * <p>The convention names what a log line may carry: an address, a selector,
 * a number, a transition, a status, a typed reason, a duration and a scope id.
 * This class writes one log call per item, using the bare identifier a
 * developer would actually reach for — {@code selector}, not
 * {@code theSelectorName} — because the bare form is the one a substring check
 * gets wrong: {@code selector} contains the letters of {@code actor}.
 *
 * <h2>The address is not here, and that is deliberate</h2>
 *
 * An address of this service carries a key its caller chose. Whether that key
 * may appear in a log line is not decided, so this fixture does not assert it
 * is permitted and the guard does not refuse it. Adding it here would decide
 * the question by the side door.
 *
 * <p>It exists because a guard that only ever reports is half a guard. Until
 * something asserts that the permitted shapes come back clean, "no offence
 * found over the main sources" is a statement about a tree that happens not to
 * contain the offending shapes, not about a check that can tell them apart.
 *
 * <p>It lives in the test sources, in its own package, and is wired into
 * nothing. The package matters: the guard points one test at this directory
 * and requires zero offences, so a forbidden call must never be added here.
 * Forbidden calls belong one directory up, in {@code ForbiddenLogFixture}.
 */
public class AllowedLogFixture {

    private static final Logger LOG = Logger.getLogger(AllowedLogFixture.class);

    /**
     * A selector. On the permitted list — and the identifier a substring check
     * for the actor would report.
     */
    public void logASelector(String selector) {
        LOG.debugf("checking selector '%s'", selector);
    }

    /** A number. A count of things, carrying nothing about any of them. */
    public void logANumber(int number) {
        LOG.debugf("page of %d", number);
    }

    /** A transition. Two status names and the arrow between them. */
    public void logATransition(String from, String to) {
        LOG.infof("%s -> %s", from, to);
    }

    /** A status. The word for where something stands. */
    public void logAStatus(String status) {
        LOG.infof("status now %s", status);
    }

    /**
     * A typed reason, reached through a variable.
     *
     * <p>A constant from a closed set, so the log carries the category of the
     * refusal and never the caller's prose about it.
     */
    public void logATypedReason(MemoryException.Reason reason) {
        LOG.warnf("refused: %s", reason);
    }

    /**
     * Typed reasons, reached through the constants themselves.
     *
     * <p>This is the form the main sources write. These are not an arbitrary
     * sample: they are every constant in {@link MemoryException.Reason} whose
     * name holds a word on the forbidden list — the token, the content, the
     * reference and the actor. The real enum is imported rather than
     * mirrored so that renaming or removing one of them breaks this file at
     * compile time instead of leaving a copy that quietly stops describing the
     * service.
     */
    public void logAConflictTokenReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.CONFLICT_TOKEN_STALE);
    }

    /** See {@link #logAConflictTokenReason()}. */
    public void logAMissingConflictTokenReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.CONFLICT_TOKEN_MISSING);
    }

    /** See {@link #logAConflictTokenReason()}. */
    public void logAContentReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.CONTENT_ABSENT);
    }

    /** See {@link #logAConflictTokenReason()}. */
    public void logAContentSizeReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.CONTENT_OVERSIZE);
    }

    /** See {@link #logAConflictTokenReason()}. */
    public void logAReferenceReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.REFERENCE_CREDENTIAL_BEARING);
    }

    /** See {@link #logAConflictTokenReason()}. */
    public void logAnActorReason() {
        LOG.warnf("refused: %s", MemoryException.Reason.ACTOR_UNKNOWN);
    }

    /** A duration. How long it took, which is about the machine. */
    public void logADuration(long durationMillis) {
        LOG.debugf("took %d ms", durationMillis);
    }

    /** A scope id. An opaque identifier, resolvable only with the directory. */
    public void logAScopeId(UUID scopeId) {
        LOG.debugf("scope %s", scopeId);
    }
}
