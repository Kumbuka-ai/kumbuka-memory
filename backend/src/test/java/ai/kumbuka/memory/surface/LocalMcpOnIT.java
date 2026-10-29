/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.adapter.mcp.McpTools;
import ai.kumbuka.memory.contract.Mcp;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=true}, set
 * explicitly (C4.6): the assistant surface is there, with the same tool list
 * as a build that does not name the setting, which every other test runs.
 *
 * <p>RED STATE, observed 2026-10-29: with the annotation on {@code McpAdapter}
 * reading {@code stringValue = "on"}, the adapter fell out of this build and
 * {@code tools/list} listed nothing, and this class turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOnIT.On.class)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class LocalMcpOnIT {

    /** The build with the setting named and on. */
    public static class On implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "true", "quarkus.http.test-port", "0");
        }
    }

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void the_assistant_surface_offers_the_declared_tools() {
        List<String> served = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.name");
        assertThat(served).isEqualTo(McpTools.names());
    }
}
