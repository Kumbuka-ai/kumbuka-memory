/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.adapter.payload.AssistantPayloads;
import ai.kumbuka.memory.adapter.payload.AssistantRefusals;
import ai.kumbuka.memory.adapter.payload.CapabilitiesPayload;
import ai.kumbuka.memory.adapter.payload.EmptyPayload;
import ai.kumbuka.memory.adapter.payload.InitializePayload;
import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.adapter.payload.RpcErrorBodyPayload;
import ai.kumbuka.memory.adapter.payload.RpcErrorDataPayload;
import ai.kumbuka.memory.adapter.payload.RpcErrorPayload;
import ai.kumbuka.memory.adapter.payload.RpcResultPayload;
import ai.kumbuka.memory.adapter.payload.ServerInfoPayload;
import ai.kumbuka.memory.adapter.payload.TextContentPayload;
import ai.kumbuka.memory.adapter.payload.ToolListPayload;
import ai.kumbuka.memory.adapter.payload.ToolResultPayload;
import ai.kumbuka.memory.domain.Actor;
import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.IdempotencyKey;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.Patch;
import ai.kumbuka.memory.surface.AddressParser;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.AssistantVerb.Names;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.CallerActor;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import ai.kumbuka.memory.surface.Texts;
import ai.kumbuka.memory.surface.UnexpectedFailures;
import ai.kumbuka.memory.surface.VerbSurface;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The assistant surface: MCP over JSON-RPC 2.0, at {@code /mcp}.
 *
 * <h2>Why this service carries one at all</h2>
 *
 * An installation without the router reaches a service only through the
 * service's own adapter. Without one, the memory of a community installation
 * could not be used by an assistant at all, which is the opposite of a free
 * core that stands on its own. Behind the router nothing changes: the router
 * keeps calling the generic surface, and this one is simply not on its path.
 *
 * <h2>JSON-RPC by hand</h2>
 *
 * Three methods — {@code initialize}, {@code tools/list}, {@code tools/call} —
 * plus {@code ping}, over the REST stack the service already runs. A protocol
 * library would be a new dependency, with configuration of its own, for a
 * protocol whose whole server side fits in this class; and it would put a
 * second opinion about the tool schemas between the declaration and the
 * caller.
 *
 * <h2>Authenticated exactly as the generic surface is</h2>
 *
 * The same bearer token, validated by this service (ADR-0034), and the acting
 * identity derived from it by the same {@link CallerActor}. No argument of any
 * tool names an author, so none can forge one.
 *
 * <h2>A refused call answers in the envelope, a request that is no call as the protocol</h2>
 *
 * The refusal of a tool call is a tool result marked as an error, carrying
 * {@code { reason, message, data }}. A failure nobody foresaw on a tool call
 * is {@code UNEXPECTED_FAILURE} with a reference under {@code data.reference}
 * that stands in the log beside it — never a sentence of its own and never a bare 500.
 *
 * <p>A request that is not a call of a tool is not refused in the envelope
 * but answered as an error of the protocol: a body that is not JSON, a
 * request that is not JSON-RPC 2.0, a method or a tool this server does not
 * have, and {@code params} or {@code arguments} that are present and are not
 * an object. There is no call yet whose refusal the envelope could describe.
 */
