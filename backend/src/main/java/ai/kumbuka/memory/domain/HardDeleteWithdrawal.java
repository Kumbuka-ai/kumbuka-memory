package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.repository.MemoryRepository;
import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The community edition's withdrawal: the row is deleted.
 *
 * <p>Not a soft delete and not a tombstone. The operator decided on
 * 2026-09-19 that the community edition forgets, and an erasure obligation
 * honoured by setting a boolean is not an erasure — which is also why this
 * service's runtime role holds {@code DELETE} on its entry table at all, the
 * one privilege that distinguishes it from its siblings.
 *
 * <p>What is deliberately absent: a check of whether anything still points at
 * this entry. {@code content_relation} holds no foreign key to
 * {@code memory}, by design and with its reason written in V1 — a relation
 * outlives its target's removal, and a dangling edge is a fact about the
 * graph rather than a defect to prevent here. Removing edges would also be a
 * write to a table no verb of this wave carries.
 */
@ApplicationScoped
@TenantBound
public class HardDeleteWithdrawal implements Withdrawal {

    @Inject MemoryRepository entries;

    @Override
    @Transactional
    public Outcome withdraw(Memory entry) {
        entries.delete(entry);
        return Outcome.DESTROYED;
    }
}
