/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.MemoryException;

/**
 * A refusal of the domain, already worded for the assistant surface.
 *
 * <p>Raised by the generic surface when the caller came through a hop that
 * names the assistant surface ({@code Kumbuka-Surface: assistant}). It keeps
 * the domain's reason, so the status is decided by the one table that
 * decides it for every other refusal, and it carries the envelope the
 * assistant surface would have answered, so the body is that one.
 *
 * <p>Thrown rather than returned: the resource method runs inside a
 * transaction the refused call has already marked for rollback, and a method
 * that returned normally from there would be asking that transaction to
 * commit.
 */
class AssistantRefused extends MemoryException {

    private final transient Payloads.Refusal refusal;

    AssistantRefused(MemoryException refused, Payloads.Refusal refusal) {
        super(refused.reason(), refused.getMessage(), refused.data());
        initCause(refused);
        this.refusal = refusal;
    }

    /** The envelope the assistant surface answers with. */
    Payloads.Refusal refusal() {
        return refusal;
    }
}
