package ai.kumbuka.memory.tenancy;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tripwire on the second way a tenant binding goes missing.
 *
 * <p>{@link RawSqlArchitectureTest} watches the first: a class that issues
 * raw SQL without {@code @TenantBound}, which is invisible to the ORM filter
 * and therefore protected by nothing. This one watches the case where the
 * annotation IS there and does not fire.
 *
 * <h2>What goes wrong, and why it is quiet</h2>
 *
 * {@code @TenantBound} binds {@code app.tenant_id} on the connection INSIDE a
 * transaction — that is where a connection exists to bind it on. A public
 * method with no {@code @Transactional} gets a connection per statement and
 * no binding, so the policy predicate is NULL, and the policy treats that as
 * failing. The read returns nothing.
 *
 * <p>Nothing raises. An empty result is a perfectly ordinary answer, and it
 * is indistinguishable from "there is nothing there" — which is exactly what
 * a lookup method concludes. In a sibling service built on the same tenancy
 * classes this was measured: a registry lookup refused a selector as
 * undeclared directly after it had been declared successfully, and the
 * message said, with complete confidence, that the caller should declare it
 * first. A typed refusal naming the wrong cause is worse than an untyped one,
 * because it is believed.
 *
 * <h2>The rule this enforces, as the rule set writes it</h2>
 *
 * Every public instance method of a {@code @TenantBound} class carries
 * {@code @Transactional}, on the method or on the class. Not only the classes
 * that hold an entity manager themselves: a class that reaches the database
 * through a collaborator makes the same promise by carrying the annotation,
 * and a method of it without a transaction runs every collaborator call in a
 * transaction of its own — bound each time, but no longer one act. A method
 * of such a class that genuinely needs no database is static, or belongs
 * somewhere else.
 *
 * <p>The classes that carry the annotation for another reason are listed in
 * {@link #ALLOWED} with that reason.
 *
 * <p>Runs as a plain unit test: it reads class files and needs no database.
 */
class TransactionalReadArchitectureTest {

    /**
     * {@code @TenantBound} classes whose public methods may run without a
     * transaction, each with the reason. Class literals, so a rename breaks
     * the build rather than leaving an exemption that matches nothing.
     */
    private static final Map<Class<?>, String> ALLOWED = Map.of(
        TenantBindingInterceptor.class,
        "carries @TenantBound as its interceptor binding, which is how CDI attaches "
            + "an interceptor to the annotation; its one public method is the "
            + "@AroundInvoke the container calls around a bound method, and it runs "
            + "inside the transaction the bound method opens rather than opening one");

    @Test
    void every_public_method_of_a_tenant_bound_class_carries_a_transaction()
            throws IOException {
        assertThat(offendersUnder(sourceRoot("main"), ALLOWED))
            .as("a @TenantBound class reaches the database through a transaction, because "
                + "that is where the session binding lives. A public method without one "
                + "reads under no tenant at all and gets an empty answer that raises "
                + "nothing — and an empty answer is what 'it does not exist' looks like. "
                + "Add @Transactional, or make the method static if it needs no database")
            .isEmpty();
    }

    /**
     * Every exemption is still needed.
     *
     * <p>An exempted class that no longer carries {@code @TenantBound}, or
     * whose public methods all carry a transaction, leaves an entry that
     * exempts nothing — indistinguishable from a decision nobody needs.
     */
    @Test
    void every_exemption_still_exempts_something() throws IOException {
        assertThat(offendersUnder(sourceRoot("main"), Map.of()))
            .as("an exemption in ALLOWED that covers no offending method is a debt "
                + "already paid. Remove the entry")
            .anySatisfy(offender -> assertThat(offender).startsWith(
                TenantBindingInterceptor.class.getName() + "."));
    }

    /**
     * The red state, observed on every build.
     *
     * <p>{@code UnboundReadFixture} is a real violation: bound, holding an
     * entity manager, and public without a transaction. Without this case the
     * assertion above would be a query that finds nothing rather than a tree
     * that contains nothing.
     */
    @Test
    void the_tripwire_catches_a_bound_class_whose_read_has_no_transaction()
            throws IOException {
        assertThat(offendersUnder(sourceRoot("test"), Map.of()))
            .as("RED STATE, observed: the fixture is @TenantBound and exposes a public "
                + "method with no transaction. An empty list here would mean the green "
                + "assertion above measures nothing")
            .anyMatch(offender -> offender.contains("UnboundReadFixture"));
    }

    /**
     * Every public instance method of a bound class that carries no
     * transaction — each named with its class — minus the exemptions.
     *
     * <p>Parameterised on the root and the exemptions so the same detection
     * serves the assertion, the exemption check and the red state. A red state
     * exercising a second copy of the logic would prove that the copy works.
     */
    private static List<String> offendersUnder(Path root, Map<Class<?>, String> allowed)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted()
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                Class<?> clazz = loadClass(root, file);
                if (clazz == null
                    || !clazz.isAnnotationPresent(TenantBound.class)
                    || allowed.containsKey(clazz)) {
                    continue;
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    if (isUnguardedPublicInstanceMethod(clazz, method)) {
                        offenders.add(clazz.getName() + "." + method.getName()
                            + " (public, no @Transactional)");
                    }
                }
            }
        }
        return offenders;
    }

    private static boolean isUnguardedPublicInstanceMethod(Class<?> clazz, Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
            || method.isSynthetic()) {
            return false;
        }
        // A class-level @Transactional covers every method, exactly as a
        // class-level @TenantBound does. Checked on the applied annotation
        // rather than on the file text, so a mention in a comment counts for
        // nothing.
        return !clazz.isAnnotationPresent(Transactional.class)
            && !method.isAnnotationPresent(Transactional.class);
    }

    /** The module's source root for a source set, whichever directory the build runs from. */
    private static Path sourceRoot(String sourceSet) {
        Path direct = Paths.get("src", sourceSet, "java");
        Path fromRepoRoot = Paths.get("backend", "src", sourceSet, "java");
        Path root = Files.isDirectory(direct) ? direct : fromRepoRoot;
        assertThat(Files.isDirectory(root))
            .as("source root %s must exist — run from the module directory", root)
            .isTrue();
        return root;
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
     * from a unit test would drag in CDI.
     */
    private static Class<?> loadClass(Path root, Path file) {
        try {
            return Class.forName(fqcn(root, file), false,
                TransactionalReadArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notLoadable) {
            return null;
        }
    }
}
