package ai.kumbuka.memory.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Calls the assistant surface over HTTP, the way an MCP client does.
 *
 * <p>Builds the JSON-RPC envelope and nothing else. What comes back is read by
 * each probe for itself; this class knows no expected value.
 */
public final class Mcp {

    public static final String PATH = "/mcp";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static int nextId = 1;

    private Mcp() {
    }

    /** One JSON-RPC request; the whole response, envelope included. */
    public static Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", nextId++);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        return post(request);
    }

    public static Response post(Object body) {
        try {
            return given().contentType(ContentType.JSON).accept(ContentType.JSON)
                .body(JSON.writeValueAsString(body))
                .post(PATH);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One tool call; the tool result under {@code result}. */
    public static Result call(String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        Response response = rpc("tools/call", params);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("tools/call answered HTTP " + response.statusCode()
                + ": " + response.asString());
        }
        return new Result(response.jsonPath());
    }

    /** Arguments in the order written: name, value, name, value. */
    public static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            args.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return args;
    }

    /** A tool result, read from its structured content. */
    public record Result(JsonPath json) {

        public boolean isError() {
            return json.getBoolean("result.isError");
        }

        public String string(String path) {
            return json.getString("result.structuredContent." + path);
        }

        public List<String> strings(String path) {
            return json.getList("result.structuredContent." + path, String.class);
        }

        public Map<String, Object> map(String path) {
            return json.getMap("result.structuredContent." + path);
        }

        public Map<String, Object> structured() {
            return json.getMap("result.structuredContent");
        }

        /** The text content, which must be the structured content as JSON. */
        public String text() {
            return json.getString("result.content[0].text");
        }

        public String reason() {
            return string("reason");
        }

        public String message() {
            return string("message");
        }

        @Override
        public String toString() {
            return json.prettify();
        }
    }
}
