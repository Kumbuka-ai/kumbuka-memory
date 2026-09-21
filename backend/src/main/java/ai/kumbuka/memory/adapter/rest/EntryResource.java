package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.surface.AddressParser;
import ai.kumbuka.memory.surface.CallerActor;
import ai.kumbuka.memory.surface.SurfaceException;
import ai.kumbuka.memory.surface.VerbSurface;
import ai.kumbuka.memory.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The six verbs, as HTTP.
 *
 * <h2>This is a front door, not an inner leg</h2>
 *
 * The community edition has no facade and addresses the services directly, so
 * this surface is the published contract of a copyleft-licensed product from
 * its first day. That is the reason for the strictness about form and closure:
 * a route added here casually is a route somebody depends on.
 *
 * <h2>The path is the address space</h2>
 *
 * The scheme is not part of the path — it is the routing decision one layer
 * out — so what this service sees is {@code {scope}/{selector}/{id}}. Three
 * depths, and each one offers exactly what the verb set has at it:
 *
 * <pre>
 *   GET    /api/{scope}                     query, over every selector
 *   GET    /api/{uuid}                      read, by technical address
 *   POST   /api/{scope}:digest              digest
 *   GET    /api/{scope}/{selector}          query, inside one selector
 *   POST   /api/{scope}/{selector}          create
 *   GET    /api/{scope}/{selector}/{id}     read
 *   PATCH  /api/{scope}/{selector}/{id}     update
 *   POST   /api/{scope}/{selector}/{id}:withdraw   withdraw
 * </pre>
 *
 * <h2>Expression, not offering</h2>
 *
 * REST conventions are followed in how a verb is expressed and not in what is
 * offered: what the verb set lacks is not offered even where the convention
 * expects it. There is no DELETE on an entry — a withdrawal is an act of this
 * service with an edition-dependent effect, not the removal of a resource —
 * and a verb addressed at the wrong depth answers 405 rather than being routed
 * somewhere plausible.
 *
 * <p>The acts themselves are in {@link VerbSurface}. This class holds their
 * HTTP expression and nothing else, so an addition here would be an addition
 * with no act behind it.
 */
