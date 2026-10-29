/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.adapter.payload.Payloads.EntryFieldsPayload;
import ai.kumbuka.memory.adapter.payload.Payloads.NextPayload;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What a refusal's caller acts on, under {@code data} (DEC-0042). Each member
 * means one thing on every path; a member with no value is absent.
 *
 * @param attempted the call as the caller's surface names it; absent where the
 *                  refusal falls before a call is known, and on {@code NOT_FOUND}
 * @param state absent where the refusal concerns no entry this caller may see
 * @param next absent where the refusal concerns no entry this caller may see
 * @param address absent except for a stale conflict token
 * @param conflictToken absent except for a stale conflict token
 * @param fields absent except for a stale conflict token
 * @param service the scheme of the service that holds the entry; absent except
 *                for a stale conflict token (DEC-0041)
 * @param offenders absent where the refusal concerns no argument
 * @param accepts absent except where a call lists the names or values it accepts
 * @param reference absent except for an unexpected failure (C8.3)
 */
public record RefusalDataPayload(
    @JsonProperty("attempted") @Nullable String attempted,
    @JsonProperty("state") @Nullable String state,
    @JsonProperty("next") @Nullable List<NextPayload> next,
    @JsonProperty("address") @Nullable String address,
    @JsonProperty("conflict_token") @Nullable String conflictToken,
    @JsonProperty("fields") @Nullable EntryFieldsPayload fields,
    @JsonProperty("service") @Nullable String service,
    @JsonProperty("offenders") @Nullable List<String> offenders,
    @JsonProperty("accepts") @Nullable List<String> accepts,
    @JsonProperty("reference") @Nullable String reference) {

    /** The scheme a stale-token refusal names as the service holding the entry. */
    private static final String SCHEME = "memory";

    /** A refusal that names nothing machine-readable. */
    public static final RefusalDataPayload NONE = new RefusalDataPayload(null, null, null, null,
        null, null, null, null, null, null);

    /** The detail of a refusal of the call {@code attempted}, and nothing else yet. */
    public static RefusalDataPayload attempting(String attempted) {
        return new RefusalDataPayload(attempted, null, null, null, null, null, null, null, null,
            null);
    }

    /** The same detail with the state of the entry it concerns, and the calls open on it. */
    public RefusalDataPayload seeing(String entryState, List<NextPayload> open) {
        return new RefusalDataPayload(attempted, entryState, open, address, conflictToken,
            fields, service, offenders, accepts, reference);
    }

    /** The same detail with the entry a stale conflict token was written against. */
    public RefusalDataPayload current(String entryAddress, String token,
                                      EntryFieldsPayload entry) {
        return new RefusalDataPayload(attempted, state, next, entryAddress, token, entry,
            SCHEME, offenders, accepts, reference);
    }

    /** The same detail naming the arguments, or their positions, the refusal concerns. */
    public RefusalDataPayload offending(List<String> names) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            service, List.copyOf(names), accepts, reference);
    }

    /** The same detail listing what the call accepts instead. */
    public RefusalDataPayload accepting(List<String> names) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            service, offenders, List.copyOf(names), reference);
    }

    /** The same detail naming the call as the caller's surface names it (C6.2). */
    public RefusalDataPayload attemptedAs(String call) {
        return new RefusalDataPayload(call, state, next, address, conflictToken, fields,
            service, offenders, accepts, reference);
    }

    /** The same detail with the reference of a failure nobody foresaw. */
    public RefusalDataPayload referencing(String failure) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            service, offenders, accepts, failure);
    }
}
