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
 * What the caller does next, per refusal reason: the second half of every
 * refusal's message, on both surfaces. The texts are in
 * {@code messages/remedy.properties}.
 *
 * <p>Each is an instruction, phrased as the act to perform and never as one
 * to avoid (DEC-0043 R21); {@code PositivePhrasingLintTest} holds the file to
 * it. A call the remedy names is a placeholder filled with the call's own name,
 * so that a remedy never names a call this service does not carry.
 */
@MessageBundle("remedy")
public interface RefusalRemedies {

    @Message String notFound();

    @Message String scopeLocked(VerbName read, VerbName query, VerbName digest);

    @Message String idempotencyKeyReused();

    @Message String scopeReadOnly(VerbName read, VerbName query, VerbName digest);

    @Message String addressMalformed();

    @Message String keyMalformed();

    @Message String selectorAbsent();

    @Message String selectorReserved();

    @Message String selectorMismatched();

    @Message String alreadyExists(VerbName read, VerbName update);

    @Message String conflictTokenStale(VerbName read);

    @Message String updateEmpty();

    @Message String typeUnknown();

    @Message String contentAbsent();

    @Message String contentOversize(Bound limit);

    @Message String referenceCredentialBearing();

    @Message String pageSizeRejected(Bound min, Bound max);

    @Message String cursorMalformed(VerbName query);

    @Message String cursorForeign(VerbName query);

    @Message String sortUndeclared();

    @Message String actorUnknown();

    @Message String argumentUnknown();

    @Message String argumentMissing(ArgumentName name);

    @Message String argumentInvalid(ArgumentPath path);

    @Message String unexpectedFailure();

    @Message String conflictTokenMissing(VerbName read);

    @Message String fieldImmutable();

    @Message String payloadMalformed();

    @Message String verbNotCarried();

    @Message String addressTruncated();

    @Message String sessionNotBound();

    /** The header that names the surface, sent with a value it cannot take (C14.5). */
    @Message String headerInvalid();
}
