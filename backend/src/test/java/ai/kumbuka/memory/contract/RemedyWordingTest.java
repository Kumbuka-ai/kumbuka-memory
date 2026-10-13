/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.surface.ReasonCatalogue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every remedy in the reason catalogue is stated as the act to perform, and
 * none as what to avoid (DEC-0042).
 *
 * <p>A test case and not a start check: whether the catalogue's start check
 * should refuse a negated remedy is part of the decision on where a service
 * keeps its messages, and is not taken here. The words are the ones a
 * prohibition is written with; a remedy that needs one of them can be
 * written as the act it implies.
 *
 * <p>The red run, observed on 2026-10-13 against the catalogue before the
 * rewording: {@code CURSOR_MALFORMED} and {@code CURSOR_FOREIGN} ("repeat
 * {query} without after") were found. The second test keeps that state in
 * view on every build.
 */
class RemedyWordingTest {

    /** The words a prohibition is written with. */
    static final List<String> NEGATIONS =
        List.of("not", "no", "never", "without", "don't", "cannot");

    @Test
    void every_remedy_is_an_instruction_stated_positively() {
        Map<String, String> remedies = new LinkedHashMap<>();
        ReasonCatalogue.declared().forEach(reason -> remedies.put(reason.code(),
            reason.remedy()));

        assertThat(negated(remedies))
            .as("a remedy names the act to perform; a caller that is a model carries out an "
                + "instruction more reliably than it inverts a prohibition (DEC-0042)")
            .isEmpty();
    }

    @Test
    void a_remedy_written_as_a_prohibition_is_found() {
        Map<String, String> before = Map.of(
            "CURSOR_MALFORMED", "repeat {query} without after",
            "SEND", "Send the complete address.",
            "DONT", "Don't send a cursor.");

        assertThat(negated(before))
            .as("RED STATE: the remedies the catalogue carried before, and a contraction")
            .containsOnlyKeys("CURSOR_MALFORMED", "DONT");
    }

    /** The codes whose remedy contains a negating word, with the words found. */
    static Map<String, List<String>> negated(Map<String, String> remedies) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        remedies.forEach((code, remedy) -> {
            List<String> words = new ArrayList<>();
            Arrays.stream(remedy.toLowerCase(Locale.ROOT).replace('’', '\'')
                    .split("[^a-z']+"))
                .filter(NEGATIONS::contains)
                .forEach(words::add);
            if (!words.isEmpty()) {
                found.put(code, words);
            }
        });
        return found;
    }
}
