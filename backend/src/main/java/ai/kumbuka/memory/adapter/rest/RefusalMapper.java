/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.rest;

import ai.kumbuka.memory.adapter.payload.Payloads;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.SurfaceException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Turns the two families of typed refusal into HTTP, and keeps them apart.
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
     * The one place every transport refusal passes through.
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed
     * address arrives on every client typo, and a refusal log at WARN would
     * make this service's operational log writable by whoever calls it —
     * flood the surface with broken addresses and you fill the log. What
     * belongs at WARN is a statement about the deployment, and those already
     * live where they are decided: the directory warns on an unresolved scope
     * and on a refused write, and {@code CallerActor} warns on a token with no
     * subject.
     *
     * <p>The actor is absent, as everywhere in this service. A second
     * aggregatable record of who was refused what is how not-collecting
     * behavioural data gets circumvented without anybody deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /**
     * Mapped per exception type rather than over {@code RuntimeException}.
     *
     * <p>A mapper registered for the supertype is chosen for every runtime
     * exception the framework raises too — the 405 a wrong method produces,
     * the 415 a missing content type produces — and re-throwing them from
     * inside a mapper turns each into a 500. The framework's own refusals are
     * part of this surface's contract, so they must reach the caller as
     * themselves.
     */
    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        Response.ResponseBuilder response = Response.status(e.reason().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(Payloads.Refusal.of(e));

        // A 405 without Allow refuses without saying what would have worked,
        // which is the one thing the status is required to carry.
        e.allow().ifPresent(allow -> response.header(HttpHeaders.ALLOW, allow));
        return response.build();
    }

    /**
     * The refusals of a call's arguments, raised on this surface where a
     * required argument is absent or {@code null} (DEC-0043 R17). 400, as the
     * assistant surface's argument refusals are.
     */
    @Provider
    public static class Arguments implements ExceptionMapper<CallException> {

        @Inject RestCall call;

        @Override
        public Response toResponse(CallException e) {
            LOG.debugf("argument refusal: %s", e.reason());
            return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Payloads.Refusal.of(verbOf(call), e))
                .build();
        }
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

    /** The domain's refusals, on the same envelope and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<MemoryException> {

        @Inject RestCall call;

        @Override
        public Response toResponse(MemoryException e) {
            if (e instanceof AssistantRefused assistant) {
                LOG.debugf("assistant refusal: %s", e.reason().name());
                return Response.status(statusOf(e.reason()))
                    .type(MediaType.APPLICATION_JSON)
                    .entity(assistant.refusal())
                    .build();
            }
            int status = statusOf(e.reason());

            // A 5xx is ours, not the caller's, and no retry of theirs fixes
            // it. That is the one refusal class this surface raises to ERROR:
            // everything else is a caller being told no, which is the surface
            // working.
            if (status >= 500) {
                LOG.errorf("domain refusal answered %d: %s", status, e.reason().name());
            } else {
                LOG.debugf("domain refusal: %s -> %d", e.reason().name(), status);
            }

            // The envelope is built in ONE place. What a caller reads here —
            // the collapsed not-found code, the detail under `data` — is not
            // this mapper's decision to make a second time; the status is.
            return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Payloads.Refusal.of(verbOf(call), e))
                .build();
        }

        /**
         * One status per domain reason.
         *
         * <p>No {@code default}. A reason added to the domain must be given a
         * status here, and the compiler is what asks for it — the alternative
         * is a new refusal quietly becoming a 500 in a deployment nobody is
         * watching.
         */
        static int statusOf(MemoryException.Reason reason) {
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
                // ALREADY_EXISTS is the occupied address; the two token
                // refusals are the lost race. SCOPE_LOCKED is here and not
                // with the 403s: a lock is the scope's own state, it is lifted
                // where it was set, and the same caller with the same token
                // gets through once it is.
                case ALREADY_EXISTS, CONFLICT_TOKEN_MISSING, CONFLICT_TOKEN_STALE,
                     SCOPE_LOCKED -> 409;

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
}
