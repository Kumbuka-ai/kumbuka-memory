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
 * @param attempted absent on the generic surface, and where no call is known
 * @param state absent where the refusal concerns no entry this caller may see
 * @param next absent where the refusal concerns no entry this caller may see
 * @param address absent except for a stale conflict token
 * @param conflictToken absent except for a stale conflict token
 * @param fields absent except for a stale conflict token
 * @param offenders absent where the refusal concerns no argument
 * @param accepts absent except where a call lists the names or values it accepts
 * @param report absent except for an unexpected failure
 */
public record RefusalDataPayload(
    @JsonProperty("attempted") @Nullable String attempted,
    @JsonProperty("state") @Nullable String state,
    @JsonProperty("next") @Nullable List<NextPayload> next,
    @JsonProperty("address") @Nullable String address,
    @JsonProperty("conflict_token") @Nullable String conflictToken,
    @JsonProperty("fields") @Nullable EntryFieldsPayload fields,
    @JsonProperty("offenders") @Nullable List<String> offenders,
    @JsonProperty("accepts") @Nullable List<String> accepts,
    @JsonProperty("report") @Nullable String report) {

    /** A refusal that names nothing machine-readable. */
    public static final RefusalDataPayload NONE = new RefusalDataPayload(null, null, null, null,
        null, null, null, null, null);

    /** The detail of a refusal of the call {@code attempted}, and nothing else yet. */
    public static RefusalDataPayload attempting(String attempted) {
        return new RefusalDataPayload(attempted, null, null, null, null, null, null, null, null);
    }

    /** The same detail with the state of the entry it concerns, and the calls open on it. */
    public RefusalDataPayload seeing(String entryState, List<NextPayload> open) {
        return new RefusalDataPayload(attempted, entryState, open, address, conflictToken,
            fields, offenders, accepts, report);
    }

    /** The same detail with the entry a stale conflict token was written against. */
    public RefusalDataPayload current(String entryAddress, String token,
                                      EntryFieldsPayload entry) {
        return new RefusalDataPayload(attempted, state, next, entryAddress, token, entry,
            offenders, accepts, report);
    }

    /** The same detail naming the arguments, or their positions, the refusal concerns. */
    public RefusalDataPayload offending(List<String> names) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            List.copyOf(names), accepts, report);
    }

    /** The same detail listing what the call accepts instead. */
    public RefusalDataPayload accepting(List<String> names) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            offenders, List.copyOf(names), report);
    }

    /** The same detail with the reference of a failure nobody foresaw. */
    public RefusalDataPayload reporting(String reportId) {
        return new RefusalDataPayload(attempted, state, next, address, conflictToken, fields,
            offenders, accepts, reportId);
    }

    /** The same detail without the call: the generic surface does not name it. */
    public RefusalDataPayload unattempted() {
        return new RefusalDataPayload(null, state, next, address, conflictToken, fields,
            offenders, accepts, report);
    }
}
