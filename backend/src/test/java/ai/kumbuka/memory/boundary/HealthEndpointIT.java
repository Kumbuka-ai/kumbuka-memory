package ai.kumbuka.memory.boundary;

import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health endpoint — the one thing this service serves over HTTP, and the
 * one statement a deployment can make about it without a caller.
 *
 * <p><strong>Why readiness is the interesting one.</strong> Liveness says the
 * process is running, which a container runtime already knows. Readiness is
 * the claim that matters here, and only because the datasource extension
 * contributes a check to it: "ready" then means the service reached the
 * database <em>as its own unprivileged role</em>, which is the failure this
 * deployment is most likely to have — a rotated password, a missing CONNECT,
 * a role that was never created. A readiness route that answered UP without
 * asking the database would be a constant dressed as a probe, and Compose
 * would gate a dependent on it and release the gate on nothing.
 *
 * <p>So the third assertion below is not decoration. It reads the payload and
 * requires the database check to be <em>named in it</em>. Remove
 * {@code quarkus.datasource.health.enabled} and the route still answers 200
 * UP; that case is exactly what this test refuses to accept as healthy.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class HealthEndpointIT {

    /**
     * Injected rather than composed from a port setting. The container's
     * healthcheck names {@code /q/health/ready} literally, and this test has
     * to ask the same server the deployment will ask.
     */
    @TestHTTPResource("/q/health")
    URL healthRoot;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Test
    void liveness_answers_up() throws Exception {
        var response = get("/live");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\": \"UP\"");
    }

    @Test
    void readiness_answers_up() throws Exception {
        var response = get("/ready");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\": \"UP\"");
    }

    @Test
    void readiness_asks_the_database_rather_than_answering_a_constant() throws Exception {
        var body = get("/ready").body();

        assertThat(body)
            .as("readiness must carry the datasource check by name. Without it the route "
                + "answers UP for a service that never reached its database, and the "
                + "orchestrator would release a dependent on that answer")
            .contains("Database connections health check");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(healthRoot + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
