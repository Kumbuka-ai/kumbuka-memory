/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP adapter speaks the protocol the contract fixes (C4.1 to C4.5).
 *
 * <p>JSON-RPC 2.0 at {@code POST /mcp}, protocol {@code 2025-06-18}; the methods
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call};
 * a notification acknowledged with 202. {@code tools/list} is the declaration:
 * its names are the identities {@code GET /api/declaration} publishes, in its
 * order. An unknown tool is the protocol's -32602 with the declared tool names
 * under {@code error.data.accepts} and no part of the name that arrived, and
 * so is a {@code tools/call} without a name or with a name that is no text;
 * an unknown method is -32601. A refused call is a tool result with
 * {@code isError}. {@code serverInfo.version} is the version the POM builds.
 *
 * <p>The expectations are read from outside the service: the POM's version
 * from the POM, the identities from the served declaration, the codes from the
 * MCP specification.
 *
 * <p>RED STATE, observed 2026-10-29 against the adapter before this change
 * (the tree of B1, with this class copied in): the unknown tool and both
 * nameless calls answered -32602 without {@code error.data.accepts}; the call
 * without a name was read as a tool named {@code null}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
class McpProtocolIT {

    private static final String MARKER = "zzecho5521";

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void initialize_names_the_protocol_and_the_version_the_build_carries() throws IOException {
        Response answer = Mcp.rpc("initialize", Map.of());
        assertThat(answer.jsonPath().getString("jsonrpc")).isEqualTo("2.0");
        assertThat(answer.jsonPath().getString("result.protocolVersion")).isEqualTo("2025-06-18");
        assertThat(answer.jsonPath().getString("result.serverInfo.version"))
            .as("serverInfo.version is the POM's version, read from the build (C4.5)")
            .isEqualTo(pomVersion());
        assertThat(answer.jsonPath().getMap("result.capabilities")).containsKey("tools");
    }

    @Test
    void ping_answers_an_empty_result() {
        Response answer = Mcp.rpc("ping", Map.of());
        assertThat(answer.jsonPath().getMap("result")).as("C4.1: ping answers {}").isEmpty();
        assertThat(answer.jsonPath().getMap("$")).doesNotContainKey("error");
    }

    @Test
    void the_tool_list_is_the_declaration() {
        List<String> tools = toolNames();
        List<String> identities = given().get(SurfaceFixture.API + "/declaration")
            .jsonPath().getList("calls.identity", String.class);
        assertThat(tools)
            .as("tools/list is generated from the declaration, one tool per call under its "
                + "identity, in its order (C4.2, ADR-0039)")
            .isEqualTo(identities);
    }

    @Test
    void an_unknown_tool_is_invalid_params_naming_the_declared_tools_and_not_the_one_sent() {
        Response answer = toolsCall(Map.of("name", "memory_" + MARKER, "arguments", Map.of()));
        assertUnknownTool(answer);
        assertThat(answer.asString()).as("no part of the name that arrived")
            .doesNotContain(MARKER);
    }

    /** No name is no tool, and the answer names no tool nobody sent. */
    @Test
    void a_call_without_a_name_is_invalid_params_naming_the_declared_tools() {
        Response answer = toolsCall(Map.of("arguments", Map.of()));
        assertUnknownTool(answer);
        assertThat(answer.jsonPath().getString("error.message"))
            .as("the absent name is no word of the answer").doesNotContain("null");
    }

    @Test
    void a_call_whose_name_is_no_text_is_invalid_params_naming_the_declared_tools() {
        Response answer = toolsCall(Map.of("name", 4711, "arguments", Map.of()));
        assertUnknownTool(answer);
        assertThat(answer.asString()).doesNotContain("4711");
    }

    @Test
    void an_unknown_method_is_method_not_found_and_names_nothing_sent() {
        Response answer = Mcp.rpc("tools/" + MARKER, Map.of());
        assertThat(answer.jsonPath().getInt("error.code")).isEqualTo(-32601);
        assertThat(answer.asString()).doesNotContain(MARKER);
    }

    @Test
    void a_notification_is_acknowledged_and_answered_with_nothing() {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        Response answer = given().contentType(ContentType.JSON).body(notification)
            .post(Mcp.PATH);
        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(answer.asString()).isEmpty();
    }

    @Test
    void a_refused_call_is_a_tool_result_marked_as_an_error_with_the_envelope() {
        Mcp.Result answer = Mcp.call("memory_read", Mcp.args("address",
            "memory://" + SubstrateDatabaseResource.PROBE_SCOPE_SLUG + "/decision/nothing-here"));
        assertThat(answer.isError()).isTrue();
        assertThat(answer.structured()).containsOnlyKeys("reason", "message")
            .containsEntry("reason", "NOT_FOUND");
        assertThat(answer.json().getString("result.content[0].text"))
            .as("the same envelope as text, for a client that reads only text")
            .contains("\"reason\":\"NOT_FOUND\"");
    }

    private static void assertUnknownTool(Response answer) {
        assertThat(answer.jsonPath().getInt("error.code")).as(answer.asString())
            .isEqualTo(-32602);
        assertThat(answer.jsonPath().getList("error.data.accepts", String.class))
            .as("the declared tool names (C4.3)").isEqualTo(toolNames());
        assertThat(answer.jsonPath().getMap("$")).doesNotContainKey("result");
    }

    private static Response toolsCall(Map<String, Object> params) {
        return Mcp.rpc("tools/call", params);
    }

    private static List<String> toolNames() {
        return Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.name", String.class);
    }

    /** The version the POM of this module builds, read from the POM. */
    private static String pomVersion() throws IOException {
        Path pom = Files.isRegularFile(Path.of("pom.xml")) ? Path.of("pom.xml")
            : Path.of("backend", "pom.xml");
        String text = Files.readString(pom);
        Matcher m = Pattern.compile(
            "<artifactId>kumbuka-memory</artifactId>\\s*<version>([^<]+)</version>")
            .matcher(text);
        assertThat(m.find()).as("the module's own version stands in its POM").isTrue();
        return m.group(1);
    }
}
