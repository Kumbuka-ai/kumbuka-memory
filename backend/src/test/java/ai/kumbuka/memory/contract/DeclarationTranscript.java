/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;
import java.util.List;
import java.util.Map;

/**
 * The service's declaration, transcribed by hand: the counter-probe of
 * ADR-0039.
 *
 * <p>Written from the contract copy, the router's catalogue and the dispatch
 * that asked for the declaration, and never from the code that serves it.
 * This file imports nothing from the production packages, and
 * {@link DeclarationEndpointIT} holds that: an expectation drawn from the
 * artefact it checks is internally consistent and proves nothing. Where the
 * served declaration and this transcription differ, one of them is wrong, and
 * the difference is the finding.
 *
 * <p>The effect classes are the router's names
 * ({@code router/.../verb/VerbCatalog.java}, {@code EffectClass}, read
 * 2026-10-15): memory's {@code read} an {@code ITEM_READ} that also takes the
 * technical address, {@code query} a {@code COLLECTION_READ} that reaches the
 * scope as a {@code SCOPE_READ}, {@code digest} a {@code SCOPE_COMPOUND}.
 */
@CounterProbe
final class DeclarationTranscript {

    private DeclarationTranscript() {
    }

    /**
     * One argument: its name, type, whether required, where it sits, default,
     * the values it takes.
     */
    record Arg(String name, String type, boolean required, String placement, Object fallback,
               List<String> values) {

        Arg(String name, String type, boolean required, Object fallback, List<String> values) {
            this(name, type, required, "TOP", fallback, values);
        }

        /** The same argument, written under {@code fields}. */
        Arg field() {
            return new Arg(name, type, required, "FIELDS", fallback, values);
        }
    }

    /** One verb as the declaration must carry it. */
    record Verb(String identity, String restName, String effectClass, String atScopeDepth,
                boolean technicalAddress, List<Arg> arguments, String answer,
                List<String> members, List<String> refusals) {
    }

    /** The values of the type an entry carries, as the service takes them today. */
    static final List<String> TYPES = List.of("constraint", "decision", "convention",
        "glossary", "status", "open_question");

    /**
     * The selectors, fixed by the service: the word of each type, with hyphens
     * (contract C5.4: a selector the service fixes is declared as fixed values).
     */
    static final List<String> SELECTORS = List.of("constraint", "decision", "convention",
        "glossary", "status", "open-question");

    private static final List<String> ENTRY = List.of("address", "fields", "conflict_token",
        "next");

    /** Every call can be refused with these, whatever it does. */
    private static final List<String> EVERY_CALL = List.of("NOT_FOUND", "ACTOR_UNKNOWN",
        "ARGUMENT_UNKNOWN", "ARGUMENT_MISSING", "ARGUMENT_INVALID", "UNEXPECTED_FAILURE",
        "SESSION_NOT_BOUND", "VERB_NOT_CARRIED", "ADDRESS_TRUNCATED", "HEADER_INVALID");

    static List<Verb> verbs() {
        Arg scope = new Arg("scope", "string", true, null, List.of());
        Arg address = new Arg("address", "string", true, null, List.of());
        Arg token = new Arg("conflict_token", "string", true, null, List.of());
        Arg fields = new Arg("fields", "object", true, null, List.of());
        return List.of(
            new Verb("memory_create", "create", "CREATE", null, false, List.of(fields,
                scope, new Arg("selector", "string", true, null, SELECTORS),
                new Arg("key", "string", true, null, List.of()).field(),
                new Arg("type", "string", true, null, TYPES).field(),
                new Arg("content", "string", true, null, List.of()).field(),
                new Arg("reference", "string", false, null, List.of()).field()),
                "ENTRY_CREATED", ENTRY, with("SCOPE_LOCKED", "SCOPE_READ_ONLY",
                    "ADDRESS_MALFORMED", "KEY_MALFORMED", "SELECTOR_ABSENT",
                    "SELECTOR_RESERVED", "SELECTOR_MISMATCHED", "ALREADY_EXISTS",
                    "TYPE_UNKNOWN", "CONTENT_ABSENT", "CONTENT_OVERSIZE",
                    "REFERENCE_CREDENTIAL_BEARING", "FIELD_IMMUTABLE", "PAYLOAD_MALFORMED")),
            new Verb("memory_read", "read", "ITEM_READ", null, true, List.of(address),
                "ENTRY", ENTRY, with("ADDRESS_MALFORMED", "KEY_MALFORMED")),
            new Verb("memory_update", "update", "FIELD_WRITE", null, false, List.of(fields,
                address, token,
                new Arg("content", "string", false, null, List.of()).field(),
                new Arg("type", "string", false, null, TYPES).field(),
                new Arg("reference", "string", false, null, List.of()).field()),
                "ENTRY", ENTRY, with("SCOPE_LOCKED", "SCOPE_READ_ONLY", "ADDRESS_MALFORMED",
                    "KEY_MALFORMED", "SELECTOR_RESERVED", "CONFLICT_TOKEN_MISSING",
                    "CONFLICT_TOKEN_STALE", "UPDATE_EMPTY", "TYPE_UNKNOWN", "CONTENT_ABSENT",
                    "CONTENT_OVERSIZE", "REFERENCE_CREDENTIAL_BEARING", "FIELD_IMMUTABLE",
                    "PAYLOAD_MALFORMED")),
            new Verb("memory_withdraw", "withdraw", "ITEM_TRANSITION", null, false,
                List.of(address, token), "WITHDRAWN",
                List.of("address", "outcome", "address_released", "next"),
                with("SCOPE_LOCKED", "SCOPE_READ_ONLY", "ADDRESS_MALFORMED", "KEY_MALFORMED",
                    "SELECTOR_RESERVED", "CONFLICT_TOKEN_MISSING", "CONFLICT_TOKEN_STALE")),
            new Verb("memory_query", "query", "COLLECTION_READ", "SCOPE_READ", false,
                List.of(scope,
                    new Arg("selector", "string", false, null, SELECTORS),
                    new Arg("type", "string", false, null, TYPES),
                    new Arg("text", "string", false, null, List.of()),
                    new Arg("after", "string", false, null, List.of()),
                    new Arg("sort", "string", false, "id", List.of("id", "created_at")),
                    new Arg("direction", "string", false, "asc", List.of("asc", "desc")),
                    new Arg("page_size", "integer", false, 50, List.of())),
                "ENTRY_LISTING", List.of("objects", "next_cursor", "unaddressable"),
                with("ADDRESS_MALFORMED", "KEY_MALFORMED", "TYPE_UNKNOWN",
                    "PAGE_SIZE_REJECTED", "CURSOR_MALFORMED", "CURSOR_FOREIGN",
                    "SORT_UNDECLARED")),
            new Verb("memory_digest", "digest", "SCOPE_COMPOUND", null, false,
                List.of(scope, new Arg("types", "array", false, null, TYPES)),
                "DIGEST_VIEW", List.of("address", "scopes", "selected_types", "summary",
                    "total_entries", "total_token_estimate", "sections", "unaddressable",
                    "truncated"),
                with("ADDRESS_MALFORMED", "TYPE_UNKNOWN", "PAYLOAD_MALFORMED")));
    }

    /** The page size's bounds, as section 5 of the contract states them. */
    static final Map<String, Integer> PAGE_SIZE_BOUNDS = Map.of("minimum", 1, "maximum", 200);

    private static List<String> with(String... own) {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(EVERY_CALL);
        all.addAll(List.of(own));
        return List.copyOf(all);
    }
}
