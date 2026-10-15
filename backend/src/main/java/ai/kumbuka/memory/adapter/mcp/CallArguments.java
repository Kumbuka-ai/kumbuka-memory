/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.ArgumentName;
import ai.kumbuka.memory.surface.ArgumentPath;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallException;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * The arguments of one tool call, checked against the declaration before a
 * verb sees them.
 *
 * <h2>No argument is accepted and discarded</h2>
 *
 * An argument the call does not declare is refused by name, at the top level
 * and inside {@code fields} alike. A dropped argument answers exactly as if
 * the caller had not sent it, which is the one failure a caller cannot see:
 * a misspelt {@code page_size} returns fifty entries and looks like a correct
 * answer to a question nobody asked.
 *
 * <h2>The order of the checks</h2>
 *
 * Unknown names first, then the declared arguments in declaration order, each
 * either missing or of the wrong kind. A value that is of the right kind and
 * still not acceptable — a page size of a thousand, a type that is not one of
 * the six — is not judged here: the verb judges it, and refuses with the
 * service's own reason, so that both surfaces refuse it the same way.
 *
 * <p>The page size is the one argument whose wrong kind is not
 * {@code ARGUMENT_INVALID}. Every page size the verb cannot take — out of
 * range, not a number, not a whole one — is {@code PAGE_SIZE_REJECTED}
 * (ADR-0049), on this surface as on the generic one, where a query parameter
 * has no kind to be wrong in.
 */
final class CallArguments {

    private final Map<String, Object> top;
    private final Map<String, Object> fields;

    CallArguments(AssistantVerb verb, Map<String, Object> arguments) {
        this.top = arguments;
        refuseUndeclared(top, verb.topNames());
        this.fields = fieldsOf(verb, top);
        refuseUndeclared(fields, verb.fieldNames());

        for (Argument argument : verb.topArguments()) {
            check(argument, top.get(argument.name()));
        }
        if (verb.writesFields() && top.get(AssistantVerb.FIELDS) == null) {
            throw CallException.missing(AssistantVerb.fieldsName());
        }
        for (Argument argument : verb.fieldArguments()) {
            check(argument, fields.get(argument.name()));
        }
        if (verb == AssistantVerb.UPDATE && "".equals(fields.get(AssistantVerb.Names.REFERENCE))) {
            // An empty text would be read by the verb as "not mentioned" and
            // dropped, so the caller would believe it had removed the
            // reference while the entry kept it. Refused, because this
            // surface accepts nothing it then discards.
            throw CallException.invalid(ArgumentPath.of(
                verb.declaredName(AssistantVerb.Names.REFERENCE).orElseThrow()));
        }
    }

    // ======================================================================
    // Reading the checked values
    // ======================================================================

    String top(String name) {
        return (String) top.get(name);
    }

    String field(String name) {
        return (String) fields.get(name);
    }

    /** A whole number, written as text so that the verb parses it as it parses any. */
    String number(String name) {
        Object value = top.get(name);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    List<String> list(String name) {
        return (List<String>) top.get(name);
    }

    // ======================================================================
    // The checks
    // ======================================================================

    private static void refuseUndeclared(Map<String, Object> given,
                                         List<ArgumentName> declared) {
        List<String> spelled = declared.stream().map(ArgumentName::value).toList();
        if (!spelled.containsAll(given.keySet())) {
            throw CallException.unknown(declared);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(AssistantVerb verb, Map<String, Object> top) {
        Object raw = top.get(AssistantVerb.FIELDS);
        if (!verb.writesFields() || raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map)) {
            throw CallException.invalid(ArgumentPath.of(AssistantVerb.fieldsName()));
        }
        return (Map<String, Object>) raw;
    }

    private static void check(Argument argument, Object value) {
        if (value == null) {
            if (argument.required()) {
                throw CallException.missing(argument.argumentName());
            }
            return;
        }
        boolean ofItsKind = switch (argument.type()) {
            case Argument.STRING -> value instanceof String;
            case Argument.INTEGER -> isWholeNumber(value);
            case Argument.ARRAY -> {
                requireTextElements(argument.argumentName(), value);
                yield value instanceof List<?> list && !list.isEmpty();
            }
            default -> throw new IllegalStateException(
                argument.name() + " is declared with a type the check does not know");
        };
        if (!ofItsKind) {
            if (AssistantVerb.Names.PAGE_SIZE.equals(argument.name())) {
                throw PageRequest.pageSizeRejected();
            }
            throw CallException.invalid(ArgumentPath.of(argument.argumentName()));
        }
    }

    private static boolean isWholeNumber(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigInteger;
    }

    /**
     * Refuses a list element that is not a text, naming its position as the
     * argument's path ({@code types[2]}) and never the value (DEC-0043 R19).
     */
    private static void requireTextElements(ArgumentName name, Object value) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (int at = 0; at < list.size(); at++) {
            if (!(list.get(at) instanceof String)) {
                throw CallException.invalid(ArgumentPath.at(name, at));
            }
        }
    }
}
