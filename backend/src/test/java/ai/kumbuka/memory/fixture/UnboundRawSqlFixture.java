package ai.kumbuka.memory.fixture;

import jakarta.persistence.EntityManager;

/**
 * A deliberate violation, for the architecture tripwire to find.
 *
 * <p>It issues raw SQL against a tenant-scoped table, carries no
 * {@code @TenantBound}, sits outside the persistence layer and has no reason
 * written for its native statement. That is precisely the class of code the
 * tripwire exists to stop: the ORM's tenant filter does not reach a statement
 * it did not build, so this read is scoped only by the session setting —
 * which nothing here binds. Under a pooled connection it would run under
 * whatever tenant the previous caller left behind, or under none.
 *
 * <p>It lives in the test sources and is never wired into anything. Its only
 * purpose is to be found: {@code RawSqlArchitectureTest} points its detection
 * at this directory and requires this class to be reported. Without that, the
 * tripwire's green result over the main sources would be a statement about a
 * detection nobody has ever seen work.
 *
 * <p>It also mentions {@code @TenantBound} in this very javadoc, on purpose.
 * A tripwire that decided on file text would read that mention and let the
 * class through. The check reads the applied annotation instead, so this
 * paragraph changes nothing about the verdict.
 */
public class UnboundRawSqlFixture {

    private final EntityManager em;

    public UnboundRawSqlFixture(EntityManager em) {
        this.em = em;
    }

    public Object countEntries() {
        return em.createNativeQuery("SELECT count(*) FROM memory.memory").getSingleResult();
    }
}
