/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where the reference of an unforeseen failure is minted, and the only place
 * (C8.3).
 *
 * <p>The caller is told to report the reference under {@code data.reference},
 * so the reference is worth exactly as much as the log line it finds. Minting
 * it here, in the same call that writes it to the log beside the failure, is
 * what makes it impossible to hand out a reference the log does not carry.
 *
 * <h2>The failure's class names, never its message</h2>
 *
 * The line carries the class names of the failure and of every cause, and
 * nothing else of them (DEC-0051, C13.2). A message is text nobody in this
 * service wrote: a database that refuses a row names the row, and the row
 * holds an entry's content. A stack trace says where, and the class names
 * already say what failed; the reference finds the line. So the failure is
 * never handed to the logger; the line is assembled here from the parts that
 * cannot carry content.
 */
public final class UnexpectedFailures {

    /**
     * ERROR, because a failure nobody foresaw is ours and no retry of the
     * caller's fixes it, and the only refusal this service logs at ERROR
     * (C13.3).
     *
     * <p>The line carries the call, the reference and the class names. Not
     * the address and not the key: whether those may stand in a log line is
     * not decided, and a line written before it is decided cannot be taken
     * back.
     */
    private static final Logger LOG = Logger.getLogger(UnexpectedFailures.class);

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Logs an unforeseen failure once and returns the reference it was logged
     * under.
     *
     * @param call    the call the caller made; absent for a request that had
     *                become no call yet
     * @param failure what went wrong
     */
    public static String logged(@Nullable String call, Throwable failure) {
        // Both put in locals first: the line names no exception and no member
        // of an entry, only the identifier minted here and the class names.
        String minted = UUID.randomUUID().toString();
        String chain = classNames(failure);
        LOG.errorf("unexpected failure on %s, reference %s, cause %s",
            call == null ? "this request" : call, minted, chain);
        return minted;
    }

    /** The class names of a failure and its causes, outermost first. */
    static String classNames(Throwable failure) {
        List<String> names = new ArrayList<>();
        Throwable at = failure;
        while (at != null && names.size() < CHAIN_LIMIT) {
            names.add(at.getClass().getName());
            at = at.getCause() == at ? null : at.getCause();
        }
        return String.join(" <- ", names);
    }
}
