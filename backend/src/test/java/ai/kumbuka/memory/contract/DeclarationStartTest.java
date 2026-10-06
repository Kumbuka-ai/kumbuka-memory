package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The check the service runs at start, run on its own.
 *
 * <p>The start itself is observed refusing in {@link CatalogueStartIT}; this
 * class pins what the check says when it refuses, for each thing it refuses.
 */
class DeclarationStartTest {

    @Test
    void the_declaration_the_service_carries_is_servable() {
        assertThatCode(() -> SurfaceDeclaration.requireServable(ReasonCatalogue.byCode()))
            .doesNotThrowAnyException();
    }

    @Test
    void a_raisable_reason_missing_from_the_catalogue_refuses_and_is_named() {
        Map<String, ReasonCatalogue.Reason> gap = new LinkedHashMap<>(ReasonCatalogue.byCode());
        gap.remove("CONTENT_OVERSIZE");
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(gap))
            .as("a catalogue without a reason the domain raises is refused, naming it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CONTENT_OVERSIZE");
    }

    @Test
    void a_collapsed_reason_is_covered_by_the_code_it_is_answered_with() {
        Map<String, ReasonCatalogue.Reason> gap = new LinkedHashMap<>(ReasonCatalogue.byCode());
        gap.remove(ReasonCatalogue.NOT_FOUND);
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(gap))
            .as("ENTRY_ABSENT and SCOPE_UNRESOLVED are answered as NOT_FOUND, so without "
                + "it both are undeclared")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ENTRY_ABSENT")
            .hasMessageContaining("SCOPE_UNRESOLVED");
    }

    @Test
    void a_reason_this_surface_adds_is_held_to_the_same_rule() {
        Map<String, ReasonCatalogue.Reason> gap = new LinkedHashMap<>(ReasonCatalogue.byCode());
        gap.remove("UNEXPECTED_FAILURE");
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(gap))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNEXPECTED_FAILURE");
    }

    @Test
    void a_reason_declared_without_a_pattern_refuses() {
        Map<String, ReasonCatalogue.Reason> blank = new LinkedHashMap<>(ReasonCatalogue.byCode());
        ReasonCatalogue.Reason kept = blank.get("TYPE_UNKNOWN");
        blank.put("TYPE_UNKNOWN", new ReasonCatalogue.Reason(kept.code(), " ", null,
            kept.remedy(), kept.steps(), kept.repeat(), kept.reach()));
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(blank))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TYPE_UNKNOWN");
    }

    @Test
    void a_call_with_no_arguments_or_an_undescribed_one_refuses() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribedArguments("memory_nothing",
            List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no arguments");
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribedArguments("memory_read",
            List.of(Argument.top("address", Argument.STRING, true, " "))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("address");
    }
}
