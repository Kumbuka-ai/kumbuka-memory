/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A refusal about the call itself rather than about the entry.
 *
 * <p>Kept apart from the domain's refusals because the two are answered
 * differently: a domain reason is mapped to a status by a table that the
 * compiler forces to stay complete, while these carry their own status —
 * there are few of them, each is about a property of HTTP, and a status is
 * the whole of what they add.
 *
 * <p>The envelope is the same one. {@code { reason, message }} with anything
 * machine-readable under {@code data} holds for every refusal on this surface,
 * whichever half produced it (DEC-0042).
 */
public class SurfaceException extends RuntimeException {

    /**
     * The conditions the transport refuses on.
     *
     * <p>Each carries its own status, because each is a statement about the
     * request as HTTP rather than about the entry it names.
     */
    public enum Reason {

        /** The body is not the JSON this verb takes. */
        PAYLOAD_MALFORMED(400),

        /** The verb named in colon notation is not one this depth carries. */
        VERB_NOT_CARRIED(405),

        /** The call names a writing verb at an address that is truncated. */
        ADDRESS_TRUNCATED(405);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final transient Reason reason;
    private final transient @Nullable String allow;

    /**
     * A refusal of the transport and nothing more: it carries no sentence of
     * its own, and its message is the reason's name. What a caller reads is
     * worded from the reason catalogue's bundles (DEC-0043 R18).
     */
    public SurfaceException(Reason reason) {
        this(reason, null);
    }

    /**
     * @param allow what the address does offer, for the {@code Allow} header a
     *              405 is required to carry. A 405 without it refuses without
     *              saying what would have worked. Absent for a refusal that
     *              is no 405.
     */
    public SurfaceException(Reason reason, @Nullable String allow) {
        super(reason.name());
        this.reason = reason;
        this.allow = allow;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<String> allow() {
        return Optional.ofNullable(allow);
    }
}
