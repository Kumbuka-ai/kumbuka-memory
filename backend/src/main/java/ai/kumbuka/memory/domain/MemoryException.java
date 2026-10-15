/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A typed refusal, naming what was refused, why, and what to do instead.
 *
 * <p>Every refusal in this service carries a {@link Reason} rather than only a
 * message. A caller that has to match on prose is a caller that breaks when
 * somebody improves the wording, and an adapter that cannot tell "you may not
 * do that yet" from "that does not exist" cannot map either onto its own
 * protocol without guessing.
 *
 * <p>The message is for a human and names the specifics; the reason is for a
 * caller and is stable. Everything machine-readable travels in {@link #data()}
 * and reaches the wire under {@code data}, which is where DEC-0042 puts it.
 *
 * <h2>The message says what to do</h2>
 *
 * Not a convention of this class but a rule of the surface: a refusal names
 * the remedy where there is one, and names the complete address wherever there
 * is one to name (DEC-0040). A message that only states the rule sends the
 * reader back to work out what it was talking about.
 */
public class MemoryException extends RuntimeException {

    /**
     * The conditions this service refuses on.
     *
     * <h2>Two of these are collapsed on the wire, and it is deliberate</h2>
     *
     * {@link #ENTRY_ABSENT} and {@link #SCOPE_UNRESOLVED} are different facts
     * and the domain keeps them apart — the first is an address with nothing
     * behind it, the second is a scope this caller may not enter. On the wire
     * they are one code with one message and no {@code data}, because a caller
     * able to tell them apart could map what it may not see. The collapse
     * happens once, at the envelope, and never here.
     *
     * <h2>Why ALREADY_EXISTS is spelled without a subject</h2>
     *
     * Every other constant here reads {@code <SUBJECT>_<STATE>}, which is the
     * form {@code spec/code-conventions/service-construction.md} binds. This
     * one does not, and it is not an oversight: it is the platform-wide code
     * the operator decided for a write onto an occupied address (D-CORE-21
     * stage 2), and a code that means the same condition on every service is
     * worth more to a caller than a name that satisfies a local form rule. The
     * collision between the two rules is reported rather than resolved here.
     */
    public enum Reason {

        // --- the scope, as the platform's read contract answers for it ------

        /** The scope could not be resolved against the platform's read contract. */
        SCOPE_UNRESOLVED,
        /** The scope is frozen: no write over a service channel, whatever the caller. */
        SCOPE_LOCKED,
        /** This caller may read the scope but not write to it over a service channel. */
        SCOPE_READ_ONLY,
        /** The session settings the read contract needs were not bound. */
        SESSION_NOT_BOUND,

        // --- the address -----------------------------------------------------

        /** Nothing stands at this address that this caller could reach. */
        ENTRY_ABSENT,
        /** The address is not one this scheme can carry. */
        ADDRESS_MALFORMED,
        /** The key is not of the shape this service stores. */
        KEY_MALFORMED,
        /** The key carries no selector, so the entry would have no address. */
        SELECTOR_ABSENT,
        /** The selector is reserved for the service itself. */
        SELECTOR_RESERVED,
        /** The address names one selector and the key carries another. */
        SELECTOR_MISMATCHED,

        // --- writing ---------------------------------------------------------

        /** An entry already stands at this address. */
        ALREADY_EXISTS,
        /** The write carried no conflict token. */
        CONFLICT_TOKEN_MISSING,
        /** The conflict token is not the one the object carries now. */
        CONFLICT_TOKEN_STALE,
        /** The update names no field this verb can change. */
        UPDATE_EMPTY,
        /** The update names a field that is fixed for the life of the entry. */
        FIELD_IMMUTABLE,

        // --- the values ------------------------------------------------------

        /** The type is not one of the six this service carries. */
        TYPE_UNKNOWN,
        /** The entry carries no content. */
        CONTENT_ABSENT,
        /** The content is longer than an entry may be. */
        CONTENT_OVERSIZE,
        /** The provenance pointer carries a credential. */
        REFERENCE_CREDENTIAL_BEARING,

        // --- the call --------------------------------------------------------

        /** The call names an argument it does not declare. */
        ARGUMENT_UNKNOWN,
        /** The call carries an argument whose value is not one it takes. */
        ARGUMENT_INVALID,

        // --- reading ---------------------------------------------------------

        /** The page size is outside what this verb serves. */
        PAGE_SIZE_REJECTED,
        /** The cursor is not one this verb handed out. */
        CURSOR_MALFORMED,
        /** The cursor was handed out for another query: scope, selector, filter or order. */
        CURSOR_FOREIGN,
        /** The order is not one this verb declares. */
        SORT_UNDECLARED,

        // --- the caller ------------------------------------------------------

        /** The token authenticated but names no subject to act as. */
        ACTOR_UNKNOWN
    }

    /**
     * The key under which a refusal names what caused it. For a refusal of an
     * argument that is the argument's name, never its value (DEC-0042).
     */
    public static final String OFFENDERS = "offenders";

    /**
     * The key under which a refusal of an undeclared name lists the names the
     * call declares instead (DEC-0042, DEC-0043 R19). The name that arrived is
     * the caller's input and is never repeated.
     */
    public static final String ACCEPTS = "accepts";

    /** The key under which a refusal names a complete address. */
    public static final String ADDRESS = "address";

    private final transient Reason reason;
    private final transient Map<String, Object> data;

    public MemoryException(Reason reason, String message) {
        this(reason, message, Map.of());
    }

    public MemoryException(Reason reason, String message, Map<String, Object> data) {
        super(message);
        this.reason = reason;
        this.data = Map.copyOf(data);
    }

    /**
     * A refusal naming the objects that caused it.
     *
     * <p>A refusal that only states the rule sends the reader back to the
     * store to work out what it was talking about. The whole value of checking
     * where the check runs is that the answer is right there.
     */
    public static MemoryException offending(Reason reason, String message,
                                            List<String> offenders) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(OFFENDERS, List.copyOf(offenders));
        return new MemoryException(reason, message, data);
    }

    /** A refusal of names the call does not declare, listing those it does. */
    public static MemoryException undeclared(String message, List<String> accepts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ACCEPTS, List.copyOf(accepts));
        return new MemoryException(Reason.ARGUMENT_UNKNOWN, message, data);
    }

    public Reason reason() {
        return reason;
    }

    /** Everything machine-readable about this refusal. Empty, never null. */
    public Map<String, Object> data() {
        return data;
    }
}
