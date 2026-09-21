package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.MemoryException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key grammar accepts exactly what the table accepts, and the possessive
 * form of it accepts exactly what the ordinary form does.
 *
 * <h2>Why the equivalence is measured and not argued</h2>
 *
 * The patterns in {@link AddressParser} are written with possessive
 * quantifiers so that their linearity is a property of the pattern rather than
 * of a paragraph explaining why backtracking cannot occur. A possessive
 * quantifier does change what a pattern accepts in general — it is only safe
 * here because the separator class and the segment class are disjoint — so the
 * claim that nothing moved is exactly the kind of claim that needs a mechanism
 * behind it rather than a comment.
 *
 * <p>The ordinary forms below are written out again, as the shapes they
 * replaced. That is a duplication with a purpose: it is the only way the two
 * can be compared at all, and a test that read the pattern out of the class it
 * is checking would be comparing something with itself.
 */
class AddressGrammarTest {

    /** The shape V1's {@code memory_key_format} carries, before the rewrite. */
    private static final Pattern KEY_BACKTRACKING =
        Pattern.compile("^[a-z0-9]+([.-][a-z0-9]+)*$");

    /** The scope shape, before the rewrite. */
    private static final Pattern SCOPE_BACKTRACKING =
        Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Test
    void the_possessive_key_pattern_accepts_exactly_what_the_ordinary_one_does() {
        List<String> disagreements = new ArrayList<>();
        for (String candidate : corpus()) {
            boolean ordinary = KEY_BACKTRACKING.matcher(candidate).matches();
            boolean possessive = AddressParser.acceptsKey(candidate);
            if (ordinary != possessive) {
                disagreements.add(candidate + ": ordinary=" + ordinary
                    + " possessive=" + possessive);
            }
        }
        assertThat(disagreements)
            .as("a possessive quantifier never gives characters back, which is safe here "
                + "only because the separator class and the segment class are disjoint. "
                + "That is the claim, and this is the measurement of it")
            .isEmpty();
    }

    @Test
    void the_possessive_scope_pattern_accepts_exactly_what_the_ordinary_one_does() {
        List<String> disagreements = new ArrayList<>();
        for (String candidate : corpus()) {
            boolean ordinary = SCOPE_BACKTRACKING.matcher(candidate).matches();
            boolean possessive = AddressParser.acceptsScope(candidate);
            if (ordinary != possessive) {
                disagreements.add(candidate + ": ordinary=" + ordinary
                    + " possessive=" + possessive);
            }
        }
        assertThat(disagreements).isEmpty();
    }

    @Test
    void the_corpus_contains_both_answers_so_the_comparison_is_not_vacuous() {
        List<String> corpus = corpus();

        assertThat(corpus).anyMatch(c -> KEY_BACKTRACKING.matcher(c).matches());
        assertThat(corpus)
            .as("RED STATE, observed on every run: two patterns that rejected everything "
                + "would agree on every input, and the comparison above would pass "
                + "against a grammar that accepts nothing at all")
            .anyMatch(c -> !KEY_BACKTRACKING.matcher(c).matches());
    }

    @Test
    void the_underscore_the_selector_grammar_admits_is_refused_by_the_key_grammar() {
        assertThatThrownBy(() -> AddressParser.ofKey("est", "my_notes.first"))
            .isInstanceOfSatisfying(MemoryException.class, e -> assertThat(e.reason())
                .as("the surface contract gives the selector as [a-z][a-z0-9_-]{0,15} and "
                    + "the table's check constraint admits no underscore at all. The "
                    + "narrower of the two binds, so that a key cannot reach a constraint "
                    + "violation at flush time instead of a refusal a caller can read")
                .isEqualTo(MemoryException.Reason.KEY_MALFORMED));

        assertThatCode(() -> AddressParser.ofKey("est", "my-notes.first"))
            .as("and the dash, which both admit, goes through")
            .doesNotThrowAnyException();
    }

    /**
     * The inputs both forms are compared over.
     *
     * <p>Ordinary keys, the shapes that are meant to be refused, and the
     * adversarial ones: long runs of segments, a long run followed by a
     * character that cannot match, and the empty string. The last group is the
     * reason the rewrite happened, so it is the group that has to be in here.
     */
    private static List<String> corpus() {
        List<String> corpus = new ArrayList<>(List.of(
            "decision.storage-engine", "decision.a", "a.b", "a-b", "a", "a1",
            "constraint.tenant-isolation.rls", "openquestion.item-25",
            "", ".", "-", "a.", ".a", "a-", "-a", "a..b", "a--b", "a.-b",
            "A.b", "a.B", "my_notes.first", "a b", "a/b", "a:b", "a@b", "é.b",
            "1.2", "1-2-3.4"));

        corpus.add("a" + "-a".repeat(200));
        corpus.add("a" + ".a".repeat(200));
        corpus.add("a".repeat(500));
        // The shape a backtracking analysis is about: a long run of segments
        // followed by one character that can never match, so the engine would
        // have to try every way of splitting what it already read.
        corpus.add("a" + "-a".repeat(60) + "!");
        corpus.add("a" + ".a".repeat(60) + "!");
        return List.copyOf(corpus);
    }
}
