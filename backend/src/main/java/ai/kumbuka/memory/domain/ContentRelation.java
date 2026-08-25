package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A typed, directional relation between two entries.
 *
 * <p>Both ends are logical ids and never keys — a key can be rewritten, and a
 * relation that followed one would silently repoint. {@link #toVersion} null
 * means the relation tracks the target's head; a set value pins it to one
 * version.
 *
 * <p><strong>There is no association mapping to {@link Memory}, and there is
 * no foreign key underneath one.</strong> A logical id is deliberately
 * non-unique across versions, so it cannot be a foreign-key target; and a
 * relation has to outlive the tombstoning of what it points at, which a hard
 * reference would prevent. The ends are addresses, resolved when something
 * asks, by whatever asks.
 *
 * <p>The one structural rule over this table — that {@code supersedes} edges
 * form no cycle — is enforced by a database trigger rather than here. A rule
 * in application code is a rule for the callers that go through that code,
 * and this table is reachable by SQL that does not.
 */
@Entity
@Table(name = "content_relation", schema = "memory")
public class ContentRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * The tenancy axis — layer 1 of the enforcement model, on this table
     * exactly as on the entry table.
     */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "from_logical_id", nullable = false)
    public UUID fromLogicalId;

    @Column(name = "to_logical_id", nullable = false)
    public UUID toLogicalId;

    /** Null tracks the target's head; a set value pins the relation to one version. */
    @Column(name = "to_version")
    public Integer toVersion;

    /** One of {@code supersedes}, {@code refines}, {@code references}. */
    @Column(name = "kind", nullable = false, length = 16)
    public String kind;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
