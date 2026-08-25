package ai.kumbuka.memory.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Supersession forms no cycle — the guard, and the state in which it is
 * absent.
 *
 * <h2>Why this class exists at all</h2>
 *
 * The schema this model comes from has carried this trigger and this function
 * for a long time and has never once exercised them: across its whole test
 * tree neither the relation table nor the words "acyclic" or "supersedes"
 * appear. A database trigger with no coverage is an assurance nobody has ever
 * seen hold. This service is built fresh, so the gate is built with it rather
 * than reproduced as another gap.
 *
 * <h2>The two halves</h2>
 *
 * A cycle-closing edge must be refused, and — with the trigger switched off
 * for the length of one assertion — the very same edge must go through. The
 * second half is what says the refusal came from this guard and not from a
 * constraint, a policy or a typo in the insert.
 *
 * <h2>And the third: the pinned search_path</h2>
 *
 * The function's body names {@code content_relation} unqualified. In the
 * schema this model comes from that resolves through whatever the calling
 * session's {@code search_path} happens to be — so a session that puts
 * another schema first gets a guard that walks the wrong table, finds no
 * path, and admits the cycle without raising anything. Here the function
 * carries its own {@code search_path}, and
 * {@link #the_refusal_is_unchanged_under_a_hostile_session_search_path()} is
 * what shows that this is so rather than merely intended.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AcyclicityProbeIT {

    private UUID tenant;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
    }

    /** A two-edge cycle: a supersedes b, then b supersedes a. */
    @Test
    void an_edge_that_closes_a_cycle_is_refused_and_the_message_says_so()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID a = Db.insertEntry(c, tenant, key("a"));
            UUID b = Db.insertEntry(c, tenant, key("b"));

            // The green half that makes the refusal meaningful: an edge that
            // closes nothing goes through. Without it, a guard that refused
            // every edge would satisfy the assertion below.
            Db.insertRelation(c, tenant, a, b, "supersedes");
            c.commit();

            try {
                Db.insertRelation(c, tenant, b, a, "supersedes");
                fail("the closing edge b->a was admitted: the acyclicity guard is not "
                    + "firing, and the supersession chain now has no head");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal must name what it refused — an error that only says "
                        + "'constraint violated' sends the reader to the wrong place")
                    .contains("would create a cycle");
                assertThat(expected.getSQLState())
                    .as("and it must arrive as the guard's own raised state rather than as "
                        + "a generic failure")
                    .isEqualTo("P0001");
            } finally {
                c.rollback();
            }
        }
    }

    /** The degenerate cycle: an entry that supersedes itself. */
    @Test
    void a_self_referential_supersedes_is_refused() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID a = Db.insertEntry(c, tenant, key("self"));
            c.commit();

            try {
                Db.insertRelation(c, tenant, a, a, "supersedes");
                fail("an entry was allowed to supersede itself");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains("self-referential");
            } finally {
                c.rollback();
            }
        }
    }

    /** The other two relation kinds claim no ordering and are unconstrained. */
    @Test
    void a_cycle_of_refines_edges_is_admitted() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID a = Db.insertEntry(c, tenant, key("r-a"));
            UUID b = Db.insertEntry(c, tenant, key("r-b"));

            Db.insertRelation(c, tenant, a, b, "refines");
            Db.insertRelation(c, tenant, b, a, "refines");
            c.commit();

            assertThat(true)
                .as("only supersession claims an ordering, so only supersession needs one. "
                    + "A guard that also constrained the other kinds would be enforcing a "
                    + "rule nobody stated")
                .isTrue();
        }
    }

    /**
     * The pinned {@code search_path}, observed.
     *
     * <p>The session is given a {@code search_path} that does not contain this
     * service's schema at all, and a decoy table by the same name is placed in
     * the schema it does contain. A function resolving its table through the
     * session would walk the decoy — which is empty, so it would find no path
     * and admit the cycle. The refusal has to arrive unchanged.
     */
    @Test
    void the_refusal_is_unchanged_under_a_hostile_session_search_path() throws SQLException {
        try (Connection owner = Db.asMigrator()) {
            Db.exec(owner, "CREATE SCHEMA IF NOT EXISTS decoy");
            Db.exec(owner, """
                CREATE TABLE IF NOT EXISTS decoy.content_relation (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id uuid NOT NULL,
                    from_logical_id uuid NOT NULL,
                    to_logical_id uuid NOT NULL,
                    to_version int,
                    kind varchar(16) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now())
                """);
            Db.exec(owner, "GRANT USAGE ON SCHEMA decoy TO "
                + SubstrateDatabaseResource.SERVICE_ROLE);
            owner.commit();
        }

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            UUID a = Db.insertEntry(c, tenant, key("path-a"));
            UUID b = Db.insertEntry(c, tenant, key("path-b"));
            Db.insertRelation(c, tenant, a, b, "supersedes");
            c.commit();

            // The hostile part: the decoy first, and this service's schema not
            // on the path at all.
            Db.exec(c, "SET search_path = decoy, pg_catalog");
            Db.bindTenant(c, tenant);

            try {
                // Schema-qualified, because the INSERT itself has to reach the
                // real table — the question is which table the FUNCTION walks,
                // not which one the statement writes to.
                try (var st = c.prepareStatement("""
                        INSERT INTO memory.content_relation
                            (tenant_id, from_logical_id, to_logical_id, kind)
                        VALUES (?::uuid, ?::uuid, ?::uuid, 'supersedes')
                        """)) {
                    st.setString(1, tenant.toString());
                    st.setString(2, b.toString());
                    st.setString(3, a.toString());
                    st.execute();
                }
                fail("under a session search_path that does not contain this schema, the "
                    + "closing edge was admitted — the guard resolved its table through "
                    + "the session and walked an empty decoy, which is a security "
                    + "function failing open under a condition any caller can create");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("RED-CAPABLE STATE, observed: the session search_path was hostile "
                        + "and the refusal arrived unchanged, so the function is carrying "
                        + "its own path rather than borrowing the caller's")
                    .contains("would create a cycle");
            } finally {
                c.rollback();
                Db.exec(c, "RESET search_path");
                c.commit();
            }
        } finally {
            try (Connection owner = Db.asMigrator()) {
                Db.exec(owner, "DROP SCHEMA IF EXISTS decoy CASCADE");
                owner.commit();
            }
        }
    }

    /**
     * The red state: with the trigger disabled, the same cycle-closing edge is
     * admitted.
     *
     * <p>Disabling a trigger requires ownership, which the runtime role does
     * not have — so this is done on the migrator's connection and undone in a
     * {@code finally}. Without this half, every assertion above would hold
     * just as well against a database that refuses those inserts for some
     * entirely different reason.
     */
    @Test
    void with_the_trigger_disabled_the_cycle_goes_through() throws SQLException {
        UUID a;
        UUID b;
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            a = Db.insertEntry(c, tenant, key("off-a"));
            b = Db.insertEntry(c, tenant, key("off-b"));
            Db.insertRelation(c, tenant, a, b, "supersedes");
            c.commit();
        }

        try (Connection owner = Db.asMigrator()) {
            try {
                Db.exec(owner, "ALTER TABLE " + Db.RELATION_TABLE
                    + " DISABLE TRIGGER content_relation_acyclicity");
                owner.commit();

                try (Connection c = Db.asService()) {
                    Db.bindTenant(c, tenant);
                    Db.insertRelation(c, tenant, b, a, "supersedes");
                    assertThat(true)
                        .as("RED STATE, observed: with the trigger disabled the closing "
                            + "edge is written without complaint. So the refusals above "
                            + "were this guard's work and not a constraint, a policy or a "
                            + "malformed statement")
                        .isTrue();
                    c.rollback();
                }
            } finally {
                Db.exec(owner, "ALTER TABLE " + Db.RELATION_TABLE
                    + " ENABLE TRIGGER content_relation_acyclicity");
                owner.commit();
            }
        }

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            try {
                Db.insertRelation(c, tenant, b, a, "supersedes");
                fail("the guard did not come back after being re-enabled");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("and restored, so the red state was the disabled trigger and "
                        + "nothing else")
                    .contains("would create a cycle");
            } finally {
                c.rollback();
            }
        }
    }

    private String key(String suffix) {
        return "cycle-" + suffix + "-" + tenant;
    }
}
