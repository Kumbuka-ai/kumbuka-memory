/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two names of an entry type: the wire name every answer writes, in the
 * vocabulary form of C7.6, and the stored name the table holds, which no
 * migration rewrites. The expectations are the contract's spelling and the
 * values of the CHECK of V1, written here.
 */
class EntryTypeTest {

    @Test
    void the_open_question_is_hyphenated_on_the_wire_and_stored_with_an_underscore() {
        assertThat(EntryType.OPEN_QUESTION.wire()).isEqualTo("open-question");
        assertThat(EntryType.OPEN_QUESTION.stored()).isEqualTo("open_question");
    }

    @Test
    void every_other_type_has_one_name_for_both() {
        assertThat(Arrays.stream(EntryType.values())
                .filter(type -> type != EntryType.OPEN_QUESTION)
                .map(type -> type.wire() + "=" + type.stored()))
            .containsExactly("constraint=constraint", "decision=decision",
                "convention=convention", "glossary=glossary", "status=status");
    }

    @Test
    void the_input_takes_the_six_wire_names_and_the_earlier_spelling_beside_its_vocabulary_form() {
        assertThat(EntryType.acceptedNames()).containsExactly("constraint", "decision",
            "convention", "glossary", "status", "open-question", "open_question");
    }

    @Test
    void both_spellings_denote_the_open_question_and_nothing_else_does() {
        assertThat(EntryType.of("open-question")).contains(EntryType.OPEN_QUESTION);
        assertThat(EntryType.of("open_question")).contains(EntryType.OPEN_QUESTION);
        for (String other : List.of("open question", "OPEN_QUESTION", "openquestion", "")) {
            assertThat(EntryType.of(other)).as("'%s'", other).isEmpty();
        }
        assertThat(EntryType.of(null)).isEmpty();
    }
}
