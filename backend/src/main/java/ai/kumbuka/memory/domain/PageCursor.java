/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import ai.kumbuka.memory.repository.StoredInstant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Where a listing stopped, and which listing it was.
 *
 * <h2>Why the cursor carries the query it was handed out for</h2>
 *
 * A position on its own continues any listing. The cursor this replaces was
 * the last key and nothing else, and it was measured being taken on another
 * selector and in another scope without a word: an answer of zero entries
 * that reads exactly like "there is nothing further here". So the cursor
 * carries its {@link Binding} — the scope, the selector, a fingerprint of the
 * filter, the order and its direction — and a cursor whose binding is not the
 * query's is refused {@code CURSOR_FOREIGN} rather than applied.
 *
 * <h2>Why the scope travels as a mark of its identity and not as its name</h2>
 *
 * A name is unique within one estate and nowhere else: every tenant has a
 * scope called {@code global}. A cursor that bound the scope by its slug was
 * measured being taken by tenant B on B's own {@code global} after tenant A
 * had been handed it on A's — not a leak, since B's page held B's entries, but
 * a page positioned by another estate's last key, which skips whatever of B's
 * sorts before it without a word. So the scope is bound by a digest of its
 * technical identity, which no two estates share; and a digest rather than
 * the identity itself, because a uuid on this surface is a value no answer
 * carries.
 *
 * <h2>What it is not</h2>
 *
 * Not a secret and not signed. It is Base64url of a short text, and a caller
 * that decodes it learns the key of the last entry of a page it was already
 * given. What the encoding buys is that the cursor reads as a token to hand
 * back unchanged rather than as a value to compose; what the binding buys is
 * that handing it back to the wrong query is refused. A caller that forges a
 * binding gets the page of a query it was allowed to ask anyway.
 *
 * <h2>The form, version 1</h2>
 *
 * Eight lines: {@code v1}, the scope's {@link #mark}, selector (empty for the
 * whole scope), filter fingerprint, order, direction, the moment of the last entry in microseconds
 * since the epoch, and its key. The key is last because it is the one value of
 * an entry, and nothing a key may contain is a line break. The moment travels
 * in the precision the column keeps ({@link StoredInstant#COLUMN_PRECISION}),
 * so that the keyset comparison meets the stored value exactly; a finer value
 * would sit between two stored ones and skip a tie.
 *
 * @param binding   the query this cursor continues
 * @param key       the key of the last entry the page carried
 * @param createdAt the moment that entry was laid down
 */
public record PageCursor(Binding binding, String key, Instant createdAt) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "\n";
    private static final int LINES = 8;

    /** The argument a cursor travels in, and the one its refusals name. */
    public static final String AFTER = "after";

    /** How many hexadecimal digits of a digest the cursor keeps. */
    private static final int FINGERPRINT_DIGITS = 16;

    /**
     * The query a cursor belongs to.
     *
     * <p>The page size is deliberately not part of it: a caller may read the
     * next page in a different size, and the position is the same position.
     *
     * @param scope     the {@link #mark} of the scope's technical identity, as the
     *                  read contract resolved it
     * @param selector  the selector, or null for the whole scope
     * @param filter    the {@link #fingerprint} of type and text
     * @param sort      the order
     * @param direction which way it runs
     */
    public record Binding(String scope, String selector, String filter, SortKey sort,
                          SortKey.Direction direction) {
    }

    /**
     * The filter, reduced to a value that changes whenever the filter does.
     *
     * <p>A digest rather than the values themselves: the text a caller
     * searched for has no business travelling in a token that is handed
     * around, and a digest keeps the cursor the same length whatever was
     * searched.
     */
    public static String fingerprint(EntryType type, String text) {
        return digestOf("type=" + (type == null ? "" : type.wire())
            + SEPARATOR + "text=" + (text == null ? "" : text));
    }

    /**
     * A scope, reduced to a value that names it in one estate and in no other.
     *
     * <p>Its technical identity rather than its slug, for the reason the class
     * gives; digested, so that the identity itself stays off the wire.
     */
    public static String mark(UUID scopeId) {
        return digestOf("scope=" + scopeId);
    }

    private static String digestOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_DIGITS);
        } catch (NoSuchAlgorithmException absent) {
            // Every Java platform carries SHA-256 (MessageDigest's own contract),
            // so this is a broken runtime and not a refusal of the caller's.
            throw new IllegalStateException("the runtime carries no SHA-256", absent);
        }
    }

    /** The cursor as a caller receives it. */
    public String encode() {
        String text = String.join(SEPARATOR, List.of(
            VERSION,
            binding.scope(),
            binding.selector() == null ? "" : binding.selector(),
            binding.filter(),
            binding.sort().wire(),
            binding.direction().wire(),
            String.valueOf(StoredInstant.COLUMN_PRECISION.between(Instant.EPOCH, createdAt)),
            key));
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A cursor as a caller handed it back.
     *
     * <p>Only its form is checked here. Whether it belongs to the query it was
     * handed to is the caller's of this method to decide, against the
     * resolved scope, which is not known yet when the form can already be
     * judged.
     */
    public static PageCursor decode(String cursor) {
        String[] lines = textOf(cursor).split(SEPARATOR, -1);
        if (lines.length != LINES || !VERSION.equals(lines[0])
            || lines[1].isEmpty() || lines[7].isEmpty()) {
            throw malformed();
        }
        SortKey sort = SortKey.of(lines[4]).orElseThrow(PageCursor::malformed);
        SortKey.Direction direction =
            SortKey.Direction.of(lines[5]).orElseThrow(PageCursor::malformed);
        long micros;
        try {
            micros = Long.parseLong(lines[6]);
        } catch (NumberFormatException notANumber) {
            throw malformed();
        }
        Binding binding = new Binding(lines[1], lines[2].isEmpty() ? null : lines[2],
            lines[3], sort, direction);
        return new PageCursor(binding, lines[7],
            Instant.EPOCH.plus(micros, StoredInstant.COLUMN_PRECISION));
    }

    private static String textOf(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw malformed();
        }
    }

    private static MemoryException malformed() {
        return MemoryException.offending(MemoryException.Reason.CURSOR_MALFORMED,
            "this is not a cursor this verb handed out. A cursor is taken from the "
                + "'next_cursor' member of a previous page and passed back unchanged as "
                + "'after'; it is not composed by the caller. Start again without one to get "
                + "the first page.",
            List.of(AFTER));
    }
}
