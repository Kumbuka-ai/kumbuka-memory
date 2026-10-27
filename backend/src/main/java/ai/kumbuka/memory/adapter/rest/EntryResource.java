/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.AssistantPayloads;
import ai.kumbuka.memory.adapter.payload.AssistantRefusals;
import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.surface.AddressParser;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallerActor;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import ai.kumbuka.memory.surface.SurfaceException;
import ai.kumbuka.memory.surface.VerbSurface;
import ai.kumbuka.memory.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.Nullable;

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
 *   GET    /api                             the service's declaration
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
 *
 * <h2>The surface a listing answers in</h2>
 *
 * A listing names calls — {@code next} on every entry, {@code attempted} on a
 * refusal — and a call has a name per surface. Without the
 * {@code Kumbuka-Surface} header that is this one. With
 * {@code Kumbuka-Surface: assistant} a hop that publishes these calls as
 * assistant tools and reaches this service over REST says so, and the
 * listing answers in the assistant surface's projection: its {@code next}
 * names {@code memory_read}, its refusals are worded from the reason
 * catalogue as the service's own MCP adapter words them. Any other value is
 * refused and nothing is read. The header is not an argument of the call, it
 * grants nothing, and for now only {@code query} reads it.
 *
 * <h2>Why the class carries a transaction</h2>
 *
 * {@code @TenantBound} binds the tenant inside a transaction and quietly does
 * nothing outside one. Every method here calls exactly one verb of the
 * surface, which joins this transaction rather than opening its own, so the
 * binding the annotation promises now happens where it is written.
 */
@Path("/api")
@Authenticated
@TenantBound
@Transactional
@Produces(MediaType.APPLICATION_JSON)
public class EntryResource {

    /** What a scope URI offers, for the {@code Allow} of a 405. */
    private static final String SCOPE_ALLOW = "GET, POST";

    /** What a collection URI offers. */
    private static final String COLLECTION_ALLOW = "GET, POST";

    /** What an item URI offers. */
    private static final String ITEM_ALLOW = "GET, PATCH, POST";

    /**
     * Names the surface the caller came through, where that is not this one.
     * It decides nothing else: who calls, and what that caller may do, come
     * from the verified identity as always.
     */
    static final String SURFACE = "Kumbuka-Surface";

    /** The one value {@link #SURFACE} takes: the assistant surface. */
    static final String ASSISTANT = "assistant";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;
    @Inject RestCall call;

    // ======================================================================
    // Scheme depth
    // ======================================================================

    /**
     * GET at the scheme's own address: the service's declaration (ADR-0039).
     *
     * <p>Every verb with its identity, effect class, arguments, answer,
     * refusals and texts, and the version they carry together -- the statement
     * the router builds its surface from. A statement about the scheme, so it
     * stands at the scheme's address, {@code memory://}, which is {@code /api};
     * a segment under it would read as a scope, and {@code declaration} is a
     * well-formed scope name. It is the declaration {@code /mcp/declaration}
     * serves: one declaration, two readers.
     *
     * <p>Authenticated as every other route of this surface. Whether the router
     * may read it with an identity of its own rather than a caller's is a
     * decision about a security gate and is not taken here.
     */
    @GET
    public Map<String, Object> declaration() {
        return SurfaceDeclaration.asMap();
    }

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
     * the refusal of unknown arguments exists to prevent.
     */
    @GET
    @Path("{segment}")
    public Response scopeGet(@PathParam("segment") String segment, @Context UriInfo uri,
                             @Context HttpHeaders http) {
        Optional<UUID> technical = AddressParser.technicalAddress(segment);
        if (technical.isPresent()) {
            call.is(AssistantVerb.READ);
            refuseParametersOnAnItem(uri);
            return answer(verbs.readTechnical(caller.current(), technical.get()));
        }
        return query(segment, null, uri, http);
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
        // A scope carries the compound reads in colon notation,
        // 'POST /api/<scope>:digest'. An entry is laid down under a selector,
        // so a create is posted one segment further in.
        CustomMethod.Split at = verbAt(segment, CustomMethod.Depth.SCOPE, SCOPE_ALLOW);

        return switch (at.known()) {
            case DIGEST -> digest(at, body);
            case WITHDRAW -> throw new IllegalStateException(
                "withdraw acts at item depth and cannot arrive here");
        };
    }

