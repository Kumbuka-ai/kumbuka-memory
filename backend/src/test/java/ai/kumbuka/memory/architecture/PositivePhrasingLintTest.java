/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text that tells a caller what to do is phrased positively, as the act
 * to perform (DEC-0043 R21; code-conventions/service-construction.md, "texts
 * addressed to a caller", point 4).
 *
 * <h2>Over which texts</h2>
 *
 * The files of the instructing bundles: the remedies, the next steps, and the
 * descriptions of the tools, the arguments and the values
 * ({@link #INSTRUCTING}). The messages of the refusals and the notices of the
 * protocol state a finding and may state it in the negative; they are not
 * read here.
 *
 * <h2>What it refuses</h2>
 *
 * The words R21 lists, as whole words and in any case. It keeps no list of
 * exceptions: a text that needs one of these words is rewritten until it does
 * not, because the distinction between a negated fact and a prohibition is
 * where the rule slips (DEC-0043, A8).
 *
 * <p>The pattern is kumbuka-dispatch's, which states it as the binding form
 * for every service that copies this lint: the phrase is matched as
 * {@code \binstead\s+of\b}, so a line break or a second space between its
 * words does not hide it, and the contraction as {@code n't\b}, at the end
 * of any word.
 *
 * <h2>How many texts it reads</h2>
 *
 * At least {@link #FLOOR}, so that a bundle read empty or not found at all
 * cannot pass as a bundle with nothing to refuse. The reference asks for more
 * than 100 of its 139 texts; this service has 80, and the floor keeps the
 * same margin below them.
 */
class PositivePhrasingLintTest {

    /** The bundles whose texts instruct, by the names of their files. */
    static final List<String> INSTRUCTING = List.of("remedy", "next", "tool", "argument",
        "value");

    /** The words of DEC-0043 R21, whole and in any case; "n't" ends a word. */
    private static final Pattern FORBIDDEN = Pattern.compile(
        "\\b(not|never|no|none|nothing|nobody|neither|nor|cannot|without|avoid|unless|"
            + "refrain)\\b|n't\\b|\\binstead\\s+of\\b",
        Pattern.CASE_INSENSITIVE);

    /** The fewest instructing texts the lint must have read. */
    static final int FLOOR = 60;

    @Test
    void every_instructing_text_is_phrased_as_the_act_to_perform() {
        List<String> offences = new ArrayList<>();
        int read = 0;
        for (String bundle : INSTRUCTING) {
            Path file = MessageBundleTest.messages().resolve(bundle + ".properties");
            assertThat(Files.isRegularFile(file)).as("%s exists", file).isTrue();
            Properties texts = MessageBundleTest.texts(file);
            assertThat(texts).as("%s holds texts", file).isNotEmpty();
            read += texts.size();
            for (String key : texts.stringPropertyNames()) {
                offences.addAll(prohibitions(bundle + "." + key, texts.getProperty(key)));
            }
        }
        assertThat(read).as("the lint reads every instructing text").isGreaterThan(FLOOR);
        assertThat(offences)
            .as("a model carries out an instruction more reliably than it inverts a "
                + "prohibition; state the act, and a fact as what is there (DEC-0043 R21)")
            .isEmpty();
    }

    @Test
    void the_lint_reports_each_word_it_exists_for() {
        assertThat(prohibitions("planted", "Do not send the type again."))
            .as("RED STATE, observed: 'do not' in a remedy is reported")
            .hasSize(1);
        assertThat(prohibitions("planted", "Don't send it; NEVER repeat it, instead of "
                + "reading it."))
            .as("RED STATE, observed: n't, a word in capitals and a phrase are reported")
            .hasSize(3);
        assertThat(prohibitions("planted", "Send a notice to the nobleman's node, once "
                + "it is unlocked."))
            .as("RED STATE, observed: a forbidden word inside a longer one is not a match")
            .isEmpty();
        assertThat(prohibitions("planted", "Read the entry instead\nof sending it again."))
            .as("RED STATE, observed: the phrase across a line break is reported")
            .hasSize(1);
        assertThat(prohibitions("planted", "Read the entry instead  of sending it again."))
            .as("RED STATE, observed: and across a second space")
            .hasSize(1);
    }

    // ======================================================================

    private static List<String> prohibitions(String where, String text) {
        List<String> found = new ArrayList<>();
        Matcher match = FORBIDDEN.matcher(text);
        while (match.find()) {
            found.add(where + ": '" + match.group().toLowerCase(Locale.ROOT) + "' in \""
                + text + "\"");
        }
        return found;
    }
}
