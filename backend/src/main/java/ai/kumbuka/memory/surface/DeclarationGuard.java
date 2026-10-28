/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to start with an assistant surface that could not be served as
 * declared (C5.6) — above all with a reason the service can raise and the
 * catalogue does not word (DEC-0043).
 *
 * <p>At start and not at the first refusal: a missing pattern found by a
 * caller is found in production, by somebody who then gets an improvised
 * answer or none. Found here, it is a container that does not come up, with a
 * log line naming what is wrong.
 *
 * <h2>It reads the declaration itself</h2>
 *
 * The checks run over the declaration as it is served, built once from the
 * bundles, and not over the enum it is built from: what a reader receives is
 * what is checked. Every check takes plain values, so that each can be
 * observed refusing without a running service ({@code SurfaceDeclarationGuardTest}).
 */
@ApplicationScoped
public class DeclarationGuard {

    private static final Logger LOG = Logger.getLogger(DeclarationGuard.class);

    void onStart(@Observes StartupEvent event) {
        Map<String, ReasonCatalogue.Reason> catalogue = catalogue();
        ReasonCatalogue.requireComplete(catalogue);
        requireServable(SurfaceDeclaration.asMap(), catalogue.keySet());
        LOG.infof("assistant surface declared: %d calls, %d reasons",
            SurfaceDeclaration.calls().size(), catalogue.size());
    }

    /**
     * The catalogue the start is checked against.
     *
     * <p>An instance method only so that a start can be observed refusing an
     * incomplete catalogue ({@code CatalogueStartIT}); the service checks the
     * one it carries.
     */
    protected Map<String, ReasonCatalogue.Reason> catalogue() {
        return ReasonCatalogue.byCode();
    }

    /**
     * Refuses a declaration that could not be served as stated: two calls
     * under one name, a call or an argument without a description, a call's
     * description over {@link SurfaceDeclaration#DESCRIPTION_BUDGET}, and a
     * refusal a call names that the catalogue does not declare.
     *
     * @param declaration the declaration as served
     * @param catalogue   the codes the reason catalogue declares
     * @throws IllegalStateException naming the call and what is wrong with it
     */
    public static void requireServable(Map<String, Object> declaration, Set<String> catalogue) {
        List<Map<String, Object>> calls = listOf(declaration, "calls", "the declaration");
        requireDistinctNames(calls.stream().map(call -> String.valueOf(call.get("call"))).toList());
        for (Map<String, Object> call : calls) {
            String name = String.valueOf(call.get("call"));
            String description = requireDescribed(name, (String) call.get("description"));
            requireWithinBudget(name, description);
            requireDescribedArguments(name, listOf(call, "arguments", name));
            requireDeclaredRefusals(name, listOf(call, "refusals", name), catalogue);
        }
    }

    /** A list member of the declaration; one that is missing is a defect of it. */
    @SuppressWarnings("unchecked")
    private static <T> List<T> listOf(Map<String, Object> declared, String member, String where) {
        if (declared.get(member) instanceof List<?> list) {
            return (List<T>) list;
        }
        throw new IllegalStateException(where + " is declared without " + member);
    }

    /** Refuses two calls declared under one name: a tool list is flat. */
    public static void requireDistinctNames(List<String> calls) {
        Set<String> seen = new HashSet<>();
        for (String call : calls) {
            if (!seen.add(call)) {
                throw new IllegalStateException(
                    "two calls are declared as " + call + "; a tool list is flat");
            }
        }
    }

    /**
     * Refuses a call declared without a description, which is all a caller has
     * to go on.
     *
     * @return the description, present
     */
    public static String requireDescribed(String call, @Nullable String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(call + " is declared without a "
                + "description, and the description is all a caller has to go on");
        }
        return description;
    }

    /**
     * Refuses a description over {@link SurfaceDeclaration#DESCRIPTION_BUDGET}
     * characters: a caller that does not read it whole cuts it. What explains
     * one argument stands at that argument.
     */
    public static void requireWithinBudget(String call, String description) {
        if (description.length() > SurfaceDeclaration.DESCRIPTION_BUDGET) {
            throw new IllegalStateException(call + "'s description has "
                + description.length() + " characters, over the budget of "
                + SurfaceDeclaration.DESCRIPTION_BUDGET);
        }
    }

    /**
     * Refuses a call with no arguments, or an argument with no description.
     *
     * <p>A call with none would have an empty closed schema and nothing for
     * the undeclared-argument refusal to name instead; an argument with no
     * description leaves the missing-argument refusal unable to say what the
     * value is.
     */
    public static void requireDescribedArguments(String call,
                                                 List<Map<String, Object>> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalStateException(call + " is declared with no arguments");
        }
        for (Map<String, Object> argument : arguments) {
            if (!(argument.get("description") instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(
                    call + " declares " + argument.get("name") + " without a description");
            }
        }
    }

    /** Refuses a call that names a refusal the catalogue does not declare. */
    public static void requireDeclaredRefusals(String call, List<String> refusals,
                                               Set<String> catalogue) {
        List<String> undeclared = new ArrayList<>(refusals);
        undeclared.removeAll(catalogue);
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(call + " is declared to refuse with "
                + undeclared + ", which the catalogue does not declare");
        }
    }
}