    private Response digest(CustomMethod.Split at, String body) {
        call.is(AssistantVerb.DIGEST);
        return Response.ok(Payloads.DigestResponse.of(
                verbs.digest(caller.current(), at.address(),
                    Payloads.digestTypes(read(body, Payloads.DigestRequest.class).orElse(null))
                        .orElse(null)))).build();
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
                                  @Context UriInfo uri, @Context HttpHeaders http) {
        return query(scope, selector, uri, http);
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
            throw new SurfaceException(SurfaceException.Reason.VERB_NOT_CARRIED, COLLECTION_ALLOW);
        });

        call.is(AssistantVerb.CREATE);
        VerbSurface.Answer answer = verbs.create(caller.current(), scope, segment,
            Payloads.newEntry(read(body, Payloads.CreateRequest.class).orElse(null)));

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
        call.is(AssistantVerb.READ);
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
                           @HeaderParam("If-Match") @Nullable String ifMatch,
                           String body) {
        call.is(AssistantVerb.UPDATE);
        VerbSurface.Answer answer = verbs.update(caller.current(),
            new AddressParser.Parts(scope, selector, id),
            unquoted(ifMatch).orElse(null),
            Payloads.patch(read(body, Payloads.UpdateRequest.class).orElse(null)));
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
                             @HeaderParam("If-Match") @Nullable String ifMatch) {
        // An entry carries one verb in colon notation,
        // 'POST /api/<scope>/<selector>/<id>:withdraw'. Reading it is a GET and
        // changing it is a PATCH; there is no DELETE, because a withdrawal is an
        // act of this service whose effect the edition decides and not the
        // removal of a resource.
        CustomMethod.Split at = verbAt(segment, CustomMethod.Depth.ITEM, ITEM_ALLOW);

        return switch (at.known()) {
            case WITHDRAW -> withdraw(scope, selector, at, ifMatch);
            case DIGEST -> throw new IllegalStateException(
                "digest acts at scope depth and cannot arrive here");
        };
    }

    private Response withdraw(String scope, String selector, CustomMethod.Split at,
                              @Nullable String ifMatch) {
        call.is(AssistantVerb.WITHDRAW);
        return Response.ok(Payloads.WithdrawnResponse.of(
                verbs.withdraw(caller.current(),
                    new AddressParser.Parts(scope, selector, at.address()),
                    unquoted(ifMatch).orElse(null)))).build();
    }

    // ======================================================================
    // Dressing a result in HTTP
    // ======================================================================

    private static Response answer(VerbSurface.Answer answer) {
        return tagged(Response.ok(Payloads.EntryResponse.of(answer)), answer);
    }

    /**
     * {@code query}, answered on the surface the caller names.
     *
     * <p>The answer is deliberately untagged: the conflict token is a
     * per-entry value and a listing has no single one. A tag over the set
     * would be a token a caller could send back on a field write, which is a
     * token about the wrong thing. Every entry of the listing carries its own.
     */
    private Response query(String scope, @Nullable String selector, UriInfo uri,
                           HttpHeaders http) {
        call.is(AssistantVerb.QUERY);
        Map<String, String> asked = parametersOf(uri);
        if (!onAssistantSurface(http.getRequestHeader(SURFACE))) {
            return Response.ok(Payloads.PageResponse.of(
                verbs.query(caller.current(), scope, selector, asked))).build();
        }
        try {
            return Response.ok(AssistantPayloads.PageAnswer.of(
                verbs.query(caller.current(), scope, selector, asked))).build();
        } catch (MemoryException refused) {
            ReasonCatalogue.Reason reason =
                ReasonCatalogue.of(ReasonCatalogue.wireCode(refused.reason().name()));
            if (reason.reach() != ReasonCatalogue.Reach.BOTH) {
                // A reason the assistant surface does not answer is a failure
                // it did not foresee; the generic form at least says what it
                // was rather than wording it in a vocabulary it has no entry in.
                throw refused;
            }
            throw new AssistantRefused(refused, AssistantRefusals.of(AssistantVerb.QUERY,
                refused, reason, Optional.empty()));
        }
    }

    /**
     * Whether the caller names the assistant surface: once, with the one
     * value it takes. An empty value or a second one is a value the header
     * cannot take, not an absence, and is refused before the scope is looked
     * at, so a scope this caller cannot see answers the same.
     *
     * <p>Without {@code data}: the header is not an argument of the call, so
     * there is no argument name for {@code offenders} to carry, and the value
     * sent is never repeated (DEC-0042). The dispatch and the worklist service
     * refuse it in the same form.
     */
    private static boolean onAssistantSurface(List<String> named) {
        if (named == null || named.isEmpty()) {
            return false;
        }
        if (!List.of(ASSISTANT).equals(named)) {
            MemoryException refused =
                new MemoryException(MemoryException.Reason.ARGUMENT_INVALID);
            throw new AssistantRefused(refused, AssistantRefusals.headerRefused());
        }
        return true;
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
                                             String allow) {
        return CustomMethod.split(segment, depth)
            .filter(CustomMethod.Split::isKnown)
            .orElseThrow(() -> new SurfaceException(
                segment.indexOf(CustomMethod.SEPARATOR) < 0
                    ? SurfaceException.Reason.ADDRESS_TRUNCATED
                    : SurfaceException.Reason.VERB_NOT_CARRIED, allow));
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

    /**
     * Refuses every query parameter on an address that names one entry.
     *
     * <p>{@code ARGUMENT_UNKNOWN} with 400, as for any argument a verb does not
     * declare (DEC-0042): {@code read} declares none, and that is decidable
     * from the verb alone, without a scope. {@code accepts} lists the names
     * the read takes as parameters, which are none; the names sent are the
     * caller's input and are repeated nowhere (DEC-0043 R19).
     */
    private static void refuseParametersOnAnItem(UriInfo uri) {
        if (!uri.getQueryParameters().isEmpty()) {
            throw MemoryException.undeclared(List.of());
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
    private static Optional<String> unquoted(@Nullable String ifMatch) {
        if (ifMatch == null) {
            return Optional.empty();
        }
        String trimmed = ifMatch.trim();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return Optional.of(trimmed.substring(1, trimmed.length() - 1));
        }
        return Optional.of(trimmed);
    }

    /**
     * Deserialises the request body, after the shape has been decided.
     *
     * <p>An absent body is empty rather than an error: some verbs take one and
     * some do not, and which is which is the verb's business rather than the
     * transport's. The verb refuses a missing body where it needs one, with a
     * message that says what was missing.
     */
    private <T> Optional<T> read(@Nullable String body, Class<T> shape) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(body, shape));
        } catch (JsonProcessingException e) {
            // The parser's message quotes the body it rejected, so it goes
            // nowhere: neither into the refusal nor into this exception.
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED);
        }
    }
}
