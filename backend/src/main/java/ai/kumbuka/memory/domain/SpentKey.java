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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One spent idempotency key: which caller spent it in which scope, on which
 * call and arguments, and which entry the first call laid down (V10).
 *
 * <p>The entry is held by its logical id and by nothing of its content: the
 * arguments are a digest, so the ledger can tell a repeat from a reuse and
 * can tell nobody what the entry said. Remembered for {@link #REMEMBERED_FOR};
 * an older row is replaced in place by the next call that spends the key.
 */
@Entity
@Table(name = "idempotency_key", schema = "memory")
public class SpentKey {

    /** How long a spent key speaks for its call: twenty-four hours (C10.4). */
    public static final Duration REMEMBERED_FOR = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** The tenancy axis, on this table exactly as on the entry table. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    @Column(name = "caller_subject", nullable = false)
    public String callerSubject;

    @Column(name = "idempotency_key", nullable = false)
    public String key;

    @Column(name = "call_name", nullable = false)
    public String callName;

    @Column(name = "argument_digest", nullable = false)
    public String argumentDigest;

    @Column(name = "entry_logical_id", nullable = false)
    public UUID entryLogicalId;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    /** Whether this row still speaks for the key at {@code now}. */
    public boolean stillStandsAt(Instant now) {
        return firstSeenAt.isAfter(now.minus(REMEMBERED_FOR));
    }

    /** Whether this row records the same call the caller is now making. */
    public boolean records(String call, String digest) {
        return callName.equals(call) && argumentDigest.equals(digest);
    }
}
