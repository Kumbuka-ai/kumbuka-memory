/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every verb is declared in a form the router can carry.
 *
 * <p>The router builds its forms from the pinned declaration alone (ADR-0039)
 * and knows a closed set of answer shapes; a shape outside it stops the
 * router's catalogue from loading. The set is written here by hand, as the
 * router names it for memory ({@code VerbCatalog.ResponseShape},
 * {@code Kumbuka-ai/platform} at main, read 2026-11-12), and never read off
 * {@link AnswerShape}: a check against the service's own enum would pass
 * whatever the service declared.
 */
class RouterFormTest {

    /** The answer shapes the router names for memory. */
    private static final List<String> ROUTER_SHAPES = List.of("ENTRY", "ENTRY_CREATED",
        "ENTRY_LISTING", "WITHDRAWN", "DIGEST_VIEW");

    @Test
    void every_verb_answers_in_a_shape_the_router_names() {
        assertThat(outsideTheRouter(declaredShapes()))
            .as("a shape the router does not name stops its catalogue from loading")
            .isEmpty();
    }

    /**
     * The tag verbs are custom methods on the entry: the router sends
     * {@code POST} with {@code :<verb>} to the item address for an
     * {@code ITEM_TRANSITION}, which is the route this service serves. Their
     * answer is the entry, its tags among its fields.
     */
    @Test
    void the_tag_verbs_are_item_transitions_answering_the_entry() {
        for (AssistantVerb verb : List.of(AssistantVerb.TAG_ATTACH, AssistantVerb.TAG_DETACH)) {
            SurfaceDeclaration.Form form = SurfaceDeclaration.formOf(verb);
            assertThat(form.effect().name()).as("%s's effect class", verb.call())
                .isEqualTo("ITEM_TRANSITION");
            assertThat(form.answer().name()).as("%s's answer", verb.call())
                .isEqualTo("ENTRY");
            assertThat(form.answer().fields()).as("%s names tags under fields", verb.call())
                .contains("tags");
        }
    }

    @Test
    void the_check_reports_a_shape_the_router_does_not_name() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("memory_read", "ENTRY");
        planted.put("memory_tag_attach", "ENTRY_TAGS");
        planted.put("memory_query", "OBJECT_LISTING");
        assertThat(outsideTheRouter(planted))
            .as("RED STATE, observed: a shape of the service's own and a shape the router "
                + "names for another service are both reported, a known one is not")
            .containsExactly("memory_tag_attach answers ENTRY_TAGS",
                "memory_query answers OBJECT_LISTING");
    }

    /** Every call by its identity, with the name of the shape it answers in. */
    private static Map<String, String> declaredShapes() {
        Map<String, String> shapes = new LinkedHashMap<>();
        Arrays.stream(AssistantVerb.values()).forEach(verb ->
            shapes.put(verb.call(), SurfaceDeclaration.formOf(verb).answer().name()));
        assertThat(shapes).as("the service declares calls; none would pass for nothing checked")
            .isNotEmpty();
        return shapes;
    }

    private static List<String> outsideTheRouter(Map<String, String> shapes) {
        return shapes.entrySet().stream()
            .filter(call -> !ROUTER_SHAPES.contains(call.getValue()))
            .map(call -> call.getKey() + " answers " + call.getValue())
            .toList();
    }
}
