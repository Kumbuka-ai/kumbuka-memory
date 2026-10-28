/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.realm;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A Keycloak carrying the tenant realm the service is bound to, for the
 * probes that are about a real token rather than about a subject.
 *
 * <p>The rest of the suite supplies its identity with {@code @TestSecurity},
 * because it asks what a subject may reach. A probe that asks whether a token
 * is accepted at all has to be handed one a real realm minted: properly
 * signed, unexpired, with the audience this service validates. The realm's
 * user holds the realm's {@code member} role and nothing of this service.
 *
 * <p>The port is mapped at random. The issuer URL is derived from the mapped
 * port once the container is up and handed to Quarkus as runtime
 * configuration, as {@code quarkus.oidc.auth-server-url}. Keycloak derives a
 * token's {@code iss} from the host and port the token was requested through,
 * so the probe requests its tokens through exactly that URL. Taken from
 * kumbuka-dispatch's two-realm resource, with the one realm this service
 * needs.
 */
public class RealmKeycloakResource implements QuarkusTestResourceLifecycleManager {

    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0";

    /** The realm the service accepts. */
    public static final String TENANT_REALM = "kumbuka";

    public static final String CLIENT_ID = "kumbuka-memory";
    public static final String CLIENT_SECRET = "test-only-not-a-secret";
    public static final String TENANT_USER = "probe-member";
    public static final String USER_PASSWORD = "probe-password";

    /** Config key under which {@link #start()} reports the tenant realm's issuer. */
    public static final String TENANT_ISSUER_KEY = "test.keycloak.tenant-issuer";

    private static GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("tenant-realm.json"),
                "/opt/keycloak/data/import/tenant-realm.json")
            .waitingFor(Wait.forHttp("/realms/" + TENANT_REALM + "/.well-known/openid-configuration")
                .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();

        String issuer = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
            + "/realms/" + TENANT_REALM;
        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.oidc.auth-server-url", issuer);
        cfg.put(TENANT_ISSUER_KEY, issuer);
        return cfg;
    }

    @Override
    public void stop() {
        if (keycloak != null) {
            keycloak.stop();
            keycloak = null;
        }
    }
}
