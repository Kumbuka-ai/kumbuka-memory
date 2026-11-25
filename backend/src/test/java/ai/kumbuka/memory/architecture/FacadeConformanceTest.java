/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.architecture;

import ai.kumbuka.memory.domain.DigestService;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.security.quarkus.ScopePermission;
import ai.kumbuka.security.quarkus.ScopelessPermission;
import ai.kumbuka.security.quarkus.conformance.CatalogueEntry;
import ai.kumbuka.security.quarkus.conformance.FacadeConformanceGuard;
import ai.kumbuka.security.quarkus.conformance.Offence;
import ai.kumbuka.security.quarkus.conformance.ScopeBinding;
import io.quarkus.security.PermissionsAllowed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every method of the domain services declares the permission the catalogue
 * gives its verb and the scope it addresses, and no public method of them is
 * left undeclared: the binding's facade conformance guard, run over the two
 * classes every verb passes through.
 *
 * <p>The facades are {@link MemoryService} and {@link DigestService}, the one
 * way into the domain: both adapters reach a verb only through the verb
 * surface, and the verb surface only through these. The expectation is
 * {@link #CATALOGUE}, written here verb by verb from the permission catalogue
 * ({@code PermissionCatalogueTest} names the commit) and never read from the
 * declarations: read, query and digest {@code memory.entry.read}; create
 * {@code memory.entry.create}; update {@code memory.entry.update}; withdraw
 * {@code memory.entry.withdraw}; tag_attach and tag_detach {@code
 * memory.entry.tag}. Every method addresses the scope through its argument
 * {@code scope}, but the read by technical address: its scope is known only
 * once its entry is loaded, so it addresses the tenant and reads the entry
 * through {@code read}, which addresses the scope.
 *
 * <p>A public method added to either class without a declaration and a row is
 * reported here, and refused at run time by the binding as well.
 */
class FacadeConformanceTest {

    private static final ScopeBinding SCOPE = new ScopeBinding.Scope("scope");

    static final List<CatalogueEntry> CATALOGUE = List.of(
        new CatalogueEntry(MemoryService.class, "create", "memory.entry.create", SCOPE),
        new CatalogueEntry(MemoryService.class, "read", "memory.entry.read", SCOPE),
        new CatalogueEntry(MemoryService.class, "readByLogicalId", "memory.entry.read",
            new ScopeBinding.Tenant()),
        new CatalogueEntry(MemoryService.class, "update", "memory.entry.update", SCOPE),
        new CatalogueEntry(MemoryService.class, "withdraw", "memory.entry.withdraw", SCOPE),
        new CatalogueEntry(MemoryService.class, "tagAttach", "memory.entry.tag", SCOPE),
        new CatalogueEntry(MemoryService.class, "tagDetach", "memory.entry.tag", SCOPE),
        new CatalogueEntry(MemoryService.class, "query", "memory.entry.read", SCOPE),
        new CatalogueEntry(DigestService.class, "digest", "memory.entry.read", SCOPE));

    @Test
    void every_method_of_the_domain_declares_the_permission_and_scope_of_its_row() {
        assertThat(FacadeConformanceGuard.offences(
                List.of(MemoryService.class, DigestService.class), CATALOGUE))
            .as("each verb declares the permission the catalogue gives it and the scope it "
                + "addresses, and the binding decides both before the method runs. Declare the "
                + "method, or give it a row of the catalogue through the order that changes it")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a planted facade wrong in every
     * way the guard exists for, held against rows of its own.
     */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        List<CatalogueEntry> rows = List.of(
            new CatalogueEntry(PlantedFacade.class, "read", "memory.entry.read", SCOPE),
            new CatalogueEntry(PlantedFacade.class, "update", "memory.entry.update", SCOPE),
            new CatalogueEntry(PlantedFacade.class, "withdraw", "memory.entry.withdraw", SCOPE),
            new CatalogueEntry(PlantedFacade.class, "digest", "memory.entry.read", SCOPE));

        assertThat(FacadeConformanceGuard.offences(List.of(PlantedFacade.class), rows).stream()
                .map(offence -> offence.method() + " " + offence.kind()).toList())
            .as("RED STATE, observed: a new verb method without a declaration and a row, a "
                + "method declaring another permission, one binding no scope, and a row no "
                + "method answers to are each reported")
            .containsExactlyInAnyOrder(
                "freshVerb " + Offence.Kind.UNDECLARED,
                "freshVerb " + Offence.Kind.NOT_IN_CATALOGUE,
                "update " + Offence.Kind.WRONG_PERMISSION,
                "withdraw " + Offence.Kind.WRONG_SCOPE_BINDING,
                "digest " + Offence.Kind.ROW_WITHOUT_METHOD);
    }

    /** A facade wrong in every way the guard exists for, and right in one. */
    static final class PlantedFacade {

        @PermissionsAllowed(value = "memory.entry.read", permission = ScopePermission.class,
            params = "scope")
        public String read(String scope) {
            return scope;
        }

        @PermissionsAllowed(value = "memory.entry.read", permission = ScopePermission.class,
            params = "scope")
        public String update(String scope) {
            return scope;
        }

        @PermissionsAllowed(value = "memory.entry.withdraw:none",
            permission = ScopelessPermission.class)
        public String withdraw(String scope) {
            return scope;
        }

        public String freshVerb(String scope) {
            return scope;
        }
    }
}
