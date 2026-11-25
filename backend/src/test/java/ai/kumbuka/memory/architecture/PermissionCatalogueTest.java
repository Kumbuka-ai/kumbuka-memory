/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import ai.kumbuka.memory.platform.SystemRoles;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The community edition's role mapping is the permission catalogue's table,
 * and nothing beside it.
 *
 * <p>The expectation is {@link #CATALOGUE}, the rows of the platform's
 * permission catalogue that carry a permission of this service, written here
 * role by role from the catalogue and never read from the mapping: concept
 * "permission catalogue, roles, groups and filters" in platform-specs, sections
 * A.3.1 (the permissions and the verbs they serve) and A.4.2, A.4.3 (the roles),
 * at commit {@value #CATALOGUE_COMMIT}. Three statements are held against it:
 *
 * <ol>
 *   <li>every role of the table grants exactly the permissions of its row;</li>
 *   <li>the roles the catalogue gives no permission of this service -- the
 *       administration role and the roles of the other services -- grant
 *       none;</li>
 *   <li>the mapping names no role beyond the table, so every other role a
 *       token carries grants nothing here.</li>
 * </ol>
 *
 * <p>The permissions and their verbs (A.3.1): {@code memory.entry.read} for
 * read, query and digest; {@code memory.entry.create} for create;
 * {@code memory.entry.update} for update; {@code memory.entry.withdraw} for
 * withdraw; {@code memory.entry.tag} for tag_attach and tag_detach. Which method
 * declares which is held by {@code FacadeConformanceTest}.
 */
class PermissionCatalogueTest {

    /** The commit of platform-specs whose catalogue this table is taken from. */
    static final String CATALOGUE_COMMIT = "0cdc111f219687953735d98ff9d14a3509fff438";

    private static final String READ = "memory.entry.read";
    private static final String CREATE = "memory.entry.create";
    private static final String UPDATE = "memory.entry.update";
    private static final String WITHDRAW = "memory.entry.withdraw";
    private static final String TAG = "memory.entry.tag";

    /** The roles that carry a permission of this service, and those permissions. */
    static final Map<String, Set<String>> CATALOGUE = Map.of(
        "owner", Set.of(READ, CREATE, UPDATE, WITHDRAW, TAG),
        "memory-reader", Set.of(READ),
        "memory-author", Set.of(CREATE, UPDATE, TAG),
        "memory-curator", Set.of(WITHDRAW));

    /**
     * Roles the catalogue gives no permission of this service: the
     * administration role, and roles of the other services as an agent's token
     * carries them beside {@code memory-reader}.
     */
    static final List<String> WITHOUT_A_PERMISSION_HERE = List.of("administration",
        "dispatch-reader", "worklist-reader", "member");

    @Test
    void the_role_mapping_is_the_catalogue_s_table() {
        assertThat(offences(SystemRoles.mapped(), SystemRoles::permissionsOf))
            .as("the community edition maps the system roles to the permissions of the "
                + "catalogue at %s and to nothing else. Change the catalogue first, then this "
                + "table and the mapping together", CATALOGUE_COMMIT)
            .isEmpty();
    }

    @Test
    void the_agent_s_roles_grant_reading_and_nothing_else() {
        assertThat(SystemRoles.permissionsOf(
                List.of("dispatch-reader", "memory-reader", "worklist-reader")))
            .as("an agent account carries the narrow cut of the catalogue: it reads memory "
                + "and writes none of it")
            .containsExactly(READ);
    }

    /**
     * The red state, observed on every build: a planted mapping wrong in every
     * way the three statements exist for.
     */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        Map<String, Set<String>> planted = new LinkedHashMap<>();
        planted.put("owner", Set.of(READ, CREATE, UPDATE, TAG));
        planted.put("memory-reader", Set.of(READ, CREATE));
        planted.put("memory-author", Set.of(CREATE, UPDATE, TAG));
        planted.put("memory-curator", Set.of(WITHDRAW));
        planted.put("dispatch-reader", Set.of(READ));
        planted.put("memory-personal", Set.of(READ));

        assertThat(offences(planted.keySet(), roles -> union(planted, roles)))
            .as("RED STATE, observed: a role short of a permission of its row, a role with one "
                + "beyond it, a role of another service that grants one here, and a role the "
                + "catalogue does not have are each reported")
            .containsExactlyInAnyOrder(
                "owner grants [memory.entry.create, memory.entry.read, memory.entry.tag, "
                    + "memory.entry.update]; the catalogue's row is [memory.entry.create, "
                    + "memory.entry.read, memory.entry.tag, memory.entry.update, "
                    + "memory.entry.withdraw]",
                "memory-reader grants [memory.entry.create, memory.entry.read]; the catalogue's "
                    + "row is [memory.entry.read]",
                "dispatch-reader grants [memory.entry.read]; the catalogue grants it no "
                    + "permission of this service",
                "dispatch-reader is mapped and is no row of the catalogue",
                "memory-personal is mapped and is no row of the catalogue");
    }

    // -----------------------------------------------------------------------

    static List<String> offences(Set<String> mapped,
                                 Function<Collection<String>, Set<String>> grants) {
        List<String> offences = new ArrayList<>();
        CATALOGUE.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
            Set<String> granted = grants.apply(List.of(row.getKey()));
            if (!granted.equals(row.getValue())) {
                offences.add(row.getKey() + " grants " + sorted(granted) + "; the catalogue's "
                    + "row is " + sorted(row.getValue()));
            }
        });
        for (String role : WITHOUT_A_PERMISSION_HERE) {
            Set<String> granted = grants.apply(List.of(role));
            if (!granted.isEmpty()) {
                offences.add(role + " grants " + sorted(granted) + "; the catalogue grants it "
                    + "no permission of this service");
            }
        }
        mapped.stream().filter(role -> !CATALOGUE.containsKey(role)).sorted()
            .forEach(role -> offences.add(role + " is mapped and is no row of the catalogue"));
        return offences;
    }

    private static Set<String> union(Map<String, Set<String>> mapping, Collection<String> roles) {
        Set<String> granted = new TreeSet<>();
        roles.forEach(role -> granted.addAll(mapping.getOrDefault(role, Set.of())));
        return granted;
    }

    private static List<String> sorted(Set<String> permissions) {
        return List.copyOf(new TreeSet<>(permissions));
    }
}
