package ai.kumbuka.memory.tenancy;

import ai.kumbuka.memory.repository.DigestPreferenceRepository;
import ai.kumbuka.memory.repository.ScopeAccessRepository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where raw SQL may be issued, and what has to stand beside it.
 *
 * <p>Three statements, each its own test.
 *
 * <p><strong>Raw SQL runs under {@code @TenantBound}.</strong> Layer 1
 * rewrites every statement the ORM builds. Raw and native SQL is not
 * rewritten — that is what makes it raw — so its only protection is the
 * database policy, and the policy only applies where the session setting was
 * bound. That binding happens in {@code @TenantBound} methods and nowhere
 * else. A class issuing raw SQL without it can read or write under whatever
 * tenant the pooled connection last carried, or under none.
 *
 * <p><strong>Native SQL appears only in {@code repository}.</strong> The
 * persistence layer exists so that "the database is reached here and nowhere
 * else" is a sentence a test can check. A native statement in another layer
 * puts a database statement where its readers are not looking for one.
 *
 * <p><strong>Every occurrence carries a written reason.</strong> Native SQL is
 * the enumerated exception to JPQL, and an exception nobody wrote down is a
 * habit. The reason stands in {@link #NATIVE_SQL_REASONS}, per class, together
 * with the number of occurrences it covers. The count is what makes the reason
 * per occurrence rather than per class: a new native statement in a class that
 * already has a reason changes the count and fails the build, so the reason has
 * to be revisited for the statement that was added rather than inherited by it.
 *
 * <p><strong>The annotation is read from the loaded class, never from the
 * file text.</strong> The file walk finds raw SQL; whether the class is bound
 * is decided by reflection. A check that matched the literal string anywhere
 * in the source would let a class that merely mentions the annotation in a
 * comment pass unguarded. A check that derives its expectation from the
 * artifact it is checking proves nothing.
 *
 * <p>Runs as a plain unit test: it reads sources and class files and needs no
 * database, which is also why it is fast enough to be worth running on every
 * build.
 */
class RawSqlArchitectureTest {

    /** The service's own root package. */
    private static final String BASE = "ai.kumbuka.memory";

    /** The one package native SQL belongs to. */
    private static final String PERSISTENCE = "repository";

    /**
     * Classes allowed to issue raw SQL without {@code @TenantBound}, each
     * with the reason it cannot cross a tenant boundary. Entries are class
     * literals rather than names, so a rename breaks the build instead of
     * quietly leaving an exemption that no longer matches anything.
     */
    private static final Set<Class<?>> ALLOWLIST = Set.of(
        // Sets app.tenant_id itself, with is_local = true. Touches no
        // tenant-scoped table, so there is no row for it to cross with.
        TenantDatabaseBinding.class,
        // Flyway beforeEachMigrate: binds the setting for the migration's own
        // transaction, at boot, before any request is served.
        TenantMigrationCallback.class);

    /**
     * Classes outside {@code repository} that issue native SQL, each with the
     * reason it is not a repository.
     *
     * <p>The same two the rule set names for the JPA access API, for the same
     * reason: both are the mechanism a repository runs under, and both belong
     * to the tenancy aspect, whose classes are byte-identical across the
     * platform's services and are not rewritten per service.
     */
    private static final Map<Class<?>, String> OUTSIDE_REPOSITORY_ALLOWED = Map.of(
        TenantDatabaseBinding.class,
        "binds app.tenant_id on the connection through set_config; it IS the "
            + "mechanism a repository would run under, so it cannot run through one",
        TenantMigrationCallback.class,
        "binds app.tenant_id for Flyway's own transaction at boot, on Flyway's JDBC "
            + "connection, before CDI serves any repository bean");

    /**
     * The written reason for every class that issues native SQL, and the
     * number of raw-SQL markers it covers.
     *
     * <p>A marker is one of {@link #RAW_SQL_MARKERS} in code, comments
     * excluded. One JDBC statement can show two of them — a connection fetched
     * and a statement prepared on it — and is counted as written.
     */
    private static final Map<Class<?>, Reasoned> NATIVE_SQL_REASONS = Map.of(
        ScopeAccessRepository.class, new Reasoned(6,
            "reads platform.scope_access, a view of the platform's read contract "
                + "that deliberately has no entity here (an entity would make it look "
                + "like a table this service maps and could write), and binds and "
                + "reads the session settings through set_config and current_setting, "
                + "which have no JPQL expression"),
        DigestPreferenceRepository.class, new Reasoned(1,
            "reads a PostgreSQL text[] column; JPQL has no array expression and JPA "
                + "no portable mapping for one, and an entity would suggest a table "
                + "the service could write, which its grants deny"),
        TenantDatabaseBinding.class, new Reasoned(1,
            "set_config('app.tenant_id', ..., true) has no JPQL expression"),
        TenantMigrationCallback.class, new Reasoned(2,
            "set_config on Flyway's own connection at boot: one statement, written as "
                + "a connection fetched and a statement prepared on it"));

    private static final List<String> RAW_SQL_MARKERS = List.of(
        "createNativeQuery", ".getConnection(", ".createStatement(", ".prepareStatement(");

    @Test
    void raw_sql_is_only_issued_from_tenant_bound_classes() throws IOException {
        assertThat(unboundUnder(sourceRoot("main"), ALLOWLIST))
            .as("raw SQL is invisible to the ORM's tenant filter, so it must run under "
                + "@TenantBound — which is what binds app.tenant_id and brings the database "
                + "policy into effect. Presence is read from the applied annotation, so a "
                + "comment naming @TenantBound does not count. Annotate these classes, or "
                + "add them to ALLOWLIST with the reason they cannot leak")
            .isEmpty();
    }

    @Test
    void native_sql_appears_only_in_the_repository_layer() throws IOException {
        assertThat(outsideRepositoryUnder(sourceRoot("main"), OUTSIDE_REPOSITORY_ALLOWED))
            .as("native SQL belongs to the persistence layer, and a statement elsewhere "
                + "puts a database call in a layer whose readers are not looking for one. "
                + "Move the statement into a %s class, or add the class to "
                + "OUTSIDE_REPOSITORY_ALLOWED with the reason it cannot be one", PERSISTENCE)
            .isEmpty();
    }

    @Test
    void every_native_sql_occurrence_carries_a_written_reason() throws IOException {
        assertThat(unreasonedUnder(sourceRoot("main"), NATIVE_SQL_REASONS))
            .as("native SQL is the enumerated exception to JPQL, and every occurrence "
                + "carries its reason in NATIVE_SQL_REASONS together with the number of "
                + "occurrences that reason covers. A new statement changes the count, so "
                + "write down why it cannot be JPQL and correct the count; a removed "
                + "statement corrects the count too")
            .isEmpty();
    }

    /**
     * Every exemption names a class that still issues raw SQL.
     *
     * <p>A class literal already breaks the build on a rename; this catches
     * the other way an exemption goes stale, which is the statement being
     * removed while the entry stays.
     */
    @Test
    void every_exemption_names_a_class_that_still_issues_raw_sql() throws IOException {
        Map<Class<?>, Integer> found = occurrencesUnder(sourceRoot("main"));
        assertThat(found.keySet())
            .as("an exemption for a class that issues no raw SQL is not a decision. "
                + "Remove the entry")
            .containsAll(ALLOWLIST)
            .containsAll(OUTSIDE_REPOSITORY_ALLOWED.keySet())
            .containsAll(NATIVE_SQL_REASONS.keySet());
    }

    /**
     * The red state of the binding rule, observed on every build.
     *
     * <p>{@code UnboundRawSqlFixture} in the test sources is a genuine
     * violation — raw SQL against a tenant-scoped table with no binding — and
     * the detection is required to find it. It also names {@code @TenantBound}
     * in its javadoc; a check that decided on file text would be fooled by
     * that mention, so the check is observed being right for the right reason.
     */
    @Test
    void the_tripwire_catches_an_unbound_class_that_issues_raw_sql() throws IOException {
        assertThat(unboundUnder(sourceRoot("test"), Set.of()))
            .as("RED STATE, observed: the fixture issues raw SQL without an applied "
                + "@TenantBound and must be reported. An empty list here would mean the "
                + "green assertion above is measuring nothing")
            .anyMatch(offender -> offender.contains("UnboundRawSqlFixture"));
    }

    /**
     * The red state of the placement and reason rules, observed on every
     * build.
     *
     * <p>The same fixture sits outside {@code repository} and has no reason
     * written for it, so both detections must report it. The main tree is
     * also handed to them with the exemptions withheld: the two tenancy
     * classes are real native statements outside the persistence layer, and
     * every real statement is unreasoned once its reason is taken away.
     */
    @Test
    void the_check_reports_native_sql_outside_the_repository_and_without_a_reason()
            throws IOException {
        assertThat(outsideRepositoryUnder(sourceRoot("test"), Map.of()))
            .as("RED STATE, observed: the fixture issues native SQL outside the "
                + "repository layer and must be reported")
            .anyMatch(offender -> offender.contains("UnboundRawSqlFixture"));
        assertThat(outsideRepositoryUnder(sourceRoot("main"), Map.of()))
            .as("RED STATE, observed: with the exemptions withheld, the tenancy binding "
                + "is native SQL outside the repository layer and must be reported")
            .anyMatch(offender -> offender.contains("TenantDatabaseBinding"));
        List<String> unreasoned = unreasonedUnder(sourceRoot("main"), Map.of());
        for (Class<?> reasoned : NATIVE_SQL_REASONS.keySet()) {
            assertThat(unreasoned)
                .as("RED STATE, observed: with no reason written, %s issues native SQL "
                    + "and must be reported", reasoned.getSimpleName())
                .anyMatch(offender -> offender.startsWith(reasoned.getName() + " "));
        }
        assertThat(unreasonedUnder(sourceRoot("main"), Map.of(
                ScopeAccessRepository.class, new Reasoned(5, "one statement short"))))
            .as("RED STATE, observed: a reason covering fewer occurrences than the class "
                + "has must be reported, or a statement added beside a reasoned one would "
                + "inherit a reason nobody wrote for it")
            .anyMatch(offender -> offender.contains("ScopeAccessRepository"));
    }

    /** The module's source root for a given source set, whichever directory the build runs from. */
    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
    }

    /**
     * Every class under {@code root} that issues raw SQL without an applied
     * {@code @TenantBound} and without an exemption.
     *
     * <p>Parameterised on the root and the allowlist so the same detection
     * serves both the assertion and its red state. A red state exercising a
     * second copy of the logic would prove that the copy works.
     */
    private static List<String> unboundUnder(Path root, Set<Class<?>> allowlist)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : rawSqlFiles(root)) {
            Class<?> clazz = loadClass(root, file);
            if (clazz == null) {
                // A raw-SQL file that cannot be loaded cannot be checked,
                // and an unverifiable file is reported rather than skipped.
                offenders.add(fqcn(root, file)
                    + " (issues raw SQL but could not be loaded for a structural check)");
                continue;
            }
            if (allowlist.contains(clazz)) {
                continue;
            }
            if (!isTenantBound(clazz)) {
                offenders.add(clazz.getName() + " (" + root.relativize(file) + ")");
            }
        }
        return offenders;
    }

    /** Every class outside the persistence layer that issues raw SQL, minus the exemptions. */
    private static List<String> outsideRepositoryUnder(Path root, Map<Class<?>, String> allowed)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Class<?>, Integer> found : occurrencesUnder(root).entrySet()) {
            Class<?> clazz = found.getKey();
            if (allowed.containsKey(clazz)) {
                continue;
            }
            if (!(BASE + "." + PERSISTENCE).equals(clazz.getPackageName())) {
                offenders.add(clazz.getName() + " (" + found.getValue() + " raw-SQL markers)");
            }
        }
        return offenders;
    }

    /** Every class whose raw-SQL occurrences are not covered by a written reason. */
    private static List<String> unreasonedUnder(Path root, Map<Class<?>, Reasoned> reasons)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Class<?>, Integer> found : occurrencesUnder(root).entrySet()) {
            Reasoned reasoned = reasons.get(found.getKey());
            if (reasoned == null) {
                offenders.add(found.getKey().getName() + " (" + found.getValue()
                    + " raw-SQL markers, no reason written)");
            } else if (reasoned.occurrences() != found.getValue()) {
                offenders.add(found.getKey().getName() + " (" + found.getValue()
                    + " raw-SQL markers, reason written for " + reasoned.occurrences() + ")");
            }
        }
        return offenders;
    }

    /**
     * The raw-SQL markers in each class under a root, counted in code with
     * comments removed — a statement named in javadoc is a reference, not a
     * statement.
     */
    private static Map<Class<?>, Integer> occurrencesUnder(Path root) throws IOException {
        Map<Class<?>, Integer> found = new LinkedHashMap<>();
        for (Path file : rawSqlFiles(root)) {
            String code = code(file);
            int count = 0;
            for (String marker : RAW_SQL_MARKERS) {
                count += code.split(Pattern.quote(marker), -1).length - 1;
            }
            if (count == 0) {
                continue;
            }
            Class<?> clazz = loadClass(root, file);
            assertThat(clazz)
                .as("%s issues raw SQL and could not be loaded; an unverifiable file is "
                    + "reported rather than skipped", fqcn(root, file))
                .isNotNull();
            found.put(clazz, count);
        }
        return found;
    }

    /**
     * Every file whose text contains a raw-SQL marker. File text locates raw
     * SQL and decides nothing else.
     */
    private static List<Path> rawSqlFiles(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted()
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                if (RAW_SQL_MARKERS.stream().anyMatch(source::contains)) {
                    found.add(file);
                }
            }
        }
        return found;
    }

    /** A file's text with comments removed. */
    private static String code(Path file) throws IOException {
        String source = Files.readString(file);
        source = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll(" ");
        return source.replaceAll("//[^\\n]*", " ");
    }

    /**
     * Class-level {@code @TenantBound} covers every method; a method-level one
     * covers that method. Either satisfies the tripwire, and neither can be
     * faked by a string appearing in the file.
     */
    private static boolean isTenantBound(Class<?> clazz) {
        if (clazz.isAnnotationPresent(TenantBound.class)) {
            return true;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(TenantBound.class)) {
                return true;
            }
        }
        return false;
    }

    private static String fqcn(Path root, Path file) {
        Path relative = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(part);
        }
        return sb.substring(0, sb.length() - ".java".length());
    }

    /**
     * Loads the class WITHOUT initialising it — annotation reflection needs
     * the class linked, not initialised, and initialising application classes
     * from a unit test would drag in CDI. Returns null when the file holds no
     * loadable top-level class of the expected name, and the caller turns
     * that into a reported offender rather than a silent skip.
     */
    private static Class<?> loadClass(Path root, Path file) {
        try {
            return Class.forName(fqcn(root, file), false,
                RawSqlArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notLoadable) {
            return null;
        }
    }

    /** A written reason and the number of raw-SQL markers it covers. */
    private record Reasoned(int occurrences, String reason) {
    }
}
