/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.surface.KeywordOutsideTheSchema;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real start of the application, refused because an argument publishes a
 * schema keyword the tool schema does not carry.
 *
 * <p>Before, the keyword failed only at the first {@code tools/list}: the
 * service came up, and the caller who listed its tools met the failure. Now
 * the start is refused, with the call, the argument and the keyword named.
 *
 * <p>RED STATE, observed 2026-10-29 with the keyword check taken out of the
 * start: the application came up with the planted argument ("started in
 * 3.267s. Listening on ...") and served until the run was stopped; the launch
 * never returned an exit code.
 */
@QuarkusMainTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(KeywordStartIT.AKeywordOutsideTheSchema.class)
class KeywordStartIT {

    /** The service as it is, but checked against arguments with one planted keyword. */
    public static class AKeywordOutsideTheSchema implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(KeywordOutsideTheSchema.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.port", "0", "quarkus.http.test-port", "0");
        }
    }

    @Test
    void the_application_does_not_start_and_names_the_keyword(QuarkusMainLauncher launcher) {
        LaunchResult started = launcher.launch();

        String said = started.getOutput() + "\n" + started.getErrorOutput();
        assertThat(started.exitCode())
            .as("an argument publishes a keyword the tool schema does not carry, so the "
                + "application does not start. It said:%n%s", said)
            .isNotZero();
        assertThat(said)
            .as("and it names the call, the argument and the keyword")
            .contains("memory_read declares address_pattern with the schema keyword "
                + KeywordOutsideTheSchema.PLANTED);
    }
}
