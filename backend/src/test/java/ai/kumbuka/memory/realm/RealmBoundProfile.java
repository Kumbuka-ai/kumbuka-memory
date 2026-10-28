/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.realm;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns the identity tenant on, and points it at the test Keycloak's realm.
 *
 * <p>{@code quarkus.oidc.tenant-enabled} is a build-time property. The test
 * configuration bakes it to false so the subject probes need no identity
 * provider, and a test resource cannot flip a build-time property back at
 * runtime — only a profile can, because Quarkus re-augments the application
 * for it.
 *
 * <p>The issuer is deliberately NOT set here. {@code auth-server-url} is a
 * runtime property, so {@link RealmKeycloakResource} supplies it from the
 * container's mapped port once the container is up. The HTTP port is the
 * operating system's choice for the same reason: a second application in one
 * run collides on a fixed one.
 */
public class RealmBoundProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.oidc.tenant-enabled", "true",
            "quarkus.oidc.client-id", RealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.credentials.secret", RealmKeycloakResource.CLIENT_SECRET,
            "quarkus.oidc.token.audience", RealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.tls.verification", "none",
            "quarkus.http.test-port", "0");
    }
}
