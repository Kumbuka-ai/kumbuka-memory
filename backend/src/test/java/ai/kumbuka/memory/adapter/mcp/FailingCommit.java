package ai.kumbuka.memory.adapter.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/**
 * A tool transaction whose commit fails, once armed.
 *
 * <p>Enabled only by the test profile that selects it. The call runs as it
 * would, its answer is built, and only then does the commit fail: a
 * synchronization registered inside the transaction refuses before
 * completion. That is the last moment a tool call can fail, after everything
 * the call itself checks has passed.
 *
 * <p>Armed per probe and disarmed after it, so that the same profile can read
 * the entry back through an ordinary call.
 */
@Alternative
@ApplicationScoped
public class FailingCommit extends ToolTransaction {

    public static final String MARKER = "marker-in-the-commit-failure";

    private static volatile boolean armed;

    @Inject TransactionSynchronizationRegistry registry;

    public static void arm() {
        armed = true;
    }

    public static void disarm() {
        armed = false;
    }

    @Override
    @Transactional
    public <T> T run(Supplier<T> call) {
        T answer = call.get();
        if (armed) {
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    throw new IllegalStateException("the commit broke, " + MARKER);
                }

                @Override
                public void afterCompletion(int status) {
                    // Nothing to observe; the probe reads the entry back.
                }
            });
        }
        return answer;
    }
}
