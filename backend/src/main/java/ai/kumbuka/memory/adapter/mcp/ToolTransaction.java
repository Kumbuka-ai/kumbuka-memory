package ai.kumbuka.memory.adapter.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.function.Supplier;

/**
 * One transaction around one tool call, closed only once its answer is built.
 *
 * <p>The verb opens a transaction of its own when called alone, and commits it
 * as it returns — before the adapter has turned the result into an answer. A
 * failure while building the answer would then follow a committed write, and
 * the refusal it produces says "Nothing was changed". Joining the verb into
 * this transaction is what makes that sentence true on every path: the
 * write commits after the answer exists, or not at all.
 *
 * <p>The tenant is bound inside it by the verb surface itself, which is
 * {@code @TenantBound}; this class binds nothing and reads nothing.
 */
@ApplicationScoped
public class ToolTransaction {

    @Transactional
    public <T> T run(Supplier<T> call) {
        return call.get();
    }
}
