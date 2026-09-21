package ai.kumbuka.memory.tenancy;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.UUID;

/**
 * Refuses to start without a tenant.
 *
 * <h2>Why the value has no default</h2>
 *
 * A guessed tenant is the one fault row-level security cannot catch. Every
 * layer below this one — the ORM filter, the transaction-local session
 * setting, the policy — enforces the axis it is GIVEN; none of them can know
 * the value it was given is another estate's. A default therefore does not
 * make the service tolerant of a missing setting, it makes it read and write
 * somebody else's tenant confidently, and the deployment reports a clean
 * start. One installation serves one tenant (ADR-0035), and which one is the
 * installation's to say.
 *
 * <p>The default this file removed was
 * {@code 00000000-0000-0000-0000-000000000001}, which is a real tenant id in a
 * development database and no tenant at all in a deployment — so it would not
 * even have failed loudly at the first query.
 *
 * <h2>Why the check sits in two places and is written once</h2>
 *
 * The application's start and the migration run are two entries, and the
 * migration run is the earlier and the more damaging of the two: V4 seeds a
 * row for the configured tenant, and by the time a startup observer fires it
 * has already run. So {@link TenantMigrationCallback} calls this before every
 * migration, and the observer below calls it for a boot that migrates nothing
 * — an already-current database, or a deployment with migrate-at-start off.
 * One check, two callers; a second copy is how the two would come to disagree
 * about what counts as set.
 *
 * <p><strong>Empty is not set.</strong> An exported-but-empty environment
 * variable is the ordinary shape of a misconfigured deployment — a compose
 * file with the value left off, a secret that resolved to nothing — and it
 * arrives as a present key with a blank value rather than as an absent one.
 * Treating it as set is how an empty string reaches a uuid cast and fails
 * there instead, with a message about SQL and no mention of the setting.
 */
@ApplicationScoped
public class TenancyConfigurationGuard {

    /** The tenancy axis for this deployment. Environment: {@code MEMORY_TENANT_ID}. */
    public static final String TENANT_KEY = "memory.tenant-id";

    /**
     * The boot path that migrates nothing.
     *
     * <p>Observing the startup event rather than eagerly constructing this
     * bean: the value is read here and nowhere else in this class, so an
     * injection point would be a second place the same question is asked, and
     * a lazily constructed bean would be asked at the first request rather
     * than at the start — which is a running deployment answering calls with
     * no tenant, exactly what this refuses.
     */
    void refuseToStartWithoutTheAxis(@Observes StartupEvent event) {
        requireConfigured(ConfigProvider.getConfig());
    }

    /**
     * The tenant, or a refusal naming the key.
     *
     * <p>Takes the {@link Config} rather than reaching for the ambient one, so
     * that the check can be run against a configuration a test assembles — in
     * particular against the shipped {@code application.properties} with no
     * environment behind it, which is the only way to observe that the default
     * is gone rather than that a test happened to set the value.
     *
     * @return the configured tenant
     * @throws IllegalStateException when it is absent, blank, or not a uuid
     */
    public static UUID requireConfigured(Config config) {
        // getConfigValue and NOT getOptionalValue, and the difference is the
        // whole reason this distinction survives. SmallRye converts an empty
        // string to an absent Optional, so a guard reading the optional form
        // would report "not set" for an exported-but-empty environment
        // variable — which is the ordinary shape of a misconfigured
        // deployment and the one an operator most needs named correctly.
        // Measured 2026-09-21: the optional form answered empty for a key
        // present with a blank value.
        ConfigValue raw = config.getConfigValue(TENANT_KEY);
        String value = raw == null ? null : raw.getValue();

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "'" + TENANT_KEY + "' is " + (value == null ? "not set" : "empty")
                    + ", and this service will not start without it. It names the estate "
                    + "this installation serves; one installation serves one tenant, and "
                    + "the value is never guessed, defaulted or inferred — a wrongly "
                    + "resolved tenant produces well-formed queries against the wrong "
                    + "estate's data, which row-level security cannot catch because "
                    + "nothing about such a query is malformed. Set the environment "
                    + "variable MEMORY_TENANT_ID to the estate's tenant id.");
        }

        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new IllegalStateException(
                "'" + TENANT_KEY + "' is set to something that is not a uuid. A tenant id "
                    + "is a uuid; a value of another shape would reach the session "
                    + "binding and fail there, in a message about a cast rather than "
                    + "about this setting. Set MEMORY_TENANT_ID to the estate's tenant "
                    + "id.", notAUuid);
        }
    }
}
