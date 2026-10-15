/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * What a next step does, as {@code next} says it beside the call. The texts
 * are in {@code messages/next.properties}, phrased positively (DEC-0043 R21).
 *
 * <p>The assistant surface names a call by its tool and says what it does;
 * the generic surface names it with the address and says what to do. Both
 * sets are here, so that the two surfaces cannot come to describe one step in
 * two places.
 */
@MessageBundle("next")
public interface NextStepTexts {

    @Message String create();

    @Message String read();

    @Message String update();

    @Message String withdraw();

    @Message String query();

    @Message String digest();

    /** After a withdrawal that freed the address. */
    @Message String afterRelease();

    /** After a withdrawal that left the entry at its address. */
    @Message String afterRetirement();

    @Message String genericRead();

    @Message String genericUpdate();

    @Message String genericWithdraw();

    @Message String genericAfterRelease();

    @Message String genericAfterRetirement();
}
