package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.surface.ReasonCatalogue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The not-found message is held twice, and must be one text.
 *
 * <p>The generic surface's envelope holds it, and so does the assistant
 * surface's catalogue, which may not reach into an adapter for it. The
 * contract fixes the assistant surface's message as "the one fixed message
 * the service already answers on its generic surface", so the generic
 * surface's text is the expected value here and the catalogue's is the one
 * measured. That both surfaces actually answer it is asserted against a
 * running service as well.
 */
class NotFoundWordingTest {

    @Test
    void the_catalogue_carries_the_generic_surface_s_not_found_text() {
        assertThat(ReasonCatalogue.NOT_FOUND_MESSAGE)
            .as("a second wording would tell a caller which surface answered")
            .isEqualTo(Payloads.Refusal.NOT_FOUND_MESSAGE);
    }
}
