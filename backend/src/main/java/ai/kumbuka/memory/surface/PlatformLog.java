/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.surface;

import ai.kumbuka.memory.domain.Memory;
import ai.kumbuka.memory.domain.MemoryException;
import org.jboss.logging.Logger;

import java.util.Collection;

/**
 * Every log line of the packages that serve a caller, and the only logger
 * they hold (DEC-0051, C4; C13.1, C13.2).
 *
 * <p>A line carries the platform's vocabulary and nothing else: the verb, the
 * technical address, the reason code, a count, the reference and the class
 * names of an unforeseen failure. Each line is one method here, with a
 * constant message, and each parameter is a type the service builds from
 * what it holds itself, never from a {@code String}, a number or a uuid a
 * caller could have chosen. So a value a caller wrote cannot reach a line:
 * there is no parameter that would take it.
 *
 * <p>The request id travels through the MDC into the format, and the service
 * through the logger's category; neither is a parameter.
 *
 * <p>{@code LogFacadeAccessTest} holds that no other class of the service
 * holds or calls a logger, {@code LogFacadeTypeTest} that every parameter is
 * one of the platform's types and that none of them can be built from a text.
 */
public final class PlatformLog {

    /*
     * Each message is a literal inside its logger call: constant, written
     * once, and where MessageBundleTest reads a sentence as a note for the
     * log rather than a text for a caller.
     */
    private static final Logger LOG = Logger.getLogger(PlatformLog.class);

    private PlatformLog() {
    }

    // ======================================================================
    // Writes: one INFO line per completed write, by the technical address
    // (C13.3). The canonical address carries the caller's key, and whether
    // that may stand in a line is not decided.
    // ======================================================================

    public static void created(TechnicalAddress entry) {
        LOG.infof("create %s", entry);
    }

    public static void updated(TechnicalAddress entry) {
        LOG.infof("update %s", entry);
    }

    public static void withdrawn(TechnicalAddress entry) {
        LOG.infof("withdraw %s", entry);
    }

    public static void tagAttached(TechnicalAddress entry) {
        LOG.infof("tag_attach %s", entry);
    }

    public static void tagDetached(TechnicalAddress entry) {
        LOG.infof("tag_detach %s", entry);
    }

    // ======================================================================
    // Refusals: DEBUG by their code, because a refusal is the surface
    // working and a log a caller can fill by sending broken calls is a log a
    // caller writes (C13.3).
    // ======================================================================

    public static void refused(RefusalCode code) {
        LOG.debugf("refusal: %s", code);
    }

    public static void assistantRefused(RefusalCode code) {
        LOG.debugf("assistant refusal: %s", code);
    }

    /**
     * WARN: a session the read contract could not be bound for is a
     * statement about the deployment, not about the call. Written where its
     * refusal arrives at the surface.
     */
    public static void sessionNotBound(RefusalCode code) {
        LOG.warnf("directory call with unbound session: %s", code);
    }

    /**
     * WARN: a token that authenticated and carries no subject is a realm
     * misconfiguration no caller can fix. The subject is not logged; there is
     * none, and which token it was belongs to the audit log.
     */
    public static void tokenWithoutSubject(RefusalCode code) {
        LOG.warnf("a token authenticated with no subject to act as: %s", code);
    }

    /** That the endpoint was reached, and nothing about who reached it. */
    public static void whoamiReached() {
        LOG.debug("whoami");
    }

    // ======================================================================
    // Start
    // ======================================================================

    public static void surfaceDeclared(Count calls, Count reasons) {
        LOG.infof("assistant surface declared: %s calls, %s reasons", calls, reasons);
    }

    /**
     * WARN, on a line of its own before the start fails (C13.3): the format
     * prints no exception (C13.1), so the problem is named here by its
     * constant. The exception's own text, which names configuration values,
     * stays out of the line.
     */
    public static void startRefused(StartProblem problem) {
        LOG.warnf("start refused: %s", problem);
    }

    // ======================================================================
    // The unforeseen failure (C8.3): ERROR, with the reference the caller is
    // told to report and the class names of the failure's chain.
    // ======================================================================

    static void unexpectedFailure(VerbName call, UnexpectedFailures.FailureReference reference,
                                  UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on %s, reference %s, cause %s", call, reference, cause);
    }

    static void unexpectedFailureBeforeACall(UnexpectedFailures.FailureReference reference,
                                             UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on this request, reference %s, cause %s", reference, cause);
    }

    // ======================================================================
    // The types a line takes
    // ======================================================================

    /**
     * An entry by its technical address, {@code memory://<logical id>}: minted
     * by this service, and no value its caller wrote. Built from the entity
     * only.
     */
    public static final class TechnicalAddress {

        private final String address;

        private TechnicalAddress(String address) {
            this.address = address;
        }

        static TechnicalAddress of(Memory entry) {
            return new TechnicalAddress("memory://" + entry.logicalId);
        }

        @Override
        public String toString() {
            return address;
        }
    }

    /**
     * The reason code of a refusal, out of one of the service's closed sets:
     * the domain's, a call's arguments', or the transport's.
     */
    public static final class RefusalCode {

        private final Enum<?> reason;

        private RefusalCode(Enum<?> reason) {
            this.reason = reason;
        }

        public static RefusalCode of(MemoryException.Reason reason) {
            return new RefusalCode(reason);
        }

        public static RefusalCode of(CallException.Reason reason) {
            return new RefusalCode(reason);
        }

        public static RefusalCode of(SurfaceException.Reason reason) {
            return new RefusalCode(reason);
        }

        @Override
        public String toString() {
            return reason.name();
        }
    }

    /** How many members a collection the service holds has. */
    public static final class Count {

        private final int size;

        private Count(int size) {
            this.size = size;
        }

        public static Count of(Collection<?> members) {
            return new Count(members.size());
        }

        @Override
        public String toString() {
            return Integer.toString(size);
        }
    }

    /**
     * What a start was refused for, without a value: the configuration value
     * that was wrong stays in the exception, out of the line.
     */
    public enum StartProblem {

        /** A reason the service can raise has no entry in the catalogue. */
        REASON_CATALOGUE_INCOMPLETE,

        /** An argument publishes a schema keyword the tool schema does not carry. */
        SCHEMA_KEYWORD_UNKNOWN,

        /** The declaration could not be served as it states itself. */
        DECLARATION_NOT_SERVABLE,

        /** The principal claim is not pinned to the token's subject. */
        PRINCIPAL_CLAIM_NOT_SUBJECT,

        /** No audience is configured, so none would be validated. */
        AUDIENCE_NOT_SET
    }
}
