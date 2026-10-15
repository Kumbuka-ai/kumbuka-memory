/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A placeholder of a message bundle takes only the platform's vocabulary
 * (code-conventions/service-construction.md, "texts addressed to a caller",
 * point 2; DEC-0043 R18).
 *
 * <h2>What it holds</h2>
 *
 * Every parameter of every bundle method is one of {@link #ALLOWED}, or a
 * {@code List} of one of them -- never a {@code String}, a number or any other
 * type a request could fill. And each allowed type can be made only from the
 * declaration: none has a public constructor, and none has a public factory
 * that takes a {@code String}, so a name that arrived in a request cannot be
 * turned into one outside the package that declares the surface.
 *
 * <p>By reflection over the compiled bundles, so that what is checked is what
 * the build renders, not a reading of its source.
 */
class MessagePlaceholderTypeTest {

    /** The simple names of the types a placeholder may take. */
    static final Set<String> ALLOWED = Set.of("ArgumentName", "ArgumentPath", "VerbName",
        "ReasonCode", "Bound");

    @Test
    void every_placeholder_is_one_of_the_platform_s_types() {
        List<String> offences = new ArrayList<>();
        for (Class<?> bundle : MessageBundleTest.bundles()) {
            offences.addAll(offendingParameters(bundle));
        }
        assertThat(offences)
            .as("a placeholder of type String can carry whatever a request carried, which "
                + "is the echo DEC-0043 R18 rules out; take the type the declaration builds")
            .isEmpty();
    }

    @Test
    void every_placeholder_type_is_made_only_by_the_declaration() {
        List<String> offences = new ArrayList<>();
        for (Class<?> bundle : MessageBundleTest.bundles()) {
            for (Method method : bundle.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    Class<?> type = placeholderClass(parameter.getParameterizedType());
                    if (type != null) {
                        offences.addAll(openings(type));
                    }
                }
            }
        }
        assertThat(offences.stream().distinct().toList())
            .as("a placeholder type a public constructor or a factory from a String can "
                + "make is a String by another name")
            .isEmpty();
    }

    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(offendingParameters(PlantedBundle.class))
            .as("RED STATE, observed: a String placeholder and a list of Strings are "
                + "reported, a declared type is not")
            .hasSize(2);
        assertThat(openings(PlantedName.class))
            .as("RED STATE, observed: a placeholder type anyone can build from a String "
                + "is reported, by its constructor and by its factory")
            .hasSize(2);
    }

    /** A bundle as a careless change would write it; never annotated, never built. */
    interface PlantedBundle {

        String byName(String name);

        String byNames(List<String> names);

        String byDeclared(ai.kumbuka.memory.surface.VerbName call);
    }

    /** A placeholder type with every opening the check exists for. */
    static final class PlantedName {

        public PlantedName(String name) {
            // planted
        }

        public static PlantedName of(String name) {
            return new PlantedName(name);
        }
    }

    // ======================================================================

    private static List<String> offendingParameters(Class<?> bundle) {
        List<String> offences = new ArrayList<>();
        for (Method method : bundle.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Class<?> type = placeholderClass(parameter.getParameterizedType());
                if (type == null || !ALLOWED.contains(type.getSimpleName())) {
                    offences.add(bundle.getSimpleName() + "." + method.getName() + "("
                        + parameter.getParameterizedType().getTypeName() + " "
                        + parameter.getName() + ")");
                }
            }
        }
        return offences;
    }

    /** The class a placeholder carries: the type itself, or a list's element. */
    private static Class<?> placeholderClass(Type type) {
        if (type instanceof Class<?> plain) {
            return plain;
        }
        if (type instanceof ParameterizedType generic
                && generic.getRawType() == List.class
                && generic.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }

    /** The ways to build a placeholder type from outside its declaration. */
    private static List<String> openings(Class<?> type) {
        List<String> openings = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                openings.add(type.getSimpleName() + " has a public constructor");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == type
                    && Arrays.asList(method.getParameterTypes()).contains(String.class)) {
                openings.add(type.getSimpleName() + "." + method.getName()
                    + " builds one from a String");
            }
        }
        return openings;
    }
}
