package ai.kumbuka.memory.tenancy;

import ai.kumbuka.memory.domain.Memory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * An ORM-routed write and read, for the probes that have to observe layer 1.
 *
 * <p><strong>This lives in the test tree deliberately.</strong> The service
 * has no verb surface and therefore no production repository — code with no
 * caller has no red probe either. But the enforcement model has two layers,
 * and the claim that the FIRST one is doing anything cannot be made against
 * raw SQL, which by construction never passes through it. So the ORM path
 * exists here, in the smallest form that exercises the Hibernate tenant
 * filter and nothing else, and it is the probe's fixture rather than the
 * service's repository.
 *
 * <p>{@code @TenantBound} is what puts the database setting on the
 * transaction, immediately after it opens; {@code @Transactional} is what
 * opens it. The pair is the production arrangement, not a test-only
 * shortcut — which is why the probes can conclude something about the
 * production arrangement from watching it.
 */
@ApplicationScoped
@TenantBound
public class OrmFixture {

    @Inject EntityManager em;
    @Inject TenantContext tenantContext;

    /** Writes one entry for the currently bound tenant, through Hibernate. */
    @Transactional
    public UUID write(UUID scopeId, String key) {
        Memory entry = new Memory();
        entry.logicalId = UUID.randomUUID();
        entry.version = 1;
        entry.tenantId = tenantContext.current().toString();
        entry.scopeId = scopeId;
        entry.isPrivate = false;
        entry.ownerSubject = SubstrateDatabaseResource.PROBE_SUBJECT;
        entry.type = "decision";
        entry.key = key;
        entry.content = "probe content";
        em.persist(entry);
        return entry.logicalId;
    }

    /** Every entry the ORM will admit for the currently bound tenant. */
    @Transactional
    public List<Memory> readAll() {
        return em.createQuery("SELECT m FROM Memory m", Memory.class).getResultList();
    }
}
