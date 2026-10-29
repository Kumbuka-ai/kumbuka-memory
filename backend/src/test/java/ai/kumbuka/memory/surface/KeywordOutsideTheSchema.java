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
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The start check, handed one argument that publishes a schema keyword the
 * tool schema does not carry.
 *
 * <p>Enabled only by the test profile that selects it, so that a real start
 * of the application can be observed refusing. Everything else about the
 * start is the service's own.
 */
@Alternative
@ApplicationScoped
public class KeywordOutsideTheSchema extends DeclarationGuard {

    /** A JSON-Schema keyword the tool schema has no member for. */
    public static final String PLANTED = "pattern";

    @Override
    void onStart(@Observes StartupEvent event) {
        super.onStart(event);
    }

    @Override
    protected Map<String, List<Argument>> argumentsByCall() {
        Map<String, List<Argument>> planted = new LinkedHashMap<>(super.argumentsByCall());
        List<Argument> read = new ArrayList<>(planted.get(AssistantVerb.READ.call()));
        read.add(Argument.top("address_pattern", Argument.STRING, false,
            ArgumentDescriptions::address, Map.of(PLANTED, "^memory://")));
        planted.put(AssistantVerb.READ.call(), read);
        return planted;
    }
}
