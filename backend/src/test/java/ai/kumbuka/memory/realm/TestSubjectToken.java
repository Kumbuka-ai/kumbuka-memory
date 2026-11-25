/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.realm;

import ai.kumbuka.security.quarkus.CallFactsAugmentor;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.security.TestSecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The token a {@code @TestSecurity} identity stands for, and the facts of its
 * calls, established as a deployment establishes them.
 *
 * <p>The suite supplies its identities with {@code @TestSecurity} and no realm
 * (application.properties of the tests). Such an identity carries the subject
 * and the roles the annotation names, but no token, and the permission check
 * reads the roles from the token's realm roles. This bean turns the identity
 * into one whose principal is that token -- the subject as {@code sub}, the
 * roles as {@code realm_access.roles}, as Keycloak issues them -- and hands it
 * to the binding's own augmentor, which asks the service's own facts source
 * ({@code platform.CommunityCallFacts}). So every test call is decided by the
 * real check on the real facts, from the subject's scopes in the substrate and
 * the roles the test names; nothing here grants or refuses anything.
 *
 * <p>Quarkus applies it once per test method, when the method's
 * {@code @TestSecurity} identity is built, and the identity serves every call
 * of the method, over HTTP and to a bean alike. A test that changes a
 * subject's scopes therefore does so before the method starts.
 *
 * <p>Where the facts cannot be established -- a test class that never granted
 * the service the platform's scope view -- the identity keeps its token and
 * carries no facts, and every declared method refuses it, as a deployment
 * refuses a call without facts. A test that makes no declared call, such as
 * one that reads the declaration, is not affected.
 */
@ApplicationScoped
public class TestSubjectToken implements TestSecurityIdentityAugmentor {

    @Inject CallFactsAugmentor facts;

    @Override
    public SecurityIdentity augment(SecurityIdentity identity, Annotation[] annotations) {
        if (identity.isAnonymous()) {
            return identity;
        }
        SecurityIdentity carried = QuarkusSecurityIdentity.builder(identity)
            .setPrincipal(new Token(identity.getPrincipal().getName(), identity.getRoles()))
            .build();
        AuthenticationRequestContext here = supplier -> Uni.createFrom().item(supplier);
        try {
            return facts.augment(carried, here).await().indefinitely();
        } catch (RuntimeException noFacts) {
            return carried;
        }
    }

    /** The claims of the test subject's token: its subject and its realm roles. */
    static final class Token implements JsonWebToken {

        private final String subject;
        private final Map<String, Object> claims;

        Token(String subject, Set<String> roles) {
            this.subject = subject;
            Map<String, Object> carried = new LinkedHashMap<>();
            carried.put("sub", subject);
            carried.put("realm_access", Map.of("roles", List.copyOf(roles)));
            this.claims = Map.copyOf(carried);
        }

        @Override
        public String getName() {
            return subject;
        }

        @Override
        public Set<String> getClaimNames() {
            return claims.keySet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String claimName) {
            return (T) claims.get(claimName);
        }
    }
}
