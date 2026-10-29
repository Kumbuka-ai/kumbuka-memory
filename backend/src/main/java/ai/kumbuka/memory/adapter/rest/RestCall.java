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

import java.util.Locale;
import java.util.Optional;

/**
 * The call the current request of the generic surface makes, and the surface
 * it is answered on.
 *
 * <p>A refusal names the call it refuses (DEC-0043 R10), and on this surface
 * the call is decided by the method, the depth of the path and a verb in
 * colon notation, all of which only the resource method has resolved. It
 * states the verb here as its first act, and the exception mapper reads it
 * back to word the refusal. A refusal raised before a verb was resolved is
 * one of the transport's, whose pattern names no call.
 *
 * <p>The surface is stated by {@link SurfaceHeaderFilter} before the request
 * is matched: with {@code Kumbuka-Surface: assistant} every call is answered
 * as the service's own MCP adapter answers it, with the tool names (C14.4).
 * The header decides nothing else.
 *
 * <p>The entry the call aims at is stated where the route knows it, so that
 * a refusal on the assistant surface can carry its state and the calls open
 * on it, as the MCP adapter's refusal does.
 */
@RequestScoped
public class RestCall {

    private @Nullable AssistantVerb verb;
    private boolean assistant;
    private @Nullable String target;

    /** States the verb this request calls. */
    public void is(AssistantVerb called) {
        this.verb = called;
    }

    /** The verb, once the resource method has resolved it. */
    public Optional<AssistantVerb> verb() {
        return Optional.ofNullable(verb);
    }

    /** States that the request is to be answered on the assistant surface. */
    void onAssistantSurface() {
        this.assistant = true;
    }

    /** Whether the request is answered on the assistant surface. */
    public boolean assistant() {
        return assistant;
    }

    /** States the complete address of the entry the call aims at. */
    public void aimsAt(String address) {
        this.target = address;
    }

    /** The complete address of the entry the call aims at, where the route knows one. */
    public Optional<String> target() {
        return Optional.ofNullable(target);
    }

    /**
     * A verb as the caller's surface names it (C6.2): its tool name on the
     * assistant surface, its REST name otherwise.
     */
    public String named(AssistantVerb called) {
        return assistant ? called.call() : called.name().toLowerCase(Locale.ROOT);
    }
}
