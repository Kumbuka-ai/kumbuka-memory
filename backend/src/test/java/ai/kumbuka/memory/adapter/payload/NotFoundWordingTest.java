package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.contract.Contract;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The not-found message is held twice, and both must be the contract's text.
 *
 * <p>The generic surface's envelope holds it, and so does the assistant
 * surface's catalogue, which may not reach into an adapter for it. The
 * expected value is the fixed message of section 4.3 of the contract, read
 * from the contract copy — not one of the two constants, because a value the
 * code holds cannot tell the code it is wrong. That both surfaces actually
 * answer it is asserted against a running service as well.
 */
class NotFoundWordingTest {

    @Test
    void both_surfaces_carry_the_contract_s_not_found_text() {
        String fixed = Contract.notFoundMessage();
        assertThat(fixed).as("section 4.3 states a message").isNotBlank();

        assertThat(Payloads.Refusal.NOT_FOUND_MESSAGE)
            .as("the generic surface's text")
            .isEqualTo(fixed);
        assertThat(ReasonCatalogue.NOT_FOUND_MESSAGE)
            .as("the assistant surface's text; a second wording would tell a caller which "
                + "surface answered")
            .isEqualTo(fixed);
    }
}
