package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.domain.Withdrawal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * An edition whose withdrawal fails in a way nobody foresaw.
 *
 * <p>Enabled only by the test profile that selects it. It throws after the
 * verb has resolved the scope, found the entry and checked the token — deep
 * in a path that is otherwise working — which is where an unforeseen failure
 * would come from in a real edition.
 *
 * <p>The failure's message and its cause's message each carry a marker, the
 * way a database's refusal carries the row it rejected. Neither may reach the
 * log or the caller.
 */
@Alternative
@ApplicationScoped
public class FailingWithdrawal implements Withdrawal {

    public static final String MESSAGE_MARKER = "marker-in-the-failure-message";
    public static final String CAUSE_MARKER = "marker-in-the-cause-message";

    @Override
    public Outcome withdraw(Memory entry) {
        throw new IllegalStateException("the edition's withdrawal broke, " + MESSAGE_MARKER,
            new IllegalArgumentException("the row it rejected, " + CAUSE_MARKER));
    }
}
