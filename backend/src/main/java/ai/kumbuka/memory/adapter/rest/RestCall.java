/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.surface.AssistantVerb;
import jakarta.enterprise.context.RequestScoped;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * The verb the current request of the generic surface calls.
 *
 * <p>A refusal names the call it refuses (DEC-0043 R10), and on this surface
 * the call is decided by the method, the depth of the path and a verb in
 * colon notation, all of which only the resource method has resolved. It
 * states the verb here as its first act, and the exception mapper reads it
 * back to word the refusal. A refusal raised before a verb was resolved is
 * one of the transport's, whose pattern names no call.
 */
@RequestScoped
public class RestCall {

    private @Nullable AssistantVerb verb;

    /** States the verb this request calls. */
    public void is(AssistantVerb called) {
        this.verb = called;
    }

    /** The verb, once the resource method has resolved it. */
    public Optional<AssistantVerb> verb() {
        return Optional.ofNullable(verb);
    }
}
