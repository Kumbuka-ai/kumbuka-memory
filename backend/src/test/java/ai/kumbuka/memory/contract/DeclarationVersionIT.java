/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.CounterProbe;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version of the declaration, recomputed from outside the way a router
 * recomputes it (C5.3): over the served body without its {@code version}, in
 * the canonical form of the reference, held in the test tree as
 * {@link ReferenceCanonicalJson}.
 *
 * <p>A version taken over a canonical form of memory's own is a hash nobody
 * else can reproduce; this probe reads the body as data and asks of it only
 * what any other service's declaration is asked. It is a counter-probe and
 * imports nothing from the production packages.
 *
 * <p>The red run, observed on 2026-10-29 against {@code a52b275}, where the
 * service wrote a comma after every member and every element and escaped
 * nothing but quote and backslash: served {@code
 * sha256:b9dfefa6d39489a6cfc438c877f3354879136ca255e57c52669f076485a31c83},
 * recomputed by the reference form {@code
 * sha256:72ae9119b7bc86655783565fbcb2fcf2c64a9adc00e85effff24632a4644d465}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
@CounterProbe
class DeclarationVersionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void the_served_version_is_the_reference_form_of_the_served_body() throws IOException {
        Response answer = given().accept(ContentType.JSON).get("/api/declaration");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        Map<String, Object> body = JSON.readValue(answer.asString(), new TypeReference<>() { });
        Object served = body.get("version");

        Map<String, Object> content = new LinkedHashMap<>(body);
        content.remove("version");
        String recomputed = ReferenceCanonicalJson.version(content);

        assertThat(served)
            .as("the version is sha256 over everything but itself, in the one canonical form "
                + "of the reference (C5.3); a router recomputing it must arrive at the same")
            .isEqualTo(recomputed);
        assertThat(answer.header("ETag"))
            .as("and the ETag is that version (C5.1)")
            .isEqualTo("\"" + recomputed + "\"");
    }
}
