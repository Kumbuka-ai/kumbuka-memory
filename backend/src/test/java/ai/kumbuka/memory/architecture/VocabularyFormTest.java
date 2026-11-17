/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import ai.kumbuka.memory.domain.EntryType;
import ai.kumbuka.memory.domain.SortKey;
import ai.kumbuka.memory.domain.Withdrawal;
import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.AssistantVerb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every value a vocabulary of this service can take is lowercase words joined
 * by a hyphen, never by an underscore (C7.6).
 *
 * <h2>Over which values</h2>
 *
 * The vocabularies an answer writes: the entry types ({@link EntryType#wire()},
 * which are also the selectors the declaration fixes), the orders
 * ({@link SortKey#wireNames()}) and their directions
 * ({@link SortKey.Direction#wireNames()}), and the values of {@code outcome}
 * in the answer to a withdrawal, which the answer writes as the constant's
 * name in lower case. And the fixed values the declaration states for an
 * argument: every {@code enum}, every {@code enum} of an array's items and
 * every textual {@code default} of every argument of every call.
 *
 * <h2>The two named exceptions</h2>
 *
 * {@link #JSON_MEMBER_NAMES}: a value that names a JSON member is written as
 * that member is (C7.6), so the order {@code created_at} keeps its underscore
 * wherever it stands. {@link #INPUT_TOLERANCE}: the earlier spellings the input
 * still takes for one release, declared beside their vocabulary form as values
 * a caller may write. The tolerance holds for declared input values only; an
 * answer vocabulary never carries one. Each list names values that exist, so
 * that when a value goes its line goes with it.
 *
 * <p>Runs as a plain unit test: it reads the enums and the call declarations
 * and needs neither a database nor the texts of the bundles.
 */
class VocabularyFormTest {

    /** The vocabulary form of C7.6, written here from the contract. */
    private static final Pattern FORM = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Values that name a JSON member, written as that member is (C7.6). */
    static final Set<String> JSON_MEMBER_NAMES = Set.of("created_at");

    /**
     * The earlier spellings the input takes until the next release, and only as
     * input. Removing the tolerance is removing its line here.
     */
    static final Set<String> INPUT_TOLERANCE = Set.of("open_question");

    /**
     * One vocabulary and the values it can take.
     *
     * @param name   where the values come from, as an offence names it
     * @param values the values
     * @param input  whether the values are declared input, where the tolerance holds
     */
    record Vocabulary(String name, List<String> values, boolean input) {
    }

    @Test
    void every_vocabulary_value_is_lowercase_words_joined_by_a_hyphen() {
        assertThat(offences(vocabularies()))
            .as("a vocabulary value is lowercase words joined by a hyphen, never by an "
                + "underscore (C7.6): write it as the vocabulary form, or, where it names a "
                + "JSON member, add it to JSON_MEMBER_NAMES")
            .isEmpty();
    }

    @Test
    void every_named_exception_names_a_value_that_exists() {
        List<String> all = vocabularies().stream()
            .flatMap(vocabulary -> vocabulary.values().stream()).toList();
        List<String> stale = new ArrayList<>();
        for (String exempt : List.of(JSON_MEMBER_NAMES, INPUT_TOLERANCE).stream()
                .flatMap(Set::stream).sorted().toList()) {
            if (!all.contains(exempt)) {
                stale.add(exempt);
            }
        }
        assertThat(stale)
            .as("an exception for a value nobody declares exempts nothing and hides the next "
                + "one: remove its line")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_each_offence_it_exists_for() {
        List<Vocabulary> planted = List.of(
            new Vocabulary(Planted.class.getSimpleName(),
                Arrays.stream(Planted.values()).map(Planted::wire).toList(), false),
            new Vocabulary("planted input", List.of("open_question", "Decision", "created_at"),
                true));
        assertThat(offences(planted))
            .as("RED STATE, observed: a planted enum value with an underscore is reported, and "
                + "so is the tolerance where an answer writes it; a capital is reported in "
                + "input as well; the tolerance as input and a JSON member name pass")
            .containsExactly(
                "Planted: 'open_question'",
                "Planted: 'some_other_kind'",
                "planted input: 'Decision'");
    }

    /**
     * What the detection is handed for its red state: an answer vocabulary as
     * an enum spells it, one value with an underscore that no list exempts, and
     * the tolerance, which an answer never writes.
     */
    enum Planted {
        DECISION("decision"),
        OPEN_QUESTION("open_question"),
        SOME_OTHER_KIND("some_other_kind");

        private final String wire;

        Planted(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    // ======================================================================

    static List<String> offences(List<Vocabulary> vocabularies) {
        List<String> offences = new ArrayList<>();
        for (Vocabulary vocabulary : vocabularies) {
            for (String value : vocabulary.values()) {
                boolean exempt = JSON_MEMBER_NAMES.contains(value)
                    || (vocabulary.input() && INPUT_TOLERANCE.contains(value));
                if (!exempt && !FORM.matcher(value).matches()) {
                    offences.add(vocabulary.name() + ": '" + value + "'");
                }
            }
        }
        return offences;
    }

    /** Every vocabulary of the service, read off the enums and the call declarations. */
    static List<Vocabulary> vocabularies() {
        List<Vocabulary> all = new ArrayList<>();
        all.add(new Vocabulary("EntryType.wire",
            Arrays.stream(EntryType.values()).map(EntryType::wire).toList(), false));
        all.add(new Vocabulary("SortKey", SortKey.wireNames(), false));
        all.add(new Vocabulary("SortKey.Direction", SortKey.Direction.wireNames(), false));
        all.add(new Vocabulary("outcome", Arrays.stream(Withdrawal.Outcome.values())
            .map(outcome -> outcome.name().toLowerCase(Locale.ROOT)).toList(), false));
        for (AssistantVerb verb : AssistantVerb.values()) {
            for (Argument argument : verb.arguments()) {
                List<String> declared = declaredValues(argument.keywords());
                if (!declared.isEmpty()) {
                    all.add(new Vocabulary(verb.call() + "." + argument.name(), declared, true));
                }
            }
        }
        assertThat(all.stream().filter(Vocabulary::input).toList())
            .as("the calls declare fixed values; finding none would mean this reads the wrong "
                + "place and passes because of it")
            .isNotEmpty();
        return all;
    }

    /** The fixed values an argument's keywords state: its enum, its items' enum, its default. */
    private static List<String> declaredValues(Map<String, Object> keywords) {
        List<String> values = new ArrayList<>(strings(keywords.get("enum")));
        if (keywords.get("items") instanceof Map<?, ?> items) {
            values.addAll(strings(items.get("enum")));
        }
        if (keywords.get("default") instanceof String fallback) {
            values.add(fallback);
        }
        return values;
    }

    private static List<String> strings(Object list) {
        if (!(list instanceof List<?> elements)) {
            return List.of();
        }
        return elements.stream().map(String::valueOf).toList();
    }
}
