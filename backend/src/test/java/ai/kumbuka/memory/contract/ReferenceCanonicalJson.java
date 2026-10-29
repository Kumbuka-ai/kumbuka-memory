/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical form of the reference, held in the test tree: a copy of
 * {@code surface/CanonicalJson.java} of kumbuka-dispatch at {@code d8d28b9},
 * which kumbuka-worklist carries word for word (C5.3).
 *
 * <p>The members of every object in the order of their names, a comma between
 * members and between elements and nowhere else, no whitespace; a string with
 * {@code "}, {@code \}, line feed, carriage return and tab escaped by their
 * short form and every other character below 0x20 as {@code \}{@code uXXXX};
 * the version is {@code sha256:} and the hex digest of the UTF-8 text. A
 * router that recomputes a service's version does it this way, so a probe
 * that recomputes memory's does it this way too, and with nothing of
 * memory's own: it is a counter-probe, and imports nothing from the
 * production packages.
 */
@CounterProbe
public final class ReferenceCanonicalJson {

    private ReferenceCanonicalJson() {
    }

    /** The version of a value: {@code sha256:} and the hex digest of its canonical text. */
    public static String version(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(write(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime carries SHA-256", e);
        }
    }

    /** The canonical text of a value built from maps, lists, strings, numbers and booleans. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> member : sorted.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(out, member.getKey());
                    out.append(':');
                    append(out, member.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    append(out, list.get(i));
                }
                out.append(']');
            }
            case String text -> string(out, text);
            case Boolean flag -> out.append(flag);
            case Number number -> out.append(number);
            default -> string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
