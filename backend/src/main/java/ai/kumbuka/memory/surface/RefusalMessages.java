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

import java.util.List;

/**
 * What went wrong, per refusal reason: the first half of every refusal's
 * message, on both surfaces. The texts are in {@code messages/refusal.properties}.
 *
 * <p>A method is named after its reason, the code in lower camel case, so that
 * the declaration can publish each text beside its code. A finding may be
 * stated in the negative here; the remedy that follows it may not
 * ({@link RefusalRemedies}).
 */
@MessageBundle("refusal")
public interface RefusalMessages {

    @Message String notFound();

    @Message String scopeLocked(VerbName call);

    @Message String idempotencyKeyReused(VerbName call);

    @Message String scopeReserved();

    @Message String scopeReadOnly(VerbName call);

    @Message String addressMalformed(VerbName call);

    @Message String keyMalformed(VerbName call);

    @Message String selectorAbsent(VerbName call);

    @Message String selectorReserved();

    @Message String selectorMismatched(VerbName call);

    @Message String alreadyExists(VerbName call);

    @Message String conflictTokenStale(VerbName call);

    @Message String updateEmpty(VerbName call);

    @Message String typeUnknown(VerbName call);

    @Message String contentAbsent(VerbName call);

    @Message String contentOversize(Bound limit);

    @Message String referenceCredentialBearing();

    @Message String pageSizeRejected(VerbName call);

    @Message String cursorMalformed(VerbName query);

    @Message String cursorForeign(VerbName query);

    @Message String sortUndeclared(VerbName call);

    @Message String actorUnknown();

    @Message String argumentUnknown(VerbName call, List<ArgumentName> arguments);

    @Message String argumentMissing(VerbName call, ArgumentName name);

    @Message String argumentInvalid(VerbName call, ArgumentPath path);

    @Message String unexpectedFailure(VerbName call);

    /** A failure nobody foresaw, on a request that had become no call yet. */
    @Message String unexpectedFailureOfRequest();

    @Message String conflictTokenMissing(VerbName call);

    @Message String fieldImmutable();

    @Message String payloadMalformed();

    @Message String verbNotCarried();

    @Message String addressTruncated();

    @Message String sessionNotBound(VerbName call);

    /** The header that names the surface, sent with a value it cannot take (C14.5). */
    @Message String headerInvalid();
}
