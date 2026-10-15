/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import java.util.List;
import java.util.Optional;

/**
 * A refusal about the arguments of a call on the assistant surface.
 *
 * <p>The generic surface takes its values from a path, a header and a body,
 * and refuses there in the vocabulary of HTTP. The assistant surface takes
 * every value as a named argument of one call, so it refuses in the vocabulary
 * of arguments: one it does not declare, one it needs and did not get, one
 * whose value is not of the declared kind. Those three, plus the failure
 * nobody foresaw, are the reasons this type adds, and the reason catalogue
 * declares each of them beside the domain's own.
 *
 * <p>What a message is built from travels as the placeholder types the
 * declaration builds -- the argument's name, its position, the names the call
 * declares -- and the exception carries no sentence: every message on either
 * surface is worded from the reason catalogue's bundles and nothing else.
 */
public class CallException extends RuntimeException {

    /** The conditions only the assistant surface refuses on. */
    public enum Reason {

        /** The call names an argument it does not declare, at any level. */
        ARGUMENT_UNKNOWN,

        /** The call lacks an argument it declares as required. */
        ARGUMENT_MISSING,

        /** The call carries an argument whose value is not of the declared kind. */
        ARGUMENT_INVALID,

        /** The call failed in a way the service did not foresee. */
        UNEXPECTED_FAILURE
    }

    private final transient Reason reason;
    private final transient ArgumentName name;
    private final transient ArgumentPath path;
    private final transient List<ArgumentName> accepts;

    private CallException(Reason reason, ArgumentName name, ArgumentPath path,
                          List<ArgumentName> accepts) {
        super(reason.name());
        this.reason = reason;
        this.name = name;
        this.path = path;
        this.accepts = List.copyOf(accepts);
    }

    /**
     * An argument the call does not declare, answered with the names it does
     * declare and never with the one that arrived (DEC-0043 R19): that name is
     * the caller's input, and the caller still holds it.
     *
     * @param declared the names the caller could have written at that level,
     *                 in declaration order
     */
    public static CallException unknown(List<ArgumentName> declared) {
        return new CallException(Reason.ARGUMENT_UNKNOWN, null, null, declared);
    }

    /** A required argument absent or sent as {@code null} (DEC-0043 R17). */
    public static CallException missing(ArgumentName name) {
        return new CallException(Reason.ARGUMENT_MISSING, name, ArgumentPath.of(name), List.of());
    }

    /**
     * A value not of the declared kind, named by its position and never by the
     * value sent: a refusal is logged, and a value can be content (DEC-0042).
     */
    public static CallException invalid(ArgumentPath path) {
        return new CallException(Reason.ARGUMENT_INVALID, null, path, List.of());
    }

    public Reason reason() {
        return reason;
    }

    /** The argument a missing-argument refusal names. */
    public Optional<ArgumentName> name() {
        return Optional.ofNullable(name);
    }

    /** The position the refusal concerns, as {@code offenders} names it. */
    public Optional<ArgumentPath> path() {
        return Optional.ofNullable(path);
    }

    /** The names the call declares where an undeclared one arrived; empty otherwise. */
    public List<ArgumentName> accepts() {
        return accepts;
    }
}
