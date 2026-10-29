/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.contract.ReferenceCanonicalJson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration's version is a hash over its content (ADR-0039): any change
 * yields a new one, and the same content the same one whatever order its keys
 * were written in. It is taken over the canonical form of the reference
 * (C5.3), so a text that carries a control character is written the way the
 * reference writes it.
 */
class DeclarationVersionTest {

    @Test
    void the_version_follows_the_content_and_nothing_else() {
        Map<String, Object> declaration = declaration("Reads one entry.");
        String version = CanonicalJson.version(declaration);

        assertThat(version).startsWith("sha256:").hasSize("sha256:".length() + 64);
        assertThat(CanonicalJson.version(reordered(declaration)))
            .as("the order the keys were written in is not content")
            .isEqualTo(version);
        assertThat(CanonicalJson.version(declaration("Reads one entry again.")))
            .as("RED STATE, observed: a changed text is a new version")
            .isNotEqualTo(version);
    }

    @Test
    void a_text_with_a_line_break_is_written_as_the_reference_writes_it() {
        Map<String, Object> declaration = declaration("Reads one entry.\nThen another.\t\u0001");

        assertThat(CanonicalJson.write(declaration))
            .as("the line feed, the tab and a character below 0x20 escaped, and a comma "
                + "between members and between elements only")
            .isEqualTo("{\"calls\":[{\"description\":\"Reads one entry.\\nThen another."
                + "\\t\\u0001\",\"identity\":\"memory_read\",\"refusals\":[\"NOT_FOUND\"]}],"
                + "\"scheme\":\"memory\"}")
            .isEqualTo(ReferenceCanonicalJson.write(declaration));
        assertThat(CanonicalJson.version(declaration))
            .isEqualTo(ReferenceCanonicalJson.version(declaration));
    }

    private static Map<String, Object> declaration(String description) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("identity", "memory_read");
        call.put("description", description);
        call.put("refusals", List.of("NOT_FOUND"));
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("scheme", "memory");
        declaration.put("calls", List.of(call));
        return declaration;
    }

    private static Map<String, Object> reordered(Map<String, Object> declaration) {
        Map<String, Object> reordered = new LinkedHashMap<>();
        List.copyOf(declaration.keySet()).reversed()
            .forEach(key -> reordered.put(key, declaration.get(key)));
        return reordered;
    }
}
