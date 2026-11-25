/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.platform;

import ai.kumbuka.memory.tenancy.TenantContext;
import ai.kumbuka.security.contract.AuthenticationContext;
import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.CallFactsSource;
import ai.kumbuka.security.contract.PresentedCredential;
import ai.kumbuka.security.contract.ScopeGrant;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The facts of a call, as the community edition establishes them: from the
 * verified token and the platform's scope view, and from nothing else.
 *
 * <ul>
 *   <li><b>Subject</b>: the token's principal, which the identity
 *       configuration pins to {@code sub}.</li>
 *   <li><b>Roles</b>: the realm roles the token carries
 *       ({@code realm_access.roles}).</li>
 *   <li><b>Permissions</b>: what those roles grant through the fixed mapping
 *       of the system roles ({@link SystemRoles}), tenant-wide.</li>
 *   <li><b>Scopes</b>: every scope of the platform's view under the tenant and
 *       subject binding the directory uses for every read, each with its
 *       technical identity and its slug, so that nobody has to resolve one
 *       from the other.</li>
 *   <li><b>Tenant</b>: the tenant the installation resolves.</li>
 *   <li><b>Actor</b>: none. The community edition knows no delegated
 *       identity.</li>
 *   <li><b>Channel and authentication</b>: as far as the token carries them --
 *       the client it was issued to ({@code azp}); the methods ({@code amr}),
 *       the context class ({@code acr}) and the moment ({@code auth_time},
 *       else {@code iat}). A member the token does not carry is the empty text,
 *       the moment the epoch, and the channel {@link #UNNAMED_CHANNEL}. No
 *       decision of this service reads either.</li>
 * </ul>
 *
 * <p>A default bean: an edition that establishes the facts otherwise brings
 * its own implementation and this one stands down. The binding calls it once
 * per authenticated call, off the event loop, and fails the call if it fails;
 * a principal without a name has no facts, and every declared method refuses
 * it.
 */
@DefaultBean
@ApplicationScoped
public class CommunityCallFacts implements CallFactsSource {

    /** The channel of a call whose token names no client. */
    static final String UNNAMED_CHANNEL = "unnamed";

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Inject ScopeDirectory scopes;
    @Inject TenantContext tenants;

    /**
     * {@inheritDoc}
     *
     * <p>Runs in a request context of its own where none is active: the
     * binding asks for the facts while it authenticates the call, on a worker
     * thread before the request's context exists, and the persistence layer
     * resolves the tenant of a session only inside one.
     */
    @Override
    @ActivateRequestContext
    public Optional<CallFacts> factsOf(PresentedCredential credential) {
        String subject = credential.principal();
        if (subject.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> claims = credential.claims();
        Set<String> roles = realmRoles(claims);
        Set<ScopeGrant> grants = scopes.visibleScopes(subject).stream()
            .map(scope -> new ScopeGrant(scope.scopeId(), scope.slug()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Optional.of(new CallFacts(subject, Optional.empty(), roles,
            SystemRoles.permissionsOf(roles), grants, tenants.current(), channel(claims),
            authentication(claims)));
    }

    /** The realm roles the token carries; none where it carries no such claim. */
    static Set<String> realmRoles(Map<String, Object> claims) {
        if (!(claims.get(REALM_ACCESS) instanceof Map<?, ?> access)
                || !(access.get(ROLES) instanceof Collection<?> roles)) {
            return Set.of();
        }
        Set<String> named = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String text && !text.isBlank()) {
                named.add(text);
            }
        }
        return Set.copyOf(named);
    }

    private static String channel(Map<String, Object> claims) {
        return text(claims.get("azp")).filter(client -> !client.isBlank())
            .orElse(UNNAMED_CHANNEL);
    }

    private static AuthenticationContext authentication(Map<String, Object> claims) {
        String methods = claims.get("amr") instanceof List<?> named
            ? named.stream().map(String::valueOf).collect(Collectors.joining(","))
            : text(claims.get("amr")).orElse("");
        String contextClass = text(claims.get("acr")).orElse("");
        Instant at = moment(claims.get("auth_time"))
            .or(() -> moment(claims.get("iat")))
            .orElse(Instant.EPOCH);
        return new AuthenticationContext(methods, contextClass, at);
    }

    private static Optional<String> text(@Nullable Object value) {
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    private static Optional<Instant> moment(@Nullable Object value) {
        return value instanceof Number seconds
            ? Optional.of(Instant.ofEpochSecond(seconds.longValue()))
            : Optional.empty();
    }
}