@Path("/api")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class EntryResource {

    /** What a scope URI offers, for the {@code Allow} of a 405. */
    private static final String SCOPE_ALLOW = "GET, POST";

    /** What a collection URI offers. */
    private static final String COLLECTION_ALLOW = "GET, POST";

    /** What an item URI offers. */
    private static final String ITEM_ALLOW = "GET, PATCH, POST";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    // ======================================================================
    // Scope depth
    // ======================================================================

    /**
     * GET at scope depth: {@code query} over every selector, or {@code read}
     * by technical address.
     *
     * <p>One route and two readings, because the address space has two things
     * that stand in this position: a scope name, and the uuid of the technical
     * address {@code memory://<uuid>}, which carries no scope by definition.
     * They are told apart by form — a uuid parses as one and a scope slug of
     * the platform's shape does not — and the split is written here rather
     * than hidden behind two registrations that would silently overlap.
     *
     * <p>A technical read with query parameters is refused rather than served
     * with them ignored: it addresses one entry, so there is nothing for a
     * filter to narrow, and a parameter accepted and discarded is the failure
     * the predicate vocabulary exists to prevent.
     */
    @GET
    @Path("{segment}")
    public Response scopeGet(@PathParam("segment") String segment, @Context UriInfo uri) {
        Optional<UUID> technical = AddressParser.technicalAddress(segment);
        if (technical.isPresent()) {
            refuseParametersOnAnItem(uri);
            return answer(verbs.readTechnical(caller.current(), technical.get()));
        }
        return listing(verbs.query(caller.current(), segment, null, parametersOf(uri)));
    }

    /**
     * POST at scope depth: the compound reads, in colon notation.
     *
     * <p>A segment with no colon is a plain scope address, and POST is not
     * something a scope offers: an entry is created under a selector, so the
     * address a create is posted to is one segment longer. 405 with
     * {@code Allow}, and not 404 — the scope may well exist.
     */
    @POST
    @Path("{segment}")
    public Response scopePost(@PathParam("segment") String segment, String body) {
        CustomMethod.Split at = verbAt(segment, CustomMethod.Depth.SCOPE, SCOPE_ALLOW,
            "a scope carries the compound reads of this service in colon notation — "
                + "'POST /api/<scope>:digest'. An entry is laid down under a selector, so "
                + "a create is posted one segment further in.");

        return switch (at.method()) {
            case DIGEST -> Response.ok(Payloads.DigestResponse.of(
                verbs.digest(caller.current(), at.address(),
                    Payloads.digestTypes(read(body, Payloads.DigestRequest.class))))).build();
            case WITHDRAW -> throw new IllegalStateException(
                "withdraw acts at item depth and cannot arrive here");
        };
    }

    // ======================================================================
    // Collection depth
    // ======================================================================

    /**
     * GET at collection depth: {@code query}, inside one selector.
     *
     * <p>The query parameters are passed through raw and are NOT declared as
     * {@code @QueryParam}s. Declaring them here would put the list of
     * filterable names in the adapter, which is a second place for it to be
     * decided; worse, an undeclared parameter would then be silently dropped
     * by the framework, and a silently dropped filter answers with the full
     * set and looks exactly like a correct narrow one. The surface refuses
     * what it does not carry, and refusing is only possible if it gets to see
     * it.
     */
    @GET
    @Path("{scope}/{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String selector,
                                  @Context UriInfo uri) {
        return listing(verbs.query(caller.current(), scope, selector, parametersOf(uri)));
    }

    /**
     * POST at collection depth: {@code create}.
     *
     * <p>The body arrives as text and is deserialised only once the shape is
     * known. That order is what makes the 405 reachable: a typed body
     * parameter is deserialised before the method is entered, so a verb
     * wrongly addressed at a collection would answer 415 about its content
     * type instead of 405 about its address — a refusal about the wrong thing
     * entirely.
     */
    @POST
    @Path("{scope}/{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   String body) {
        CustomMethod.split(segment, CustomMethod.Depth.COLLECTION).ifPresent(at -> {
            throw new SurfaceException(SurfaceException.Reason.VERB_NOT_CARRIED,
                "'" + at.verb() + "' is not a verb of this scheme at a selector. What a "
                    + "selector carries is 'create', which is a plain POST onto it, and "
                    + "'query', which is a GET. 'digest' acts on a whole scope and "
                    + "'withdraw' on one entry.",
                COLLECTION_ALLOW);
        });

        VerbSurface.Answer answer = verbs.create(caller.current(), scope, segment,
            Payloads.newEntry(read(body, Payloads.CreateRequest.class)));

        return tagged(Response
            .created(UriBuilder.fromResource(EntryResource.class)
                .path("{scope}/{selector}/{id}")
                .build(scope, answer.entry().address().selector(),
                    answer.entry().address().id()))
            .entity(Payloads.EntryResponse.of(answer)), answer);
    }

    // ======================================================================
    // Item depth
    // ======================================================================

    /** GET at item depth: {@code read}. */
    @GET
    @Path("{scope}/{selector}/{id}")
    public Response read(@PathParam("scope") String scope,
                         @PathParam("selector") String selector,
                         @PathParam("id") String id,
                         @Context UriInfo uri) {
        refuseParametersOnAnItem(uri);
        return answer(verbs.read(caller.current(),
            new AddressParser.Parts(scope, selector, id)));
    }

    /**
     * PATCH at item depth: {@code update}.
     *
     * <p>PATCH and not PUT: the body names the fields that change and says
     * nothing about the others, which is what a partial write is. The conflict
     * token travels as {@code If-Match}, where HTTP already puts the
     * precondition of a write — a token in the body would be a value of the
     * entry, which it is not.
     */
    @PATCH
    @Path("{scope}/{selector}/{id}")
    public Response update(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @HeaderParam("If-Match") String ifMatch,
                           String body) {
        VerbSurface.Answer answer = verbs.update(caller.current(),
            new AddressParser.Parts(scope, selector, id),
            unquoted(ifMatch),
            Payloads.patch(read(body, Payloads.UpdateRequest.class)));
        return answer(answer);
    }

    /**
     * POST at item depth: the transitions, in colon notation.
     *
     * <p>A segment with no colon is a plain item address, and POST is not
     * something an item offers — 405 with {@code Allow}, for the same reason
     * the collection refuses a compound read: what the verb set lacks is not
     * offered even where the convention expects it.
     */
    @POST
    @Path("{scope}/{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             @HeaderParam("If-Match") String ifMatch) {
        CustomMethod.Split at = verbAt(segment, CustomMethod.Depth.ITEM, ITEM_ALLOW,
            "an entry carries one verb in colon notation — "
                + "'POST /api/<scope>/<selector>/<id>:withdraw'. Reading it is a GET and "
                + "changing it is a PATCH; there is no DELETE, because a withdrawal is an "
                + "act of this service whose effect the edition decides and not the "
                + "removal of a resource.");

        return switch (at.method()) {
            case WITHDRAW -> Response.ok(Payloads.WithdrawnResponse.of(
                verbs.withdraw(caller.current(),
                    new AddressParser.Parts(scope, selector, at.address()),
                    unquoted(ifMatch)))).build();
            case DIGEST -> throw new IllegalStateException(
                "digest acts at scope depth and cannot arrive here");
        };
    }

    // ======================================================================
    // Dressing a result in HTTP
    // ======================================================================

    private static Response answer(VerbSurface.Answer answer) {
        return tagged(Response.ok(Payloads.EntryResponse.of(answer)), answer);
    }

    /**
     * A listing, with no entity tag.
     *
     * <p>Deliberately untagged: the conflict token is a per-entry value and a
     * listing has no single one. A tag over the set would be a token a caller
     * could send back on a field write, which is a token about the wrong
     * thing. Every entry of the listing carries its own.
     */
    private static Response listing(ai.kumbuka.memory.domain.Listing found) {
        return Response.ok(Payloads.ListingResponse.of(found)).build();
    }

    private static Response tagged(Response.ResponseBuilder response,
                                   VerbSurface.Answer answer) {
        return response.tag(new EntityTag(answer.entry().conflictToken())).build();
    }

    // ======================================================================
    // Reading the call
    // ======================================================================

    /**
     * The verb a segment names at a depth, or a 405 that says what is carried.
     *
     * <p>Both failures are 405 and neither is 404. A segment with no colon
     * resolved as an address and the method is what the address does not
     * offer; a colon naming an unknown verb resolved too, and what did not
     * exist is the verb. A 404 in either case would send the caller looking
     * for the object.
     */
    private static CustomMethod.Split verbAt(String segment, CustomMethod.Depth depth,
                                             String allow, String guidance) {
        return CustomMethod.split(segment, depth)
            .filter(CustomMethod.Split::isKnown)
            .orElseThrow(() -> new SurfaceException(
                segment.indexOf(CustomMethod.SEPARATOR) < 0
                    ? SurfaceException.Reason.ADDRESS_TRUNCATED
                    : SurfaceException.Reason.VERB_NOT_CARRIED,
                "'" + segment + "' names no verb this address carries. " + guidance,
                allow));
    }

    /**
     * The query parameters, flattened to one value per name.
     *
     * <p>The last value of a repeated parameter wins, and the choice is
     * arbitrary — what matters is that it is made here and not by whichever
     * part of the framework happened to read it first. A repeated filter is a
     * caller mistake either way, and the surface refuses the names it does not
     * carry rather than the shapes it does not expect.
     */
    private static Map<String, String> parametersOf(UriInfo uri) {
        Map<String, String> flattened = new LinkedHashMap<>();
        MultivaluedMap<String, String> raw = uri.getQueryParameters();
        raw.forEach((name, values) -> flattened.put(name,
            values.isEmpty() ? "" : values.get(values.size() - 1)));
        return flattened;
    }

    private static void refuseParametersOnAnItem(UriInfo uri) {
        List<String> given = List.copyOf(uri.getQueryParameters().keySet());
        if (!given.isEmpty()) {
            throw MemoryException.offending(MemoryException.Reason.PREDICATE_UNKNOWN,
                "this address names one entry, so there is nothing for a filter to "
                    + "narrow. The parameters are refused rather than ignored: one "
                    + "accepted and discarded would let a caller believe it had asked "
                    + "something. Filters belong on a query, which is addressed at a "
                    + "scope or at a selector.",
                given);
        }
    }

    /**
     * The conflict token out of an {@code If-Match} header.
     *
     * <p>An entity tag travels quoted, and a caller that read the token out of
     * an answer body sends it unquoted. Both are accepted and the quotes are
     * stripped, because refusing one of them would be refusing a caller for
     * following the other half of the same contract.
     */
    private static String unquoted(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String trimmed = ifMatch.trim();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Deserialises the request body, after the shape has been decided.
     *
     * <p>An absent body is null rather than an error: some verbs take one and
     * some do not, and which is which is the verb's business rather than the
     * transport's. The verb refuses a missing body where it needs one, with a
     * message that says what was missing.
     */
    private <T> T read(String body, Class<T> shape) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readValue(body, shape);
        } catch (JsonProcessingException e) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the request body is not the JSON this verb takes: " + e.getOriginalMessage());
        }
    }
}
