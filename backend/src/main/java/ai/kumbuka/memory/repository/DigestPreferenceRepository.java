package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the digest's type selection, and only reads it.
 *
 * <p>There is no write method and there is no grant to write with: the runtime
 * role holds {@code SELECT} on {@code memory.digest_preference} and nothing
 * else (V4). Changing an estate's selection is an operator act against the
 * database. A write method here would be code that cannot run, which is worse
 * than no method at all — it reads as a capability the service has.
 *
 * <h2>Why this statement is native, and it is the only one that is</h2>
 *
 * The selection is a PostgreSQL {@code text[]}. JPQL has no array expression
 * and JPA no portable mapping for one; an entity carrying a custom array type
 * would additionally make this look like a table the service maps and could
 * write, which is the impression V4 exists to prevent. This is the enumerated
 * native case, and the reason is written here rather than assumed.
 */
@ApplicationScoped
@TenantBound
public class DigestPreferenceRepository {

    /**
     * The scope id the estate's default stands under.
     *
     * <p>Not a scope: {@code platform.scope} allocates with
     * {@code gen_random_uuid()}, which never produces the all-zero value, so a
     * lookup for a real scope can never collide with it. It reads as "no scope
     * in particular", which is what a default is.
     */
    public static final UUID DEFAULT_SCOPE = new UUID(0L, 0L);

    @Inject EntityManager em;

    /**
     * The selection for a scope: its own where it has one, the estate's
     * default otherwise.
     *
     * <p>One statement, because the precedence is part of the question. Two
     * reads with a fallback in Java would be the same rule written where a
     * reader has to reconstruct it, and would cost a round trip for every
     * digest of a scope that has no row of its own — which is every scope, in
     * an estate that never set one.
     *
     * <p>Empty means the default row is missing too, which V4 makes impossible
     * to reach by accident: it verifies its own seed and raises if it wrote
     * nothing. The caller therefore treats empty as a defect and not as "no
     * preference".
     */
    @Transactional
    public Optional<Selection> forScope(UUID scopeId) {
        List<?> rows = em.createNativeQuery("""
                SELECT types, include_global
                  FROM memory.digest_preference
                 WHERE scope_id IN (:scope, :fallback)
                 ORDER BY (scope_id = :scope) DESC
                 LIMIT 1
                """)
            .setParameter("scope", scopeId)
            .setParameter("fallback", DEFAULT_SCOPE)
            .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new Selection(stringsOf(row[0]), (Boolean) row[1]));
    }

    /**
     * The array column, as a list, whichever shape it arrives in.
     *
     * <p>Two shapes are admitted and the reason is measured rather than
     * defensive. Hibernate's native-query path hands a {@code text[]} over as
     * a Java {@code String[]} — observed on 3.33.2, 2026-09-21 — while a plain
     * JDBC read of the same column answers {@link Array}. Which one arrives is
     * a property of the layer in between, not of the column, and a mapping
     * that knew only the JDBC shape failed loudly the first time it ran. It
     * failed loudly on purpose: the message named the type it did get, which
     * is what made the fix one line rather than a hunt.
     *
     * <p>Anything else is still a defect and still says so. A column whose
     * type moved out from under this mapping would otherwise surface as a
     * {@code ClassCastException} with no mention of the column.
     */
    private static List<String> stringsOf(Object value) {
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(String::valueOf).toList();
        }
        if (value instanceof Array array) {
            try {
                return Arrays.stream((Object[]) array.getArray())
                    .map(String::valueOf)
                    .toList();
            } catch (SQLException e) {
                throw new IllegalStateException(
                    "could not read memory.digest_preference.types", e);
            }
        }
        throw new IllegalStateException(
            "memory.digest_preference.types did not come back as an array but as "
                + (value == null ? "null" : value.getClass().getName())
                + " — the column's type and this mapping have parted company");
    }

    /** What the digest carries content for, and whether it reaches the global scope. */
    public record Selection(List<String> types, boolean includeGlobal) {
    }
}
