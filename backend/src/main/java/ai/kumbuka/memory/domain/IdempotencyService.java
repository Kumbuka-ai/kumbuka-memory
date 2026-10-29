/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * What an idempotency key compares: a digest of the call's arguments.
 *
 * <p>The rule a key carries has three outcomes, and {@link MemoryService}
 * keeps it on the ledger of V10: no key, or one this caller did not spend in
 * this scope in {@link SpentKey#REMEMBERED_FOR}, and the call runs; the same
 * key on the same call with the same arguments, and the call answers the entry
 * the first one laid down while it still stands; anything else, and {@code
 * IDEMPOTENCY_KEY_REUSED}. What is left here is the comparison those outcomes
 * turn on, in the form of the reference service.
 */
public final class IdempotencyService {

    private IdempotencyService() {
    }

    /**
     * A digest over what the call writes, in a canonical form.
     *
     * <p>Order-bearing and separator-delimited: the values are joined with a
     * character that cannot occur in any of them, so {@code ["ab", "c"]} and
     * {@code ["a", "bc"]} cannot collide into one digest. An absent value gets
     * a mark of its own rather than the empty segment, because "absent" and
     * "empty" are different calls and a digest that could not tell them apart
     * would treat one as a repeat of the other.
     */
    public static String digestOf(List<@Nullable String> values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            canonical.append(value == null ? "\u001E" : value).append('\u001F');
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
