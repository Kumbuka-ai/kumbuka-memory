/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

/**
 * Who is acting.
 *
 * <p>One field, and a type around it rather than a bare string. The subject is
 * passed to the scope directory, written into {@code owner_subject}, and
 * compared against it on every private read; a {@code String} in all three
 * places is a value any other string can be handed to by mistake, and the
 * mistake is an authorship record with the wrong name on it.
 *
 * <p>There is no capacity here and no role. ADR-0034 has each recipient derive
 * the acting identity from its own validated security identity, and this
 * service's permissions come from the platform's read contract rather than
 * from a claim in the token — so a role would be a second answer to a question
 * the contract already answers.
 */
public record Actor(String subject) {

    public Actor {
        if (subject == null || subject.isBlank()) {
            throw new MemoryException(MemoryException.Reason.ACTOR_UNKNOWN);
        }
    }
}
