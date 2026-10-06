package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Actor;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * An identity derivation that fails unforeseen for one subject.
 *
 * <p>Enabled only by the test profile that selects it. For every other
 * subject it answers exactly as the service's own does, so the same profile
 * can show a call failing and a neighbouring call succeeding.
 */
@Alternative
@RequestScoped
public class FailingCallerActor extends CallerActor {

    public static final String FAILING_SUBJECT = "failing-subject";

    @Override
    public Actor current() {
        if (identity != null && FAILING_SUBJECT.equals(identity.getPrincipal().getName())) {
            throw new IllegalStateException("the identity derivation broke");
        }
        return super.current();
    }
}
