package ai.kumbuka.memory.domain;

import java.util.Optional;

/**
 * What an {@code update} changes.
 *
 * <p>Three optionals, and the optional is doing real work: {@code reference}
 * is nullable in the table, so "leave it alone" and "clear it" are different
 * instructions and a plain null could only express one of them. An empty
 * optional is the field not mentioned; a present optional holding null is the
 * field cleared.
 *
 * <p>Scope and key are absent by construction. They are fixed for the life of
 * an entry, and a field that cannot be changed has no place in the type that
 * says what changed — a caller that sends one is refused by name rather than
 * having it silently ignored.
 */
public record Patch(Optional<String> type, Optional<String> content,
                    Optional<String> reference) {

    /** Whether this patch asks for nothing at all. */
    public boolean isEmpty() {
        return type.isEmpty() && content.isEmpty() && reference.isEmpty();
    }
}
