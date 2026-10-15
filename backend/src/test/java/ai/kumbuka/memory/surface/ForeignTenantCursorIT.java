/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.contract.Mcp;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.memory.tenancy.ConfiguredTenantResolver;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static ai.kumbuka.memory.surface.SurfaceFixture.API;
import static ai.kumbuka.memory.surface.SurfaceFixture.call;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cursor of one tenant, handed back by another on a scope of the same name.
 *
 * <h2>The case, as it was measured</h2>
 *
 * Two estates each have a scope called {@code global}. The cursor bound the
 * scope by that name, so a cursor tenant A was handed on its {@code global}
 * was taken by tenant B on B's own {@code global} and positioned B's page
 * after A's last key and moment, measured end to end through the router. No
 * content of A reached B — row-level security holds — but an entry of B that
 * sorts before A's position was skipped without a sign, and the binding the
 * cursor promises was not there. ADR-0049: the cursor encodes the scope by its
 * technical identity.
 *
 * <h2>Why one service plays both tenants</h2>
 *
 * The acting tenant is a property of the installation, not of a call, and the
 * claim here is about a cursor crossing from one installation's tenant to
 * another's. So the resolver the service asks for the tenant is replaced for
 * the duration of each probe by one that can be switched between the two
 * estates the substrate stages. Everything else is the real path: the
 * directory resolves the scope for the bound tenant, the cursor is the one the
 * service handed out, and the refusal is the one it answers.
 *
 * <p>The red run this probe was built against, observed on 2026-10-12 on
 * {@code 5105cfb}: REST answers 200 and the assistant surface a result that
 * is no refusal, both with tenant B's entries after tenant A's position.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class ForeignTenantCursorIT {

    /** Tenant B's scope of the same name as tenant A's {@code global}. */
    private static final String OTHER_GLOBAL_SCOPE_ID = "00000000-0000-0000-0000-000000000021";

    private static final String SCOPE = SubstrateDatabaseResource.GLOBAL_SCOPE_SLUG;
    private static final String KEYS = "objects.fields.key";

    private final SwitchableTenant tenant = new SwitchableTenant();

    /**
     * Tenant B gets a {@code global} of its own, and the probe's subject a
     * membership in B. Both are what a second estate of a real installation
     * has; the substrate stages neither, because no other probe needs them.
     */
    @BeforeAll
    static void aSecondEstateWithAScopeOfTheSameName() {
        PlatformFixture.grantDirectoryAccess();
        SurfaceFixture.run("INSERT INTO public.scope (id, tenant_id, slug, kind, locked) "
            + "VALUES ('" + OTHER_GLOBAL_SCOPE_ID + "', '"
            + SubstrateDatabaseResource.OTHER_TENANT_ID + "', '" + SCOPE + "', 'global', false) "
            + "ON CONFLICT (id) DO NOTHING");
        SurfaceFixture.run("INSERT INTO public.user_account (tenant_id, subject) "
            + "SELECT '" + SubstrateDatabaseResource.OTHER_TENANT_ID + "', '"
            + SubstrateDatabaseResource.PROBE_SUBJECT + "' WHERE NOT EXISTS (SELECT 1 FROM "
            + "public.user_account WHERE tenant_id = '"
            + SubstrateDatabaseResource.OTHER_TENANT_ID + "' AND subject = '"
            + SubstrateDatabaseResource.PROBE_SUBJECT + "')");
    }

    @AfterAll
    static void removeTheSecondEstatesAdditions() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.run("DELETE FROM public.user_account WHERE tenant_id = '"
            + SubstrateDatabaseResource.OTHER_TENANT_ID + "' AND subject = '"
            + SubstrateDatabaseResource.PROBE_SUBJECT + "'");
        SurfaceFixture.run("DELETE FROM public.scope WHERE id = '" + OTHER_GLOBAL_SCOPE_ID + "'");
    }

    /**
     * The same four keys, laid down at the same four moments, in both estates.
     * So a position in A's listing is a position B's listing can take: if the
     * binding does not hold, nothing else stops the cursor.
     */
    @BeforeEach
    void theSameEntriesInBothEstates() {
        SurfaceFixture.clearEntries();
        Instant start = Instant.parse("2026-03-01T12:00:00Z");
        for (int n = 1; n <= 4; n++) {
            Instant at = start.plusSeconds(n);
            SurfaceFixture.plant(new SurfaceFixture.Planted(SubstrateDatabaseResource.TENANT_ID,
                SubstrateDatabaseResource.GLOBAL_SCOPE_ID, false,
                SubstrateDatabaseResource.PROBE_SUBJECT, "decision", "decision.e-00" + n,
                "estate a, entry " + n, at));
            SurfaceFixture.plant(new SurfaceFixture.Planted(
                SubstrateDatabaseResource.OTHER_TENANT_ID, OTHER_GLOBAL_SCOPE_ID, false,
                SubstrateDatabaseResource.PROBE_SUBJECT, "decision", "decision.e-00" + n,
                "estate b, entry " + n, at));
        }
        QuarkusMock.installMockForType(tenant, ConfiguredTenantResolver.class);
    }

    @Test
    void a_cursor_of_another_tenant_on_a_scope_of_the_same_name_is_foreign_on_rest() {
        tenant.actAs(SubstrateDatabaseResource.TENANT_ID);
        String cursor = call().get(API + "/" + SCOPE + "/decision?page_size=2")
            .jsonPath().getString("next_cursor");
        assertThat(cursor).as("tenant A's first page hands out a cursor").isNotBlank();

        tenant.actAs(SubstrateDatabaseResource.OTHER_TENANT_ID);
        Response own = call().get(API + "/" + SCOPE + "/decision?page_size=2");
        assertThat(own.jsonPath().getList(KEYS, String.class))
            .as("control: tenant B reads its own global, and its own cursor continues it")
            .containsExactly("decision.e-001", "decision.e-002");
        assertThat(call().get(API + "/" + SCOPE + "/decision?page_size=2&after="
                + own.jsonPath().getString("next_cursor")).statusCode())
            .isEqualTo(200);

        Response foreign = call().get(API + "/" + SCOPE + "/decision?page_size=2&after=" + cursor);

        assertThat(foreign.statusCode())
            .as("A's cursor on B's scope of the same name. Measured before the fix: 200 with "
                + "B's entries after A's position — %s", foreign.asString())
            .isEqualTo(400);
        assertThat(foreign.jsonPath().getString("reason")).isEqualTo("CURSOR_FOREIGN");
    }

    @Test
    void a_cursor_of_another_tenant_on_a_scope_of_the_same_name_is_foreign_on_mcp() {
        tenant.actAs(SubstrateDatabaseResource.TENANT_ID);
        Mcp.Result first = Mcp.call("memory_query",
            Mcp.args("scope", SCOPE, "selector", "decision", "page_size", 2));
        String cursor = first.string("next_cursor");
        assertThat(cursor).as("%s", first).isNotBlank();

        tenant.actAs(SubstrateDatabaseResource.OTHER_TENANT_ID);
        Mcp.Result foreign = Mcp.call("memory_query",
            Mcp.args("scope", SCOPE, "selector", "decision", "page_size", 2, "after", cursor));

        assertThat(foreign.isError())
            .as("A's cursor on B's scope of the same name, on the assistant surface — %s",
                foreign)
            .isTrue();
        assertThat(foreign.reason()).isEqualTo("CURSOR_FOREIGN");
    }

    /**
     * The tenant resolver, switchable between the two estates within one probe.
     * A subclass of the bean it stands in for, because that is what a mock of
     * a normal-scoped bean has to be.
     */
    static final class SwitchableTenant extends ConfiguredTenantResolver {

        private volatile UUID current = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

        void actAs(String tenantId) {
            current = UUID.fromString(tenantId);
        }

        @Override
        public UUID currentTenant() {
            return current;
        }
    }
}
