/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.tenancy;

import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Every log line of the tenancy aspect, and the only logger it holds
 * (DEC-0051, C4).
 *
 * <p>Part of the tenancy set, and like it imports nothing of its own service:
 * every type a line takes is declared here. A line carries the tenant the
 * installation is configured for and a constant naming a start problem, never
 * a value a caller chose and never a configuration value's text. Each line is
 * one method with a constant message; the methods are reachable from the
 * tenancy package only, and the types are built only there, from what the
 * aspect itself holds.
 *
 * <p>The request id travels through the MDC into the format, and the service
 * through the logger's category; neither is a parameter.
 */
public final class TenancyLog {

    private static final Logger LOG = Logger.getLogger(TenancyLog.class);

    /**
     * The line a migration run leaves behind, starting with
     * {@link TenantMigrationCallback#FIRED_MARKER}: the contract with whoever
     * reads a deployment's log to answer "did the binding hold on that run".
     */
    static final String BINDING_APPLIED = TenantMigrationCallback.FIRED_MARKER
        + ": app.tenant_id=%s before a migration";
    static final String TENANT_REBOUND ="tenant rebinding inside one transaction: %s -> %s";
    static final String START_REFUSED = "start refused: %s";

    private TenancyLog() {
    }

    /** INFO: the tenant bound before a migration; one line per migration. */
    static void bindingApplied(TenantId tenant) {
        LOG.infof(BINDING_APPLIED, tenant);
    }

    /**
     * WARN: one transaction asked to carry a second tenant, which can only be
     * a programming error.
     */
    static void rebinding(TenantId bound, TenantId requested) {
        LOG.warnf(TENANT_REBOUND, bound, requested);
    }

    /**
     * WARN, on a line of its own before the start fails (C13.3): the format
     * prints no exception (C13.1). The value that was wrong stays out of the
     * line.
     */
    static void startRefused(StartProblem problem) {
        LOG.warnf(START_REFUSED, problem);
    }

    /** A tenant, as this installation holds it: configured or bound. */
    public static final class TenantId {

        private final UUID tenant;

        private TenantId(UUID tenant) {
            this.tenant = tenant;
        }

        static TenantId of(UUID tenant) {
            return new TenantId(tenant);
        }

        @Override
        public String toString() {
            return tenant.toString();
        }
    }

    /** What a start was refused for, without the value that was wrong. */
    public enum StartProblem {

        /** The tenant key is not set at all. */
        TENANT_NOT_SET,

        /** The tenant key is present and blank. */
        TENANT_EMPTY,

        /** The tenant key holds something that is not a uuid. */
        TENANT_NOT_A_UUID
    }
}
