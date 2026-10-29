/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.surface.SurfaceException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Reads {@code Kumbuka-Surface} on every call of the REST surface (C14.4).
 * Absent, the call is answered in the REST names; {@code assistant}, it is
 * answered as the service's own MCP adapter answers it. Another value than
 * {@code assistant}, an empty one, or the header twice, is
 * {@code HEADER_INVALID} 400 with no {@code data} (C14.5).
 *
 * <p>It runs before the request is matched to a resource, so it is decided
 * before anything else the request could be refused for: the route and its
 * method, the address, the scope, the body and the entry. It runs after
 * authentication: a caller with no identity is left to the refusal of the
 * resource, which is the one for an unauthenticated call.
 *
 * <p>The header decides the names and nothing else: who calls and what that
 * caller may do come from the verified identity as always (ADR-0034). Only
 * the REST surface reads it; the MCP adapter is not under {@code /api} and
 * never sees this filter.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 50)
public class SurfaceHeaderFilter implements ContainerRequestFilter {

    /** The header that names the surface the caller came through. */
    static final String SURFACE = "Kumbuka-Surface";

    /** The one value {@link #SURFACE} takes: the assistant surface. */
    static final String ASSISTANT = "assistant";

    /** The path the REST surface is served under, as a request's path begins. */
    private static final String REST_ROOT = "api/";

    @Inject SecurityIdentity identity;
    @Inject RestCall call;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (!relative.startsWith(REST_ROOT) || identity.isAnonymous()) {
            return;
        }
        List<String> named = request.getHeaders().get(SURFACE);
        if (named == null || named.isEmpty()) {
            return;
        }
        if (!List.of(ASSISTANT).equals(named)) {
            throw new SurfaceException(SurfaceException.Reason.HEADER_INVALID);
        }
        call.onAssistantSurface();
    }
}
