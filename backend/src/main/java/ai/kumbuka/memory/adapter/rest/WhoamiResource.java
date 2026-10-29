/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.WhoamiPayload;
import ai.kumbuka.memory.tenancy.TenantContext;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Who the caller is, and which tenant the request was bound to (C16.2), at the
 * second literal path of the REST surface (C3.2).
 *
 * <p>It makes the realm binding observable: a token from the tenant realm
 * reaches the body, and a token from any other issuer is refused in the
 * authentication layer, before this class is entered. It reads no entry and
 * writes nothing, and it is no verb of the declaration.
 */
@Path("/api/whoami")
@Authenticated
public class WhoamiResource {

    /**
     * The endpoint logs that it was reached and nothing about who reached it:
     * correlation runs through the request id, never through a subject.
     */
    private static final Logger LOG = Logger.getLogger(WhoamiResource.class);

    @Inject SecurityIdentity identity;
    @Inject TenantContext tenants;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public WhoamiPayload whoami() {
        LOG.debug("whoami");
        return new WhoamiPayload(identity.getPrincipal().getName(),
            tenants.current().toString());
    }
}
