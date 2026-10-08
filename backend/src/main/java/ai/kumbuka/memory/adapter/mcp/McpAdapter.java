package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.adapter.payload.AssistantPayloads;
import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.Actor;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.Patch;
import ai.kumbuka.memory.surface.AddressParser;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.AssistantVerb.Names;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.CallerActor;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import ai.kumbuka.memory.surface.UnexpectedFailures;
import ai.kumbuka.memory.surface.VerbSurface;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.Arrays;
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
 * is {@code UNEXPECTED_FAILURE} with a report reference that stands in the
 * log beside it — never a sentence of its own and never a bare 500.
 *
 * <p>A request that is not a call of a tool is not refused in the envelope
 * but answered as an error of the protocol: a body that is not JSON, a
 * request that is not JSON-RPC 2.0, a method or a tool this server does not
 * have, and {@code params} or {@code arguments} that are present and are not
 * an object. There is no call yet whose refusal the envelope could describe.
 */
@Path("/mcp")
@Authenticated
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
            return error(null, PARSE_ERROR, "The body is not JSON.");
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            return error(null, INVALID_REQUEST,
                "This server takes one JSON-RPC 2.0 request object per call.");
        }
        Map<String, Object> request = objectOf(raw);
        Object id = request.get("id");
        if (!JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(id, INVALID_REQUEST, "The request is not a JSON-RPC 2.0 request.");
        }
        if (!request.containsKey("id")) {
            // A notification: the protocol forbids an answer to it.
            return Response.accepted().build();
        }
        String method = String.valueOf(request.get("method"));
        return switch (method) {
            case "initialize" -> result(id, initialize());
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", tools()));
            case "tools/call" -> call(id, request);
            default -> error(id, METHOD_NOT_FOUND, method + " is not a method of this server. "
                + "It speaks initialize, ping, tools/list and tools/call.");
        };
    }

    /** The declaration every tool, schema, description and reason is derived from. */
    @GET
    @Path("/declaration")
    public Map<String, Object> declaration() {
        return SurfaceDeclaration.asMap();
    }

    private Map<String, Object> initialize() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("protocolVersion", PROTOCOL_VERSION);
        answer.put("capabilities", Map.of("tools", Map.of()));
        answer.put("serverInfo", Map.of("name", "kumbuka-memory", "version", version));
        return answer;
    }

    private static List<Map<String, Object>> tools() {
        return McpTools.declared().stream()
            .map(tool -> {
                Map<String, Object> listed = new LinkedHashMap<>();
                listed.put("name", tool.name());
                listed.put("description", tool.description());
                listed.put("inputSchema", tool.inputSchema());
                return listed;
            })
            .toList();
    }

    private Response call(Object id, Map<String, Object> request) {
        Map<String, Object> params = presentObject(request, "params");
        if (params == null) {
            return error(id, INVALID_PARAMS, "The params of tools/call are an object naming the "
                + "tool and its arguments.");
        }
        String tool = String.valueOf(params.get("name"));
        Optional<AssistantVerb> verb = AssistantVerb.byCall(tool);
        if (verb.isEmpty()) {
            // Not a refusal of a call but a call that does not exist: the
            // protocol answers it as an error of the request, and names the
            // tools there are.
            return error(id, INVALID_PARAMS, tool + " is not a tool of this server. Its tools "
                + "are: " + String.join(", ",
                    Arrays.stream(AssistantVerb.values()).map(AssistantVerb::call).toList())
                + ".");
        }
        Map<String, Object> args = presentObject(params, "arguments");
        if (args == null) {
            // Present and not an object: there are no named arguments to
            // refuse one of, so this is no call yet.
            return error(id, INVALID_PARAMS, "The arguments of " + tool + " are an object of "
                + "named arguments.");
        }
        return result(id, toolResult(verb.get(), args));
    }

    // ======================================================================
    // One tool call
    // ======================================================================

    private Map<String, Object> toolResult(AssistantVerb verb, Map<String, Object> args) {
        try {
            return answered(verb, args);
        } catch (RuntimeException failure) {
            return refused(Refusals.unexpected(verb, args,
                UnexpectedFailures.logged(verb.call(), failure)));
        }
    }

    private Map<String, Object> answered(AssistantVerb verb, Map<String, Object> args) {
        try {
            CallArguments in = new CallArguments(verb, args);
            return transaction.run(() -> content(invoke(verb, in), false));
        } catch (CallException e) {
            LOG.debugf("assistant refusal: %s", e.reason());
            return refused(Refusals.of(verb, e, seen(verb, args, null)));
        } catch (MemoryException e) {
            if (!foreseen(e)) {
                // A reason of the generic surface arriving here — a session
                // the read contract could not be bound for, above all — is a
                // failure this surface did not foresee, and is answered as one.
                return refused(Refusals.unexpected(verb, args,
                    UnexpectedFailures.logged(verb.call(), e)));
            }
            LOG.debugf("assistant refusal: %s", e.reason());
            return refused(Refusals.of(verb, e, reasonOf(e), args, seen(verb, args, e)));
        }
    }

    /** Whether a refusal of the domain is one this surface answers as a refusal. */
    private static boolean foreseen(MemoryException e) {
        return reasonOf(e).reach() == ReasonCatalogue.Reach.BOTH;
    }

    private static ReasonCatalogue.Reason reasonOf(MemoryException e) {
        return ReasonCatalogue.of(ReasonCatalogue.wireCode(e.reason().name()));
    }

    private Object invoke(AssistantVerb verb, CallArguments in) {
        Actor actor = caller.current();
        return switch (verb) {
            case CREATE -> AssistantPayloads.EntryAnswer.of(verbs.create(actor,
                in.top(Names.SCOPE), in.top(Names.SELECTOR),
                new VerbSurface.NewEntry(in.field(Names.KEY), in.field(Names.TYPE),
                    in.field(Names.CONTENT), in.field(Names.REFERENCE))).entry());
            case READ -> AssistantPayloads.EntryAnswer.of(verbs.read(actor,
                AddressParser.complete(in.top(Names.ADDRESS))).entry());
            case UPDATE -> AssistantPayloads.EntryAnswer.of(verbs.update(actor,
                AddressParser.complete(in.top(Names.ADDRESS)), in.top(Names.CONFLICT_TOKEN),
                new Patch(Optional.ofNullable(in.field(Names.TYPE)),
                    Optional.ofNullable(in.field(Names.CONTENT)),
                    Optional.ofNullable(in.field(Names.REFERENCE)))).entry());
            case WITHDRAW -> AssistantPayloads.WithdrawalAnswer.of(verbs.withdraw(actor,
                AddressParser.complete(in.top(Names.ADDRESS)), in.top(Names.CONFLICT_TOKEN)));
            case QUERY -> AssistantPayloads.ListingAnswer.of(verbs.query(actor,
                in.top(Names.SCOPE), in.top(Names.SELECTOR), narrowing(in)));
            case DIGEST -> Payloads.DigestResponse.of(verbs.digest(actor,
                in.top(Names.SCOPE), in.list(Names.TYPES)));
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
        putIfPresent(asked, Names.TYPE, in.top(Names.TYPE));
        putIfPresent(asked, Names.TEXT, in.top(Names.TEXT));
        putIfPresent(asked, Names.AFTER, in.top(Names.AFTER));
        putIfPresent(asked, Names.PAGE_SIZE, in.number(Names.PAGE_SIZE));
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
                                     MemoryException e) {
        if (e != null) {
            if (e.data().get(AssistantVerb.FIELDS) instanceof EntryView current) {
                return Optional.of(current);
            }
            if (ReasonCatalogue.NOT_FOUND.equals(ReasonCatalogue.wireCode(e.reason().name()))
                || e.reason() == MemoryException.Reason.ACTOR_UNKNOWN) {
                return Optional.empty();
            }
        }
        Object address = null;
        if (Refusals.takesAddress(verb)) {
            address = args.get(Names.ADDRESS);
        } else if (e != null) {
            address = e.data().get(MemoryException.ADDRESS);
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

    // ======================================================================
    // The wire
    // ======================================================================

    private Map<String, Object> refused(Payloads.Refusal refusal) {
        return content(refusal, true);
    }

    /**
     * A tool result: the answer as structured content, and the same answer as
     * JSON text for a client that reads only text.
     */
    private Map<String, Object> content(Object payload, boolean isError) {
        String text;
        try {
            text = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private static Response result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put("id", id);
        envelope.put("result", payload);
        return Response.ok(envelope).build();
    }

    private static Response error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put("id", id);
        envelope.put("error", Map.of("code", code, "message", message));
        return Response.ok(envelope).build();
    }

    /**
     * A member of a JSON object that is itself an object: absent is the empty
     * object, and present but not an object is null.
     */
    private static Map<String, Object> presentObject(Map<String, Object> in, String name) {
        return in.containsKey(name) ? objectOf(in.get(name)) : Map.of();
    }

    /** The value as an object, or null when it is not one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static void putIfPresent(Map<String, String> into, String name, String value) {
        if (value != null) {
            into.put(name, value);
        }
    }
}
