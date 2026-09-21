package ai.kumbuka.memory.tenancy;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A11 — the service does not start without a tenant, and the shipped
 * configuration does not supply one for it.
 *
 * <h2>Why the shipped file is read rather than the running configuration</h2>
 *
 * A test that asked the booted application for {@code memory.tenant-id} would
 * get the value the TEST profile sets, and would pass whether or not the
 * default in {@code application.properties} is gone. The question here is
 * about the artefact a deployment ships, so the artefact is what is read —
 * from disk, by path, because the classpath carries both files merged and
 * could not tell them apart.
 *
 * <p>This is a plain unit test. The guard reads configuration and nothing
 * else; booting a database to observe that would be measuring something else
 * entirely.
 */
class TenancyConfigurationGuardTest {

    private static final Path SHIPPED = Path.of("src/main/resources/application.properties");

    @Test
    void the_shipped_configuration_supplies_no_tenant_of_its_own() throws IOException {
        String shipped = Files.readString(SHIPPED);

        assertThat(shipped)
            .as("a guessed tenant is the one fault row-level security cannot catch: every "
                + "layer below enforces the axis it is GIVEN and none can know it was "
                + "given another estate's. The default this file used to carry was a real "
                + "tenant id in a development database and no tenant at all anywhere "
                + "else, so it would not even have failed at the first query")
            .doesNotContain(TenancyConfigurationGuard.TENANT_KEY + "=");
        assertThat(shipped)
            .as("and the key is still named in the file, so a reader learns from it which "
                + "environment variable serves the value")
            .contains("MEMORY_TENANT_ID");
    }

    @Test
    void a_configuration_with_no_tenant_refuses_the_start_and_names_the_key() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(configOf()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(TenancyConfigurationGuard.TENANT_KEY)
            .hasMessageContaining("not set")
            .as("and the message names the environment variable a deployment sets")
            .hasMessageContaining("MEMORY_TENANT_ID");
    }

    @Test
    void an_empty_value_is_not_a_set_one() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(
                configOf(TenancyConfigurationGuard.TENANT_KEY, "")))
            .isInstanceOf(IllegalStateException.class)
            .as("an exported-but-empty environment variable is the ordinary shape of a "
                + "misconfigured deployment — a compose file with the value left off, a "
                + "secret that resolved to nothing — and it arrives as a present key with "
                + "a blank value rather than as an absent one")
            .hasMessageContaining("empty");
    }

    @Test
    void a_value_that_is_not_a_uuid_is_refused_here_rather_than_at_the_session_binding() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(
                configOf(TenancyConfigurationGuard.TENANT_KEY, "the-alpha-estate")))
            .isInstanceOf(IllegalStateException.class)
            .as("a value of another shape would otherwise reach the session binding and "
                + "fail there, in a message about a cast rather than about this setting")
            .hasMessageContaining("not a uuid");
    }

    @Test
    void a_configured_tenant_is_answered() {
        UUID tenant = UUID.randomUUID();

        assertThat(TenancyConfigurationGuard.requireConfigured(
                configOf(TenancyConfigurationGuard.TENANT_KEY, tenant.toString())))
            .as("the other half: without it every refusal above would hold just as well "
                + "against a guard that refuses everything")
            .isEqualTo(tenant);
    }

    /** A configuration with exactly what is named and nothing else behind it. */
    private static Config configOf(String... namesAndValues) {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            properties.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "the probe's own", 100))
            .build();
    }
}
