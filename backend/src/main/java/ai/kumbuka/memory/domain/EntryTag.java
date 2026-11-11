/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * One tag an entry carries (V11).
 *
 * <p>The entry is held by its durable key, {@code (logical_id, version)}, and
 * the tag by its id in the platform's vocabulary, never by its token: the
 * token is the platform's to publish and is read through
 * {@code platform.tag_access} at every answer, in the scope of the entry.
 *
 * <p>An assignment is a relation and not a field of the entry. Attaching or
 * detaching one writes here and nowhere else, so the entry's conflict token
 * and change time stay where they are.
 */
@Entity
@IdClass(EntryTagId.class)
@Table(name = "entry_tag", schema = "memory")
public class EntryTag {

    @Id
    @Column(name = "entry_logical_id", nullable = false)
    public UUID entryLogicalId;

    @Id
    @Column(name = "entry_version", nullable = false)
    public int entryVersion = 1;

    @Id
    @Column(name = "tag_id", nullable = false)
    public UUID tagId;

    /** The tenancy axis, on this table exactly as on the entry table. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The subject that attached the tag: who did it, never how often. */
    @Column(name = "attached_by", nullable = false)
    public String attachedBy;

    /** Written by the application from the injected clock (C20.1). */
    @Column(name = "attached_at", nullable = false, updatable = false)
    public Instant attachedAt;
}
