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
 * What each argument is, as the input schema and the declaration describe it.
 * The texts are in {@code messages/argument.properties}, phrased positively
 * (DEC-0043 R21). Every optional argument names its default and states that
 * {@code null} does the same as leaving it out (DEC-0043 R17).
 */
@MessageBundle("argument")
public interface ArgumentDescriptions {

    @Message String scope();

    @Message String address();

    @Message String conflictToken();

    @Message String fields();

    @Message String createSelector();

    @Message String createKey();

    @Message String createType();

    @Message String createContent(Bound limit);

    @Message String createReference();

    @Message String updateContent(Bound limit);

    @Message String updateType();

    @Message String updateReference();

    @Message String querySelector();

    @Message String queryType();

    @Message String queryText();

    @Message String queryAfter();

    @Message String querySort();

    @Message String queryDirection();

    @Message String queryPageSize(Bound min, Bound max, Bound fallback);

    @Message String digestTypes();
}
