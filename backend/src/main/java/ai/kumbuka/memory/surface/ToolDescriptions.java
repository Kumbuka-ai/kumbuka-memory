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
 * The description of each call, as the tool list and the declaration carry
 * it. The texts are in {@code messages/tool.properties}, phrased positively
 * (DEC-0043 R21).
 */
@MessageBundle("tool")
public interface ToolDescriptions {

    @Message String create(VerbName update);

    @Message String read();

    @Message String update();

    @Message String withdraw();

    @Message String query();

    @Message String digest();
}
