/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import io.quarkus.qute.TemplateExtension;

import java.util.List;
import java.util.stream.Collectors;

/**
 * How a text writes a list placeholder: its elements joined by a comma.
 *
 * <p>A template extension rather than a loop in every text, so that a list
 * reads the same wherever a text names one, written {@code {names.joined}}.
 */
@TemplateExtension
public final class Placeholders {

    private Placeholders() {
    }

    static String joined(List<?> elements) {
        return elements.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
