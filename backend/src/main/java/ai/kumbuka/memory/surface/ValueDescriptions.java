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
 * What each value of a fixed set means: the six entry types, the two orders
 * and the two directions. The texts are in {@code messages/value.properties},
 * phrased positively (DEC-0043 R21), and published with the declaration.
 */
@MessageBundle("value")
public interface ValueDescriptions {

    @Message String typeConstraint();

    @Message String typeDecision();

    @Message String typeConvention();

    @Message String typeGlossary();

    @Message String typeStatus();

    @Message String typeOpenQuestion();

    @Message String sortId();

    @Message String sortCreatedAt();

    @Message String directionAsc();

    @Message String directionDesc();
}