@Path("/mcp")
@Authenticated
@IfBuildProperty(name = "kumbuka.local-mcp.enabled", stringValue = "true",
    enableIfMissing = true)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpAdapter {

    /**
     * DEBUG, as on the generic surface: a refusal is the surface working, and
     * a log a caller can fill by sending broken calls is a log a caller writes.
     * Only the reason travels, never an argument.
     */
    private static final Logger LOG = Logger.getLogger(McpAdapter.class);

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String JSONRPC = "2.0";
    private static final String KEY_JSONRPC = "jsonrpc";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;
    @Inject ToolTransaction transaction;

    @Inject
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String version;

    // ======================================================================
    // The protocol
    // ======================================================================

    @POST
    public Response rpc(String body) {
        Object parsed;
        try {
            parsed = json.readValue(body == null ? "" : body, Object.class);
        } catch (JsonProcessingException notJson) {
            return error(null, PARSE_ERROR, Texts.protocol().bodyNotJson());
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            return error(null, INVALID_REQUEST, Texts.protocol().oneRequest());
        }
        Map<String, Object> request = objectOf(raw).orElseGet(Map::of);
        Object id = request.get("id");
        if (!JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(id, INVALID_REQUEST, Texts.protocol().notJsonRpc());
        }
        if (!request.containsKey("id")) {
            // A notification: the protocol forbids an answer to it.
            return Response.accepted().build();
        }
        String method = String.valueOf(request.get("method"));
        return switch (method) {
            case "initialize" -> result(id, initialize());
            case "ping" -> result(id, new EmptyPayload());
            case "tools/list" -> result(id, new ToolListPayload(McpTools.declared()));
            case "tools/call" -> call(id, request);
            // The method that arrived is the caller's input and is never
            // repeated; the answer names the methods there are (DEC-0043 R19).
            default -> error(id, METHOD_NOT_FOUND, Texts.protocol().methods());
        };
    }

    /**
     * The declaration every tool, schema, description and reason is derived
     * from: the transitional exception of C5.1, beside {@code GET /api}, which
     * goes in memory's breaking step. The declaration's own path is
     * {@code /api/declaration}.
     */
    @GET
    @Path("/declaration")
    public Map<String, Object> declaration() {
        return SurfaceDeclaration.asMap();
    }

    private InitializePayload initialize() {
        return new InitializePayload(PROTOCOL_VERSION, new CapabilitiesPayload(new EmptyPayload()),
            new ServerInfoPayload("kumbuka-memory", version));
    }

    private Response call(@Nullable Object id, Map<String, Object> request) {
        Optional<Map<String, Object>> present = presentObject(request, "params");
        if (present.isEmpty()) {
            return error(id, INVALID_PARAMS, Texts.protocol().paramsObject());
        }
        Map<String, Object> params = present.get();
        Optional<AssistantVerb> verb = params.get("name") instanceof String tool
            ? AssistantVerb.byCall(tool) : Optional.empty();
        if (verb.isEmpty()) {
            // Not a refusal of a call but a call that does not exist: the
            // protocol answers it as an error of the request, with the tools
            // there are under error.data.accepts and never the name that
            // arrived (C4.3, DEC-0043 R19). A name that is absent or no text
            // names no tool either, and is answered alike: it once
            // became the word null, a tool name nobody sent.
            return error(id, INVALID_PARAMS, Texts.protocol().toolUnknown(),
                new RpcErrorDataPayload(McpTools.names()));
        }
        Optional<Map<String, Object>> args = presentObject(params, "arguments");
        if (args.isEmpty()) {
            // Present and not an object: there are no named arguments to
            // refuse one of, so this is no call yet.
            return error(id, INVALID_PARAMS, Texts.protocol().argumentsObject());
        }
        return result(id, toolResult(verb.get(), args.get()));
    }

    // ======================================================================
    // One tool call
    // ======================================================================

    private ToolResultPayload toolResult(AssistantVerb verb, Map<String, Object> args) {
        try {
            return answered(verb, args);
        } catch (RuntimeException failure) {
            return refused(AssistantRefusals.unexpected(verb,
                UnexpectedFailures.logged(verb.call(), failure)));
        }
    }

    private ToolResultPayload answered(AssistantVerb verb, Map<String, Object> args) {
        try {
            CallArguments in = new CallArguments(verb, args);
            return transaction.run(() -> content(invoke(verb, in), false));
        } catch (CallException e) {
            LOG.debugf("assistant refusal: %s", e.reason());
            return refused(AssistantRefusals.of(verb, e, seen(verb, args, Optional.empty())));
        } catch (MemoryException e) {
            if (!foreseen(e)) {
                // A reason of the generic surface arriving here — a session
                // the read contract could not be bound for, above all — is a
                // failure this surface did not foresee, and is answered as one.
                return refused(AssistantRefusals.unexpected(verb,
                    UnexpectedFailures.logged(verb.call(), e)));
            }
            LOG.debugf("assistant refusal: %s", e.reason());
            return refused(AssistantRefusals.of(verb, e, reasonOf(e),
                seen(verb, args, Optional.of(e))));
        }
    }

    /** Whether a refusal of the domain is one this surface answers as a refusal. */
    private static boolean foreseen(MemoryException e) {
        return reasonOf(e).reach() == ReasonCatalogue.Reach.BOTH;
    }

    private static ReasonCatalogue.Reason reasonOf(MemoryException e) {
        return ReasonCatalogue.of(ReasonCatalogue.wireCode(e.reason().name()));
    }

    /**
     * The verb this call names, with its arguments.
     *
     * <p>A required argument is read with {@code orElseThrow}: the check in
     * {@link CallArguments} refused the call already where it was absent, so
     * the absence cannot arrive here.
     */
    private Object invoke(AssistantVerb verb, CallArguments in) {
        Actor actor = caller.current();
        return switch (verb) {
            case CREATE -> AssistantPayloads.AssistantEntryPayload.of(verbs.create(actor,
                in.top(Names.SCOPE).orElseThrow(), in.top(Names.SELECTOR).orElseThrow(),
                new VerbSurface.NewEntry(in.field(Names.KEY).orElse(null),
                    in.field(Names.TYPE).orElse(null), in.field(Names.CONTENT).orElse(null),
                    in.field(Names.REFERENCE).orElse(null)),
                IdempotencyKey.of(in.top(Names.IDEMPOTENCY_KEY).orElse(null))).entry());
            case READ -> AssistantPayloads.AssistantEntryPayload.of(verbs.read(actor,
                AddressParser.complete(in.top(Names.ADDRESS).orElseThrow())).entry());
            case UPDATE -> AssistantPayloads.AssistantEntryPayload.of(verbs.update(actor,
                AddressParser.complete(in.top(Names.ADDRESS).orElseThrow()),
                in.top(Names.CONFLICT_TOKEN).orElse(null),
                Patch.of(in.field(Names.TYPE), in.field(Names.CONTENT),
                    in.field(Names.REFERENCE), List.of())).entry());
            case WITHDRAW -> AssistantPayloads.AssistantWithdrawnPayload.of(verbs.withdraw(actor,
                AddressParser.complete(in.top(Names.ADDRESS).orElseThrow()),
                in.top(Names.CONFLICT_TOKEN).orElse(null)));
            case QUERY -> AssistantPayloads.AssistantPagePayload.of(verbs.query(actor,
                in.top(Names.SCOPE).orElseThrow(), in.top(Names.SELECTOR).orElse(null),
                Payloads.query(narrowing(in))));
            case DIGEST -> Payloads.DigestPayload.of(verbs.digest(actor,
                in.top(Names.SCOPE).orElseThrow(), in.list(Names.TYPES).orElse(null)));
        };
    }

    /**
     * The narrowing arguments of a query, in the form the verb takes them.
     *
     * <p>Only the declared ones can be here — anything else was refused by
     * name before the verb was reached — so the verb's own refusal of an
     * unknown predicate cannot arise on this path.
     */
    private static Map<String, String> narrowing(CallArguments in) {
        Map<String, String> asked = new LinkedHashMap<>();
        in.top(Names.TYPE).ifPresent(value -> asked.put(Names.TYPE, value));
        in.top(Names.TEXT).ifPresent(value -> asked.put(Names.TEXT, value));
        in.top(Names.AFTER).ifPresent(value -> asked.put(Names.AFTER, value));
        in.top(Names.SORT).ifPresent(value -> asked.put(Names.SORT, value));
        in.top(Names.DIRECTION).ifPresent(value -> asked.put(Names.DIRECTION, value));
        in.number(Names.PAGE_SIZE).ifPresent(value -> asked.put(Names.PAGE_SIZE, value));
        return asked;
    }

    /**
     * The entry a refusal concerns, as this caller would read it — or empty
     * where there is none it may see.
     *
     * <p>Read in a transaction of its own, after the refused call's has been
     * rolled back, and through the ordinary read: whether the caller may see
     * the entry is the read contract's answer, asked again, not a guess made
     * here from the refusal.
     */
    private Optional<EntryView> seen(AssistantVerb verb, Map<String, Object> args,
                                     Optional<MemoryException> refusal) {
        if (refusal.isPresent()) {
            Optional<EntryView> carried = AssistantRefusals.carried(refusal.get());
            if (carried.isPresent() || AssistantRefusals.concernsNoEntry(refusal.get())) {
                return carried;
            }
        }
        @Nullable Object address = null;
        if (AssistantRefusals.takesAddress(verb)) {
            address = args.get(Names.ADDRESS);
        } else if (verb == AssistantVerb.CREATE) {
            address = targetOfCreate(args).orElse(null);
        }
        if (!(address instanceof String complete)) {
            return Optional.empty();
        }
        try {
            return Optional.of(verbs.read(caller.current(), AddressParser.complete(complete))
                .entry());
        } catch (MemoryException refused) {
            if (!foreseen(refused)) {
                UnexpectedFailures.logged(verb.call(), refused);
            }
            // Otherwise the read's own refusal: the caller may not see the
            // entry, or there is none.
            return Optional.empty();
        } catch (RuntimeException failure) {
            // Not an answer about visibility but a failure nobody foresaw. It
            // is logged as one; the caller still gets the refusal of the call,
            // without the state of an entry nobody could read.
            UnexpectedFailures.logged(verb.call(), failure);
            return Optional.empty();
        }
    }

    /**
     * The address a create aimed at, from the scope and the key it was sent;
     * empty where the key carries no selector and so names no address.
     */
    private static Optional<String> targetOfCreate(Map<String, Object> args) {
        if (!(args.get(Names.SCOPE) instanceof String scope)
            || !(args.get(AssistantVerb.FIELDS) instanceof Map<?, ?> fields)
            || !(fields.get(Names.KEY) instanceof String key)
            || key.indexOf(EntryAddress.SEPARATOR) < 1) {
            return Optional.empty();
        }
        return Optional.of(EntryAddress.ofKey(scope, key).canonical());
    }

    // ======================================================================
    // The wire
    // ======================================================================

    private ToolResultPayload refused(Payloads.RefusalPayload refusal) {
        return content(refusal, true);
    }

    /**
     * A tool result: the answer as structured content, and the same answer as
     * JSON text for a client that reads only text.
     */
    private ToolResultPayload content(Object payload, boolean isError) {
        String text;
        try {
            text = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return new ToolResultPayload(List.of(TextContentPayload.text(text)), payload, isError);
    }

    private static Response result(@Nullable Object id, Object payload) {
        return Response.ok(new RpcResultPayload(JSONRPC, id, payload)).build();
    }

    private static Response error(@Nullable Object id, int code, String message) {
        return Response.ok(new RpcErrorPayload(JSONRPC, id,
                new RpcErrorBodyPayload(code, message, null)))
            .build();
    }

    private static Response error(@Nullable Object id, int code, String message,
                                  RpcErrorDataPayload data) {
        return Response.ok(new RpcErrorPayload(JSONRPC, id,
                new RpcErrorBodyPayload(code, message, data)))
            .build();
    }

    /**
     * A member of a JSON object that is itself an object: absent is the empty
     * object, and present but not an object is no object at all.
     */
    private static Optional<Map<String, Object>> presentObject(Map<String, Object> in,
                                                               String name) {
        return in.containsKey(name) ? objectOf(in.get(name)) : Optional.of(Map.of());
    }

    /** The value as an object, or empty when it is not one. */
    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> objectOf(@Nullable Object value) {
        return value instanceof Map<?, ?> map
            ? Optional.of((Map<String, Object>) map) : Optional.empty();
    }
}
