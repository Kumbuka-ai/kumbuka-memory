package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.tenancy.OrmFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.memory.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composite identity of an entry, and the relation that points at one.
 *
 * <h2>Why the identity needs a test rather than a reading</h2>
 *
 * An entry is identified by {@code (logical_id, version)} and by nothing else
 * — there is no surrogate row id. That makes {@link MemoryId} load-bearing in
 * a way a value class usually is not: Hibernate uses its {@code equals} and
 * {@code hashCode} to decide whether two references mean the same entry, so a
 * defect in either is not a wrong answer to a comparison, it is a session that
 * quietly holds two copies of one row or one copy of two.
 *
 * <p>Both are therefore exercised where they actually matter — through a
 * lookup by identity against a real database — and not only as an algebraic
 * contract. The contract is checked too, because a lookup that happens to work
 * proves the pair agrees with itself, not that it distinguishes what it must.
 *
 * <h2>And why the relation is written here at all</h2>
 *
 * {@link ContentRelation} is otherwise only ever written by raw SQL, in the
 * probes that drive the acyclicity trigger. Its mapping would then be checked
 * by the schema validator and by nothing else — every column name right, and
 * no evidence that an insert through it produces the row it describes. One
 * write and one read back is the whole claim.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class EntryIdentityIT {

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject OrmFixture orm;
    @Inject TenantContext tenantContext;

    @Test
    void an_entry_is_found_by_its_composite_identity() throws Exception {
        UUID tenant = UUID.randomUUID();

        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            UUID logicalId = orm.write(SCOPE, "identity-" + tenant);

            Memory found = orm.find(logicalId, 1);
            {
                assertThat(found)
                    .as("the entry resolves by (logical_id, version) — the lookup Hibernate "
                        + "performs with MemoryId's own equals and hashCode")
                    .isNotNull();
                assertThat(found.key).isEqualTo("identity-" + tenant);
                assertThat(found.version).isEqualTo(1);
                assertThat(found.isHead)
                    .as("the column defaults carry the head-versus-history shape without "
                        + "the caller stating it")
                    .isTrue();
                assertThat(found.isDeleted).isFalse();
                assertThat(found.state).isEqualTo("published");
                assertThat(found.lock).isEqualTo("none");
                assertThat(found.source).isEqualTo("mcp");
                assertThat(found.createdAt)
                    .as("created_at is written by the database and read back, never sent")
                    .isNotNull();

                assertThat(orm.find(logicalId, 2))
                    .as("and another version of the same entry is a different entry — if "
                        + "the version were ignored in the comparison, a copy-on-write "
                        + "edition would silently overwrite its own history")
                    .isNull();
            }
        }
    }

    /**
     * The contract itself, including the cases a database lookup never
     * reaches: a comparison against null, against a foreign type, and against
     * an identity that differs only in the version.
     */
    @Test
    void the_identity_distinguishes_what_it_must() {
        UUID logicalId = UUID.randomUUID();
        MemoryId first = new MemoryId(logicalId, 1);
        MemoryId same = new MemoryId(logicalId, 1);
        MemoryId laterVersion = new MemoryId(logicalId, 2);
        MemoryId otherEntry = new MemoryId(UUID.randomUUID(), 1);

        assertThat(first)
            .isEqualTo(first)
            .isEqualTo(same)
            .hasSameHashCodeAs(same)
            .isNotEqualTo(laterVersion)
            .isNotEqualTo(otherEntry)
            .isNotEqualTo(null)
            .isNotEqualTo("not an identity");

        assertThat(new MemoryId())
            .as("the no-argument constructor is the one the persistence provider uses, and "
                + "an identity with nothing in it must not equal one with something in it")
            .isNotEqualTo(first);
    }

    @Test
    void a_relation_written_through_the_orm_reads_back() throws Exception {
        UUID tenant = UUID.randomUUID();

        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            UUID from = orm.write(SCOPE, "relation-from-" + tenant);
            UUID to = orm.write(SCOPE, "relation-to-" + tenant);

            UUID relationId = orm.writeRelation(from, to, "refines");

            ContentRelation found = orm.findRelation(relationId);
            {
                assertThat(found).isNotNull();
                assertThat(found.fromLogicalId).isEqualTo(from);
                assertThat(found.toLogicalId).isEqualTo(to);
                assertThat(found.toVersion)
                    .as("a null target version means the relation tracks the target's head "
                        + "rather than pinning it, and null must survive the round trip as "
                        + "null")
                    .isNull();
                assertThat(found.kind).isEqualTo("refines");
                assertThat(found.createdAt).isNotNull();
            }
        }
    }
}
