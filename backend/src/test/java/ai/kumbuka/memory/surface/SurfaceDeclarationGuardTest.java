/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start check of the declaration (C5.6), observed refusing, once for each
 * thing it refuses.
 *
 * <p>The checks take the declaration as served and the catalogue as plain
 * values, so each runs here against a declaration or a catalogue that breaks
 * it, without the service. The catalogue's own texts come from the bundles,
 * which exist only in a running application; where a check reads them, it
 * reads a catalogue whose texts are stubbed. That the declaration the service
 * carries passes is observed by every test that boots the service, and that a
 * start is refused by {@code CatalogueStartIT}.
 */
class SurfaceDeclarationGuardTest {

    private static final Set<String> CATALOGUE = Set.of("NOT_FOUND", "TYPE_UNKNOWN");

    @Test
    void a_declaration_that_keeps_every_rule_passes() {
        assertThatCode(() -> DeclarationGuard.requireServable(declaration(), CATALOGUE))
            .doesNotThrowAnyException();
    }

    @Test
    void two_calls_under_one_name_refuse_the_start() {
        Map<String, Object> declaration = declaration();
        calls(declaration).add(call("memory_read", "Reads one entry."));
        assertThatThrownBy(() -> DeclarationGuard.requireServable(declaration, CATALOGUE))
            .as("RED STATE, observed: a duplicate call name refuses the start, and names it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("two calls are declared as memory_read");
    }

    @Test
    void a_call_with_no_description_refuses_the_start() {
        Map<String, Object> blank = declaration();
        calls(blank).get(0).put("description", " ");
        assertThatThrownBy(() -> DeclarationGuard.requireServable(blank, CATALOGUE))
            .as("RED STATE, observed: a blank description refuses the start, naming the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read is declared without a description");
        Map<String, Object> absent = declaration();
        calls(absent).get(0).remove("description");
        assertThatThrownBy(() -> DeclarationGuard.requireServable(absent, CATALOGUE))
            .as("RED STATE, observed: so does a description that is not there")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read is declared without a description");
    }

    @Test
    void a_description_over_the_budget_refuses_the_start() {
        Map<String, Object> declaration = declaration();
        calls(declaration).get(0).put("description",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET + 1));
        assertThatThrownBy(() -> DeclarationGuard.requireServable(declaration, CATALOGUE))
            .as("RED STATE, observed: a description one character over the budget refuses "
                + "the start, naming the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read's description has 501 characters");
        calls(declaration).get(0).put("description",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET));
        assertThatCode(() -> DeclarationGuard.requireServable(declaration, CATALOGUE))
            .as("and one at the budget passes").doesNotThrowAnyException();
    }

    @Test
    void a_call_with_no_arguments_or_an_undescribed_one_refuses_the_start() {
        Map<String, Object> none = declaration();
        calls(none).get(0).put("arguments", List.of());
        assertThatThrownBy(() -> DeclarationGuard.requireServable(none, CATALOGUE))
            .as("RED STATE, observed: a call with no arguments refuses the start")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read is declared with no arguments");
        Map<String, Object> undescribed = declaration();
        argument(undescribed).put("description", " ");
        assertThatThrownBy(() -> DeclarationGuard.requireServable(undescribed, CATALOGUE))
            .as("RED STATE, observed: an argument with no description refuses the start, "
                + "naming the argument")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read declares address without a description");
    }

    @Test
    void a_refusal_the_catalogue_does_not_declare_refuses_the_start() {
        Map<String, Object> declaration = declaration();
        calls(declaration).get(0).put("refusals", List.of("NOT_FOUND", "SCOPE_VANISHED"));
        assertThatThrownBy(() -> DeclarationGuard.requireServable(declaration, CATALOGUE))
            .as("RED STATE, observed: a call naming a reason the catalogue lacks refuses "
                + "the start, naming the reason")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("memory_read is declared to refuse with [SCOPE_VANISHED]");
    }

    @Test
    void a_declaration_without_its_calls_refuses_the_start() {
        Map<String, Object> declaration = declaration();
        declaration.remove("calls");
        assertThatThrownBy(() -> DeclarationGuard.requireServable(declaration, CATALOGUE))
            .as("RED STATE, observed: a declaration missing a member the checks read is "
                + "refused rather than passed for having nothing to check")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("the declaration is declared without calls");
    }

    // ======================================================================
    // The catalogue
    // ======================================================================

    @Test
    void the_catalogue_with_stubbed_texts_passes() {
        assertThatCode(() -> ReasonCatalogue.requireComplete(stubbed()))
            .doesNotThrowAnyException();
    }

    @Test
    void a_raisable_reason_missing_from_the_catalogue_refuses_and_is_named() {
        Map<String, ReasonCatalogue.Reason> gap = stubbed();
        gap.remove("CONTENT_OVERSIZE");
        assertThatThrownBy(() -> ReasonCatalogue.requireComplete(gap))
            .as("RED STATE, observed: a catalogue without a reason the domain raises is "
                + "refused, naming it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CONTENT_OVERSIZE");
    }

    @Test
    void a_collapsed_reason_is_covered_by_the_code_it_is_answered_with() {
        Map<String, ReasonCatalogue.Reason> gap = stubbed();
        gap.remove(ReasonCatalogue.NOT_FOUND);
        assertThatThrownBy(() -> ReasonCatalogue.requireComplete(gap))
            .as("RED STATE, observed: ENTRY_ABSENT and SCOPE_UNRESOLVED are answered as "
                + "NOT_FOUND, so without it both are undeclared")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ENTRY_ABSENT")
            .hasMessageContaining("SCOPE_UNRESOLVED");
    }

    @Test
    void a_reason_this_surface_adds_is_held_to_the_same_rule() {
        Map<String, ReasonCatalogue.Reason> gap = stubbed();
        gap.remove("UNEXPECTED_FAILURE");
        assertThatThrownBy(() -> ReasonCatalogue.requireComplete(gap))
            .as("RED STATE, observed: a reason of the assistant surface is required as the "
                + "domain's are")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNEXPECTED_FAILURE");
    }

    @Test
    void a_reason_declared_without_a_pattern_refuses() {
        Map<String, ReasonCatalogue.Reason> blank = stubbed();
        blank.put("TYPE_UNKNOWN", texts(blank.get("TYPE_UNKNOWN"), facts -> " ",
            facts -> "Write one of the types."));
        assertThatThrownBy(() -> ReasonCatalogue.requireComplete(blank))
            .as("RED STATE, observed: a reason whose message is blank refuses, naming it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TYPE_UNKNOWN is declared without a message");
    }

    @Test
    void a_reason_whose_text_needs_a_fact_it_never_receives_refuses() {
        Map<String, ReasonCatalogue.Reason> unbuildable = stubbed();
        unbuildable.put("CONTENT_ABSENT", texts(unbuildable.get("CONTENT_ABSENT"),
            facts -> "The content is missing.",
            facts -> ReasonCatalogue.Facts.none().call().toString()));
        assertThatThrownBy(() -> ReasonCatalogue.requireComplete(unbuildable))
            .as("RED STATE, observed: a text that asks for a fact no refusal supplies is "
                + "found at start, not by the first caller refused")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CONTENT_ABSENT is declared with a text that does not build");
    }

    // ======================================================================

    /** One call that keeps every rule. */
    private static Map<String, Object> declaration() {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("calls", new ArrayList<>(List.of(call("memory_read", "Reads one entry."))));
        return declaration;
    }

    private static Map<String, Object> call(String name, String description) {
        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("name", "address");
        argument.put("description", "the complete address of the entry");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("call", name);
        call.put("description", description);
        call.put("arguments", new ArrayList<>(List.of(argument)));
        call.put("refusals", List.of("NOT_FOUND"));
        return call;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> calls(Map<String, Object> declaration) {
        return (List<Map<String, Object>>) declaration.get("calls");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argument(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) calls(declaration).get(0).get("arguments")).get(0);
    }

    /** The catalogue the service carries, its texts replaced by text of the test's own. */
    private static Map<String, ReasonCatalogue.Reason> stubbed() {
        Map<String, ReasonCatalogue.Reason> stubbed = new LinkedHashMap<>();
        ReasonCatalogue.byCode().forEach((code, reason) -> stubbed.put(code,
            texts(reason, facts -> "Something about " + code + ".", facts -> "Do this.")));
        return stubbed;
    }

    private static ReasonCatalogue.Reason texts(ReasonCatalogue.Reason kept,
                                                Function<ReasonCatalogue.Facts, String> message,
                                                Function<ReasonCatalogue.Facts, String> remedy) {
        return new ReasonCatalogue.Reason(kept.code(), kept.steps(), kept.repeat(), kept.reach(),
            message, remedy);
    }
}
