/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

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
 * never handed to the logger; {@link FailureCause} reads the class names off
 * it, and is made here and nowhere else, as {@link FailureReference} is.
 */
public final class UnexpectedFailures {

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Logs an unforeseen failure once, at ERROR, and returns the reference it
     * was logged under.
     *
     * <p>The line carries the call, the reference and the class names. Not
     * the address and not the key: whether those may stand in a log line is
     * not decided, and a line written before it is decided cannot be taken
     * back. The call is the one the declaration names, never a name a request
     * carried.
     *
     * @param call    the call the caller made; absent for a request that had
     *                become no call yet
     * @param failure what went wrong
     */
    public static String logged(@Nullable VerbName call, Throwable failure) {
        FailureReference reference = new FailureReference(UUID.randomUUID());
        FailureCause cause = new FailureCause(failure);
        if (call == null) {
            PlatformLog.unexpectedFailureBeforeACall(reference, cause);
        } else {
            PlatformLog.unexpectedFailure(call, reference, cause);
        }
        return reference.toString();
    }

    /** The reference a caller is told to report, minted for one failure. */
    public static final class FailureReference {

        private final UUID reference;

        private FailureReference(UUID reference) {
            this.reference = reference;
        }

        @Override
        public String toString() {
            return reference.toString();
        }
    }

    /**
     * The class names of a failure and its causes, outermost first. Read off
     * the chain by class and nothing else: no message, no stack frame.
     */
    public static final class FailureCause {

        private final String chain;

        private FailureCause(Throwable failure) {
            List<String> names = new ArrayList<>();
            Throwable at = failure;
            while (at != null && names.size() < CHAIN_LIMIT) {
                names.add(at.getClass().getName());
                at = at.getCause() == at ? null : at.getCause();
            }
            this.chain = String.join(" <- ", names);
        }

        @Override
        public String toString() {
            return chain;
        }
    }
}
