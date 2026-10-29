/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryException;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A token that authenticated and names no subject to act as is refused
 * {@code ACTOR_UNKNOWN} (C8.8): there is no author to record and no owner of a
 * private entry to match, so nothing is guessed. The refusal names nothing the
 * caller sent, because a caller without a subject sent nothing it could name.
 */
class CallerActorTest {

    @Test
    void a_token_without_a_subject_is_actor_unknown() {
        CallerActor anonymous = new CallerActor();
        anonymous.identity = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
        assertThatThrownBy(anonymous::current)
            .isInstanceOfSatisfying(MemoryException.class, refused ->
                assertThat(refused.reason()).isEqualTo(MemoryException.Reason.ACTOR_UNKNOWN));

        CallerActor blank = new CallerActor();
        blank.identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(" ")).build();
        assertThatThrownBy(blank::current)
            .isInstanceOfSatisfying(MemoryException.class, refused ->
                assertThat(refused.reason()).isEqualTo(MemoryException.Reason.ACTOR_UNKNOWN));
    }

    /** The red state, observed on every build: a subject is no refusal. */
    @Test
    void a_token_with_a_subject_acts_as_it() {
        CallerActor named = new CallerActor();
        named.identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("probe-subject")).build();
        assertThat(named.current().subject())
            .as("RED STATE, observed: the check must let a subject through, or it refuses "
                + "every caller and says nothing about this one")
            .isEqualTo("probe-subject");
    }
}
