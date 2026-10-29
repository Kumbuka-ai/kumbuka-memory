/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.AssistantRefusals;
import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.surface.AddressParser;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.CallerActor;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceException;
import ai.kumbuka.memory.surface.UnexpectedFailures;
import ai.kumbuka.memory.surface.VerbSurface;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Every refusal of the REST surface, in its envelope and with its status: the
 * outermost edge, and with {@code McpAdapter} the only place a refusal is
 * caught (C8.2).
 *
 * <p>One mapper per type, nested here: the transport's refusals, the
 * refusals of a call's arguments, the domain's, and everything else, which is
 * ours and answered as {@code UNEXPECTED_FAILURE} with a reference (C8.3).
 * Each refusal is answered on the surface the request names (C14.4): in the
 * generic vocabulary, or with {@code Kumbuka-Surface: assistant} as the
 * service's own MCP adapter answers it. The status is the same on both.
 *
 * <h2>The status is not a judgement made here</h2>
 *
 * For a transport refusal the status travels on the reason itself. For a
 * domain refusal it is decided by the table below — one entry per reason, no
 * default branch that swallows a new one. A {@code switch} over an enum with
 * no default is what makes an added reason a compile error rather than a
 * silent 500, and this is exactly where a silent 500 would be indefensible:
 * the reasons are the published contract of the surface.
 *
 * <h2>The two statuses that look wrong and are not</h2>
 *
 * {@code SCOPE_UNRESOLVED} answers <strong>404</strong> rather than 403. The
 * directory answers for the bound subject only and existence in its answer IS
 * the permission, so a 403 would confirm that a scope exists to a caller who
 * may not see it — turning the error path into a scope enumerator.
 *
 * <p>{@code SCOPE_LOCKED} answers <strong>409</strong> and {@code
 * SCOPE_READ_ONLY} <strong>403</strong>, and the split is the same one the
 * sibling services make. A lock is the scope's own state: the same caller with
 * the same token gets through once it is lifted, which is what 409 means. A
 * missing write right is about this caller and no state change lifts it, which
 * is what 403 means. A caller told the wrong one of those goes to the wrong
 * person.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    /**
     * Every refusal at DEBUG, by its code (C13.3).
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed
     * address arrives on every client typo, and a refusal log at WARN would
     * make this service's operational log writable by whoever calls it —
     * flood the surface with broken addresses and you fill the log. What
     * belongs at WARN is a statement about the deployment, and those live
     * where they are decided: the directory warns on a session it could not
     * bind. The one refusal at ERROR is {@code UNEXPECTED_FAILURE}, written by
     * {@link UnexpectedFailures}.
     *
     * <p>The actor is absent, as everywhere in this service. A second
     * aggregatable record of who was refused what is how not-collecting
     * behavioural data gets circumvented without anybody deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /** The one DEBUG line of a refusal: its code and nothing else. */
    private static final String REFUSED = "refusal: %s";

    @Inject RestCall call;

    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf(REFUSED, e.reason().name());
        // The surface header is refused before the route is matched, and so
        // names no call; every other transport refusal names the call where
        // the route had resolved one.
        Optional<String> attempted = e.reason() == SurfaceException.Reason.HEADER_INVALID
            ? Optional.empty() : call.verb().map(call::named);
        return answer(e.reason().status(), Payloads.RefusalPayload.of(e, attempted,
            e.carried().stream().map(call::named).toList()), e.allow().orElse(null));
    }

    /** One refusal as an HTTP response: its status, its envelope, and for a 405 the methods. */
    static Response answer(int status, Payloads.RefusalPayload refusal, @Nullable String allow) {
        Response.ResponseBuilder response = Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(refusal);
        // A 405 without Allow refuses without saying what would have worked,
        // which is the one thing the status is required to carry.
        if (allow != null) {
            response.header(HttpHeaders.ALLOW, allow);
        }
        return response.build();
    }

    /**
     * The verb the refused request called. Every resource method states it
     * before anything it calls can refuse, so its absence is a defect of the
     * resource and not a condition of the caller's.
     */
    static AssistantVerb verbOf(RestCall call) {
        return call.verb().orElseThrow(() -> new IllegalStateException(
            "a refusal arrived before the resource method stated its verb"));
    }

    /**
     * The entry a refusal concerns, as this caller would read it — or empty
     * where there is none it may see — for the assistant surface's
     * {@code state} and {@code next}.
     *
     * <p>Read in a transaction of its own, after the refused call's has been
     * rolled back, and through the ordinary read: whether the caller may see
     * the entry is the read contract's answer, asked again. Called only on the
     * assistant surface: the generic one carries neither member.
     */
    static Optional<EntryView> seen(RestCall call, VerbSurface verbs, CallerActor caller,
                                    Optional<MemoryException> refusal) {
        if (refusal.isPresent()) {
            Optional<EntryView> carried = AssistantRefusals.carried(refusal.get());
            if (carried.isPresent() || AssistantRefusals.concernsNoEntry(refusal.get())) {
                return carried;
            }
        }
        Optional<String> target = call.target();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(verbs.read(caller.current(), AddressParser.complete(target.get()))
                .entry());
        } catch (MemoryException | CallException unreadable) {
            // The read's own refusal: the caller may not see the entry, or
            // there is none.
            return Optional.empty();
        } catch (RuntimeException failure) {
            // Not an answer about visibility but a failure nobody foresaw. It
            // is logged as one; the caller still gets the refusal of the call,
            // without the state of an entry nobody could read.
            UnexpectedFailures.logged(call.verb().map(AssistantVerb::call).orElse(null), failure);
            return Optional.empty();
        }
    }

    /**
     * The refusals of a call's arguments: one the call does not declare, one
     * it needs and did not get, one not of the declared kind. 400, as the
     * assistant surface's argument refusals are.
     */
    @Provider
    public static class Arguments implements ExceptionMapper<CallException> {

        @Inject RestCall call;
        @Inject VerbSurface verbs;
        @Inject CallerActor caller;

        @Override
        public Response toResponse(CallException e) {
            LOG.debugf(REFUSED, e.reason());
            AssistantVerb verb = verbOf(call);
            return answer(400, call.assistant()
                ? AssistantRefusals.of(verb, e, seen(call, verbs, caller, Optional.empty()))
                : Payloads.RefusalPayload.of(verb, e, call.named(verb)), null);
        }
    }

    /** The domain's refusals, on the same envelope and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<MemoryException> {

        @Inject RestCall call;
        @Inject VerbSurface verbs;
        @Inject CallerActor caller;

        @Override
        public Response toResponse(MemoryException e) {
            LOG.debugf(REFUSED, e.reason().name());
            AssistantVerb verb = verbOf(call);
            int status = statusOf(e.reason());
            ReasonCatalogue.Reason reason =
                ReasonCatalogue.of(ReasonCatalogue.wireCode(e.reason().name()));
            // A reason the assistant surface does not answer is worded in the
            // generic form, naming the call as the caller's surface names it:
            // that form at least says what it was, rather than wording it in a
            // vocabulary it has no entry in.
            if (!call.assistant() || reason.reach() != ReasonCatalogue.Reach.BOTH) {
                return answer(status, Payloads.RefusalPayload.of(verb, e, call.named(verb)),
                    null);
            }
            return answer(status, AssistantRefusals.of(verb, e, reason,
                seen(call, verbs, caller, Optional.of(e))), null);
        }

        /**
         * One status per domain reason.
         *
         * <p>No {@code default}. A reason added to the domain must be given a
         * status here, and the compiler is what asks for it — the alternative
         * is a new refusal quietly becoming a 500 in a deployment nobody is
         * watching.
         */
        public static int statusOf(MemoryException.Reason reason) {
            return switch (reason) {

                // The call is malformed, and no scope had to be known to say
                // so. Grammar is the one family of check that runs before the
                // scope is resolved, precisely because it discloses nothing.
                // An unknown argument and an argument of the wrong value are
                // here too: this service declares no filter fields per scope,
                // so both are decided without one (DEC-0042).
                case ADDRESS_MALFORMED, KEY_MALFORMED, SELECTOR_ABSENT,
                     SELECTOR_MISMATCHED, UPDATE_EMPTY, CONTENT_ABSENT,
                     CONTENT_OVERSIZE, PAGE_SIZE_REJECTED, CURSOR_MALFORMED,
                     CURSOR_FOREIGN, SORT_UNDECLARED, ARGUMENT_UNKNOWN,
                     ARGUMENT_INVALID -> 400;

                // Not this caller. SCOPE_READ_ONLY is not a 404: the caller
                // can already see this scope — the directory answered for it —
                // so saying "you may not write here" reveals nothing a 404
                // would withhold, and a 404 would send somebody looking for a
                // typo in an address that is correct.
                case SCOPE_READ_ONLY, ACTOR_UNKNOWN -> 403;

                // Nothing there — or nothing this subject may know is there.
                // One status for the two, as they carry one code and one
                // message: a status that differed would separate them again
                // below the envelope.
                case ENTRY_ABSENT, SCOPE_UNRESOLVED -> 404;

                // The address or the entry is real and the state says no.
                // ALREADY_EXISTS is the occupied address. SCOPE_LOCKED is here
                // and not with the 403s: a lock is the scope's own state, it
                // is lifted where it was set, and the same caller with the
                // same token gets through once it is. A spent idempotency key
                // is the ledger's state: the key speaks for another call.
                case ALREADY_EXISTS, SCOPE_LOCKED, IDEMPOTENCY_KEY_REUSED -> 409;

                // The conflict token, as HTTP puts a precondition: a stale one
                // failed it, and a missing one was required (C8.7).
                case CONFLICT_TOKEN_STALE -> 412;
                case CONFLICT_TOKEN_MISSING -> 428;

                // Vocabulary: the call parses and names something that is not
                // part of the offering. A type this service does not carry, a
                // selector reserved for the service, a reference that may not
                // be stored at all. No PREDICATE_UNKNOWN: this service declares
                // no filter fields per scope, so an unknown filter is an
                // unknown argument (DEC-0042).
                case TYPE_UNKNOWN, SELECTOR_RESERVED,
                     FIELD_IMMUTABLE, REFERENCE_CREDENTIAL_BEARING -> 422;

                // Ours, not the caller's: the session contract was not bound,
                // and no retry of theirs will fix it.
                case SESSION_NOT_BOUND -> 500;
            };
        }
    }

    /**
     * Everything that is no refusal (C8.3). A {@link WebApplicationException}
     * is the framework's own answer: a route that does not exist is
     * {@code NOT_FOUND}, byte for byte the one of an absent entry; a method the
     * route does not take is {@code VERB_NOT_CARRIED} with the route's
     * {@code Allow} and the verbs carried at the address. Every other status of
     * the framework, the security layer's among them, goes back unchanged (the
     * authentication exception of DEC-0042). Anything else is a defect on our
     * side, logged once with a reference and answered with it.
     *
     * <p>Registered for {@code Throwable} and therefore chosen only where no
     * mapper of a nearer type exists: the three refusal types above, and the
     * framework's own mappers for its security exceptions, keep theirs.
     */
    @Provider
    public static class Unexpected implements ExceptionMapper<Throwable> {

        @Inject RestCall call;
        @Context UriInfo uri;

        @Override
        public Response toResponse(Throwable failure) {
            if (failure instanceof WebApplicationException framework) {
                Response given = framework.getResponse();
                return switch (given.getStatus()) {
                    case 404 -> answer(404, Payloads.RefusalPayload.notFound(), null);
                    case 405 -> methodNotCarried(given);
                    default -> given;
                };
            }
            Optional<AssistantVerb> verb = call.verb();
            String reference = UnexpectedFailures.logged(
                verb.map(AssistantVerb::call).orElse(null), failure);
            return answer(500, Payloads.RefusalPayload.unexpected(verb, verb.map(call::named),
                reference), null);
        }

        /**
         * The framework's 405 in the envelope: the verbs carried at the
         * address where it is one, and the route's methods in {@code Allow}.
         * The framework's own {@code Allow} is taken where it sends one; where
         * it does not, the depth's methods are this surface's.
         */
        private Response methodNotCarried(Response given) {
            Optional<CustomMethod.Depth> depth = depthOf(uri.getPath());
            String allow = given.getHeaderString(HttpHeaders.ALLOW);
            if (allow == null) {
                allow = depth.map(CustomMethod.Depth::allow).orElse(null);
            }
            List<String> accepts = depth.map(CustomMethod.Depth::carried).orElse(List.of())
                .stream().map(call::named).toList();
            return answer(405, Payloads.RefusalPayload.of(
                new SurfaceException(SurfaceException.Reason.VERB_NOT_CARRIED),
                Optional.empty(), accepts), allow);
        }

        /** The depth of a REST path below {@code /api}: a scope, a collection, an entry. */
        private static Optional<CustomMethod.Depth> depthOf(String path) {
            String[] segments = (path.startsWith("/") ? path.substring(1) : path).split("/", -1);
            if (!"api".equals(segments[0])) {
                return Optional.empty();
            }
            return switch (segments.length) {
                case 2 -> Optional.of(CustomMethod.Depth.SCOPE);
                case 3 -> Optional.of(CustomMethod.Depth.COLLECTION);
                case 4 -> Optional.of(CustomMethod.Depth.ITEM);
                default -> Optional.empty();
            };
        }
    }
}
