/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.contract.Contract;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The not-found message is the contract's text.
 *
 * <p>It is held once, in the bundles: the reason's message and its remedy,
 * which both surfaces answer with. The expected value is the fixed message of
 * section 4.3 of the contract, read from the contract copy, and the actual one
 * the two texts as their files write them -- not a constant of the code,
 * because a value the code holds cannot tell the code it is wrong. That both
 * surfaces actually answer it is asserted against a running service as well.
 */
class NotFoundWordingTest {

    @Test
    void the_bundles_carry_the_contract_s_not_found_text() {
        String fixed = Contract.notFoundMessage();
        assertThat(fixed).as("section 4.3 states a message").isNotBlank();

        ReasonCatalogue.Reason notFound = ReasonCatalogue.of(ReasonCatalogue.NOT_FOUND);
        assertThat(notFound.messageTemplate() + " " + notFound.remedyTemplate())
            .as("one wording for both surfaces; a second would tell a caller which surface "
                + "answered")
            .isEqualTo(fixed);
    }
}
