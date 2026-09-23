package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * One curated entry.
 *
 * <p><strong>Every column of the table is mapped, and that is the point of
 * having this class at all today.</strong> The service has no verb surface,
 * so nothing reads or writes an entry through this mapping yet. What it does
 * is give {@code hibernate.hbm2ddl.auto=validate} something to validate:
 * every field below is a claim about a column's name and type that fails at
 * boot when the schema and the model drift apart. A mapping that named only
 * the columns some future verb happens to need would leave the rest of the
 * table unchecked and would still have to be completed later, by somebody
 * reading the migration instead of the model.
 *
 * <p>The fields are grouped exactly as the columns are grouped in V1, and for
 * the same reason.
 *
 * <p><strong>What is deliberately not here:</strong> no repository, no
 * service, no named query and no lifecycle callback. Code with no caller has
 * no red probe either, and untested code waiting for its seam is the state
 * assumptions come from.
 */
@Entity
@IdClass(MemoryId.class)
@Table(name = "memory", schema = "memory")
public class Memory {

    // --- identity ---------------------------------------------------------

    /**
     * The entry's identity across versions.
     *
     * <p>No {@code @GeneratedValue}: the column carries a database default,
     * but a composite key is assigned by the caller that knows which entry it
     * is writing. A generated half of a composite identity would be a value
     * nobody chose and nobody could reference.
     */
    @Id
    @Column(name = "logical_id", nullable = false)
    public UUID logicalId;

    /** The coordinate within that identity. Always 1 in this edition. */
    @Id
    @Column(name = "version", nullable = false)
    public int version = 1;

    // --- where the entry belongs -----------------------------------------

    /**
     * The tenancy axis — layer 1 of the enforcement model.
     *
     * <p>Typed as String because Quarkus' Hibernate tenant-resolver SPI is
     * String-only, and converted to the {@code uuid} column by
     * {@link StringUuidConverter}.
     */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /**
     * The platform scope this entry belongs to. Stored, never resolved from
     * here: resolving it is a runtime read of the platform's published access
     * contract through
     * {@link ai.kumbuka.memory.platform.ScopeDirectory}, not a schema-level
     * reference — which is why there is no foreign key and no join.
     */
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /**
     * Whether that scope is a private one, denormalised onto the row.
     *
     * <p>The two partial unique indexes need it in their predicate, and the
     * only other way to have it there would be a join into the tenancy
     * anchor's table — which is the reference this service does not hold.
     */
    @Column(name = "is_private", nullable = false)
    public boolean isPrivate;

    /** The subject that authored the entry. Server-derived, never a client flag. */
    @Column(name = "owner_subject", nullable = false)
    public String ownerSubject;

    // --- what the entry is ------------------------------------------------

    @Column(name = "type", nullable = false, length = 32)
    public String type;

    /** Optional address for the entry: lower-case kebab, optionally dotted. */
    @Column(name = "key")
    public String key;

    @Column(name = "content", nullable = false)
    public String content;

    /** Optional provenance pointer. Never fetched on read, and never credential-bearing. */
    @Column(name = "reference")
    public String reference;

    // --- what state it is in ----------------------------------------------

    @Column(name = "state", nullable = false, length = 16)
    public String state = "published";

    @Column(name = "lock", nullable = false, length = 16)
    public String lock = "none";

    @Column(name = "is_head", nullable = false)
    public boolean isHead = true;

    @Column(name = "is_deleted", nullable = false)
    public boolean isDeleted;

    /** Reserved and not enforced in this edition. */
    @Column(name = "valid_from")
    public Instant validFrom;

    /** Reserved and not enforced in this edition. */
    @Column(name = "valid_until")
    public Instant validUntil;

    // --- who wrote it, and through which channel --------------------------

    /** The channel the entry was written through. */
    @Column(name = "source", nullable = false, length = 16)
    public String source = "mcp";

    /** The subject of the last in-place edit. Null until an entry is first edited. */
    @Column(name = "updated_by")
    public String updatedBy;

    /** The channel of the last in-place edit. Null until an entry is first edited. */
    @Column(name = "updated_source", length = 16)
    public String updatedSource;

    // --- technical fields, server-derived ---------------------------------

    /**
     * Written by the database default and read back, never sent.
     *
     * <p>{@code @Generated} is what tells Hibernate to fetch the value rather
     * than to supply one: without it the entity would carry whatever it last
     * saw, and an insert would try to write a null into a not-null column.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    /**
     * Written by the database default at insert; written by the verb on every
     * change afterwards.
     *
     * <p>This is the entry's conflict token. A caller reads it, hands it back
     * on the next write, and the write is refused when it is no longer the
     * current one (DEC-0041) — so it has to move whenever the entry moves, and
     * it has to move for exactly the writes that changed something. There is
     * no maintenance trigger to do it: the schema carries none, and one added
     * now would fire for a direct SQL writer as well, which would make a token
     * change that no verb produced.
     *
     * <p>{@code insertable = false} keeps the insert on the database's clock
     * together with {@code created_at}, so a fresh entry's two timestamps
     * come from one source and are equal. {@code updatable = true} is what
     * lets the verb move it afterwards.
     *
     * <p>A verb that moves it takes the moment from
     * {@link ai.kumbuka.memory.repository.StoredInstant}, never from
     * {@code Instant.now()} directly: this column keeps microseconds and a
     * platform clock may offer more, and the difference is the token. The
     * reasoning is there rather than repeated here.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
