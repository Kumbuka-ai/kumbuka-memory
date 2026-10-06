package ai.kumbuka.memory.fixture;

import org.jboss.logging.Logger;

/**
 * Log calls the convention exists to stop, for the guard to find.
 *
 * <p>The first two are the shapes somebody reaches for while debugging a race
 * at two in the morning: print the content to see which entry this is, print
 * the whole object to see everything at once. Neither is malicious and neither
 * would be noticed in review — the first ships an entry's content out of the
 * container past a boundary built as a missing GRANT, and the second ships the
 * content, the reference and the author together.
 *
 * <p>The rest carry the other things a log line must not: the reference, the
 * conflict token, free text belonging to the caller, a title, a body, a
 * receipt, the subject, and the actor in the four shapes it is written in —
 * the bare identifier, the type, the getter and the accessor. The actor is
 * forbidden for a different reason than the content, and it is the harder one
 * to hold on to. It belongs in the audit log, whose collection is governed; a
 * second, aggregatable stream of the same fact, kept somewhere with different
 * rules, is how not-collecting-behavioural-data gets circumvented without
 * anybody deciding to circumvent it.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported: without it, the guard's clean result over the main
 * sources would be a statement about a detection nobody has seen work.
 */
public class ForbiddenLogFixture {

    private static final Logger LOG = Logger.getLogger(ForbiddenLogFixture.class);

    public void logTheContent(Entryish e) {
        LOG.infof("working on %s", e.content);
    }

    public void logAWholeEntry(Entryish e) {
        LOG.debugf("state now: %s", e);
    }

    public void logTheReference(Entryish e) {
        LOG.debugf("carrying: %s", e.reference);
    }

    /** The conflict token, which lets its holder overwrite the entry. */
    public void logAConflictToken(String conflictToken) {
        LOG.debugf("carrying: %s", conflictToken);
    }

    public void logMetadataText(Entryish e) {
        LOG.debugf("carrying: %s", e.metadata);
    }

    public void logATitle(Entryish e) {
        LOG.debugf("carrying: %s", e.title);
    }

    public void logABody(Entryish e) {
        LOG.debugf("carrying: %s", e.body);
    }

    /** A receipt is a bearer token, in a file the provider operates. */
    public void logAReceipt(String receipt) {
        LOG.debugf("carrying: %s", receipt);
    }

    /** The subject is the actor under the name the token gives it. */
    public void logASubject(String subject) {
        LOG.debugf("carrying: %s", subject);
    }

    /** The actor as a bare identifier — a local, a parameter, a field. */
    public void logTheActorBare(String actor) {
        LOG.infof("changed by %s", actor);
    }

    /** The actor as a type, reached through one of its constants. */
    public void logTheActorType() {
        LOG.infof("changed by %s", Actor.MEMBER);
    }

    /** The actor through a getter. */
    public void logTheActorGetter(Entryish e) {
        LOG.infof("changed by %s", e.getActor());
    }

    /** The actor through an accessor, the shape a record gives it. */
    public void logTheActorAccessor(Entryish e) {
        LOG.infof("changed by %s", e.actor());
    }

    /** Stands in for the entity, so the fixture needs no domain import. */
    public static class Entryish {
        public String content = "an entry's content";
        public String reference = "an entry's provenance pointer";
        public String metadata = "free text belonging to the caller";
        public String title = "a title";
        public String body = "a body";

        public String getActor() {
            return "who did it";
        }

        public String actor() {
            return "who did it";
        }
    }

    /** Stands in for the actor type, so the fixture needs no domain import. */
    public enum Actor {
        MEMBER, SERVICE
    }
}
