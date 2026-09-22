package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.tenancy.TenantBound;
import ai.kumbuka.memory.tenancy.TenantContext;
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

    /**
     * The tenant id the platform's own selection stands under (V5).
     *
     * <p>The same value as {@link #DEFAULT_SCOPE} and a different statement:
     * there it means "no scope in particular", here "no tenant in particular".
     * Two constants rather than one used twice, because a reader of either
     * call site should not have to work out which of the two meanings applies,
     * and because the two could in principle be given different values without
     * either reading becoming wrong.
     *
     * <p>Not a tenant, for the same reason it is not a scope: the platform
     * allocates tenants with {@code gen_random_uuid()}.
     */
    public static final UUID PLATFORM_TENANT = new UUID(0L, 0L);

    @Inject EntityManager em;
    @Inject TenantContext tenants;

    /**
     * The selection that governs a scope, in the order the estate's
     * arrangement implies.
     *
     * <p>Three candidates, most specific first:
     *
     * <ol>
     *   <li>this tenant's row for this scope — a scope that was given its own
     *       selection;</li>
     *   <li>this tenant's row for no scope in particular — the estate's own
     *       default, which is what V4 seeds;</li>
     *   <li>the platform's row for no tenant and no scope in particular — what
     *       V5 seeds, and what serves every tenant that came into existence
     *       after this database was migrated.</li>
     * </ol>
     *
     * <h2>Why the order is written out rather than left to the rows</h2>
     *
     * Three rows can match and exactly one answer is right. Without the
     * explicit ordering the answer would be whichever row the executor
     * happened to return first — stable in a test, stable right up until an
     * autovacuum or a plan change moved it, and then wrong for one tenant in
     * production with nothing in the estate having changed. An estate that set
     * its own selection would silently get the platform's.
     *
     * <h2>One statement rather than a fallback in Java</h2>
     *
     * Unchanged from V4's arrangement and for the same reason: the precedence
     * is part of the question. Written as up to three reads with a fallback
     * between them it would be the same rule in a place a reader has to
     * reconstruct it, and would cost up to three round trips for the common
     * case — a tenant with no row of its own, which after V5 is the expected
     * state rather than a defect.
     *
     * <p>Empty now means all three are missing, which is a database whose V4
     * and V5 seeds both failed. Both verify themselves and raise, so the
     * caller still treats empty as a defect and not as "no preference".
     */
    @Transactional
    public Optional<Selection> forScope(UUID scopeId) {
        UUID tenant = tenants.current();

        List<?> rows = em.createNativeQuery("""
                SELECT types, include_global
                  FROM memory.digest_preference
                 WHERE (tenant_id = :tenant   AND scope_id = :scope)
                    OR (tenant_id = :tenant   AND scope_id = :noScope)
                    OR (tenant_id = :platform AND scope_id = :noScope)
                 ORDER BY CASE
                            WHEN tenant_id = :tenant AND scope_id = :scope THEN 1
                            WHEN tenant_id = :tenant                       THEN 2
                            ELSE                                                3
                          END
                 LIMIT 1
                """)
            .setParameter("tenant", tenant)
            .setParameter("scope", scopeId)
            .setParameter("noScope", DEFAULT_SCOPE)
            .setParameter("platform", PLATFORM_TENANT)
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
