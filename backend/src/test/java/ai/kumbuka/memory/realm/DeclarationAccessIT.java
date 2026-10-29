/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.realm;

import ai.kumbuka.memory.architecture.DeclarationShapeTest;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who reads the declaration, where, and in which shape (C5.1, C5.2, C5.3).
 *
 * <p>Against a real identity provider, because the statement is about a real
 * token: the realm's user holds the realm's {@code member} role and nothing of
 * this service, so a token of it is a valid platform token with no role here.
 * That token reads the declaration; no token reads nothing but the protocol's
 * challenge; and the declaration is served at {@code /api/declaration} and at
 * the two transitional paths C5.1 names, and nowhere else.
 *
 * <p>The shape of what is served is checked against the schema in the test
 * tree, through {@link DeclarationShapeTest#violations}: the declaration
 * carries the texts of the message bundles, which only the running service
 * can render.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = RealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class DeclarationAccessIT {

    private static final String DECLARATION = "/api/declaration";

    /** The paths C5.1 lets serve the declaration while the router has not moved. */
    private static final List<String> TRANSITIONAL = List.of("/api", "/mcp/declaration");

    /** The deeper routes resolve a scope, which reads the platform's directory. */
    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void a_valid_token_with_no_role_of_this_service_reads_the_declaration() {
        Response read = given()
            .header("Authorization", "Bearer " + memberToken())
            .accept(ContentType.JSON)
            .when().get(DECLARATION);

        assertThat(read.statusCode())
            .as("the declaration needs a valid platform token and no role (C5.2): the "
                + "router reads it with its own service identity. It said: %s",
                read.asString())
            .isEqualTo(200);
        assertThat(read.header("ETag"))
            .as("the ETag is the declaration's version (C5.1)")
            .isEqualTo("\"" + read.jsonPath().getString("version") + "\"");
    }

    @Test
    void no_token_reads_no_declaration_and_is_challenged() {
        Response read = given().accept(ContentType.JSON).when().get(DECLARATION);
        assertThat(read.statusCode())
            .as("the declaration is behind authentication like every route of the surface")
            .isEqualTo(401);
        assertThat(read.header("WWW-Authenticate"))
            .as("and the answer is the protocol's challenge, naming the bearer scheme")
            .startsWith("Bearer");
        assertThat(read.asString()).doesNotContain("shape_version");
    }

    @Test
    void the_declaration_is_served_at_its_path_and_the_transitional_ones_only() {
        String token = memberToken();
        Map<String, Object> served = read(token, DECLARATION).jsonPath().getMap("$");
        for (String path : TRANSITIONAL) {
            assertThat(read(token, path).jsonPath().getMap("$"))
                .as("%s serves the declaration of %s until the router binds it (C5.1)",
                    path, DECLARATION)
                .isEqualTo(served);
        }
        for (String path : List.of("/declaration", "/mcp/api/declaration",
                "/api/declaration/decision", "/api/declaration/decision/decision.x")) {
            Response other = given()
                .header("Authorization", "Bearer " + token)
                .accept(ContentType.JSON)
                .when().get(path);
            assertThat(other.asString())
                .as("%s is not a path of the declaration (C5.1); it answered %d",
                    path, other.statusCode())
                .doesNotContain("shape_version");
        }
    }

    @Test
    void below_the_declaration_s_path_declaration_is_no_scope() {
        Response collection = given()
            .header("Authorization", "Bearer " + memberToken())
            .accept(ContentType.JSON)
            .when().get("/api/declaration/decision");
        assertThat(collection.statusCode())
            .as("declaration is the name of a literal path and never a scope (C3.2): a path "
                + "below it is refused before the route is matched. It said: %s",
                collection.asString())
            .isEqualTo(400);
        assertThat(collection.jsonPath().getString("reason")).isEqualTo("ADDRESS_MALFORMED");
    }

    @Test
    void the_served_declaration_has_the_contract_shape() {
        Map<String, Object> declaration = read(memberToken(), DECLARATION).jsonPath().getMap("$");

        assertThat(DeclarationShapeTest.violations(declaration))
            .as("the declaration, as served, against the schema of C5.3 to C5.5 held in the "
                + "test tree")
            .isEmpty();
    }

    private static Response read(String token, String path) {
        Response read = given()
            .header("Authorization", "Bearer " + token)
            .accept(ContentType.JSON)
            .when().get(path);
        assertThat(read.statusCode()).as("%s: %s", path, read.asString()).isEqualTo(200);
        return read;
    }

    /**
     * A real access token from the realm, by direct grant.
     *
     * <p>The grant is enabled on the test realm only. Driving the
     * authorization-code flow would add a browser to a test about a token and
     * change nothing about the token that comes out of it.
     */
    private static String memberToken() {
        String issuer = ConfigProvider.getConfig()
            .getValue(RealmKeycloakResource.TENANT_ISSUER_KEY, String.class);
        return given()
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "password")
            .formParam("client_id", RealmKeycloakResource.CLIENT_ID)
            .formParam("client_secret", RealmKeycloakResource.CLIENT_SECRET)
            .formParam("username", RealmKeycloakResource.TENANT_USER)
            .formParam("password", RealmKeycloakResource.USER_PASSWORD)
            .when().post(issuer + "/protocol/openid-connect/token")
            .then().statusCode(200)
            .extract().path("access_token");
    }
}
