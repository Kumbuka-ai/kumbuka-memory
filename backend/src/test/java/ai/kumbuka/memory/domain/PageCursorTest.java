/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cursor handed out before the type took its vocabulary form on the wire
 * (C7.6) still continues its listing after it.
 *
 * <p>Version 1 of the cursor binds the filter by a fingerprint of the type's
 * name, and up to that release the name it used was {@code open_question}.
 * The cursor here is built by hand in that form, with every digest computed in
 * this test from the form version 1 states, and never by the class under test.
 */
class PageCursorTest {

    private static final UUID SCOPE = UUID.fromString("6b1d9a3e-2f4c-4d8a-9e5b-0c7f1a2b3c4d");

    @Test
    void a_cursor_filtered_to_open_questions_before_the_release_continues_the_same_query() {
        String handedOut = "v1\n" + digest("scope=" + SCOPE) + "\nopen-question\n"
            + digest("type=open_question\ntext=") + "\nid\nasc\n1700000000000000\n"
            + "open-question.storage";
        String cursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(handedOut.getBytes(StandardCharsets.UTF_8));

        PageCursor.Binding today = new PageCursor.Binding(PageCursor.mark(SCOPE),
            "open-question", PageCursor.fingerprint(EntryType.OPEN_QUESTION, null, null),
            SortKey.ID, SortKey.Direction.ASC);

        assertThat(PageCursor.decode(cursor).binding())
            .as("the query of today binds the same filter the cursor was handed out for, "
                + "so it is continued and never refused as foreign")
            .isEqualTo(today);
    }

    @Test
    void a_cursor_with_a_tag_keeps_the_stored_name_as_well() {
        assertThat(PageCursor.fingerprint(EntryType.OPEN_QUESTION, "engine", "infra"))
            .isEqualTo(digest("type=open_question\ntext=engine\ntag=infra"));
    }

    /** The first sixteen hexadecimal digits of SHA-256, as version 1 states the digests. */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
