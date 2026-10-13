/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the statements Hibernate prepares, unchanged, so a probe can ask the
 * planner about the statement the service actually issues rather than about
 * one written by hand to resemble it.
 *
 * <p>Test code only. It returns every statement as it received it, and it
 * records only between {@link #start()} and {@link #recorded()}, so the rest
 * of the suite runs through it without leaving a record behind.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class RecordedStatements implements StatementInspector {

    private static final long serialVersionUID = 1L;

    private static final List<String> RECORDED = new ArrayList<>();
    private static boolean recording;

    @Override
    public String inspect(String sql) {
        synchronized (RECORDED) {
            if (recording) {
                RECORDED.add(sql);
            }
        }
        return sql;
    }

    /** Forgets what was recorded so far and records from now on. */
    static void start() {
        synchronized (RECORDED) {
            RECORDED.clear();
            recording = true;
        }
    }

    /** The statements recorded since {@link #start()}, in order; recording stops. */
    static List<String> recorded() {
        synchronized (RECORDED) {
            recording = false;
            return List.copyOf(RECORDED);
        }
    }
}
