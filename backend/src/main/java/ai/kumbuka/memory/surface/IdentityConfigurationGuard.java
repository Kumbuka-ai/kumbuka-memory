package ai.kumbuka.memory.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Optional;

/**
 * Refuses to start when the acting identity would not be the token's subject,
 * or when the audience is not checked at all.
 *
 * <h2>The defect this blocks, measured elsewhere first</h2>
 *
 * Quarkus resolves a service application's principal through
 * {@code upn -> preferred_username -> sub} unless it is pinned. In this
 * service the principal is written into {@code owner_subject} and compared
 * against it on every private read, so an unpinned principal means an entry
 * authored under an email address and a private entry whose owner check
 * compares two different kinds of name. Both fail quietly: the write succeeds,
 * the read returns nothing, and it reads as missing data.
 *
 * <p>The core carries the same guard for the same reason, and it exists there
 * because the pin shipped mis-pathed once and nothing noticed until somebody
 * looked at a row.
 *
 * <h2>Why the audience is checked here too</h2>
 *
 * An unset {@code quarkus.oidc.token.audience} does not fail; it means the
 * audience is not validated. A deployment that lost the value would accept a
 * token minted for anything at all, with a clean start and no error — which is
 * the same failure shape as the principal claim and belongs in the same guard.
 * ADR-0034 gives the platform one audience and makes every recipient validate
 * it; what the value IS is a deployment's to configure, and what this asserts
 * is that there is one.
 *
 * <h2>The escape hatch, and why it is the tenant flag</h2>
 *
 * A disabled bearer tenant validates no token, so there is no principal to pin
 * and no audience to check; asserting about one would be asserting about a
 * surface that is not there. The default is {@code true}, so silence never
 * disarms the guard — only an explicit statement that authentication is off
 * does, which is what a test that stands up no realm says.
 */
@ApplicationScoped
public class IdentityConfigurationGuard {

    /** The claim the acting identity must come from. */
    public static final String EXPECTED_PRINCIPAL_CLAIM = "sub";

    static final String PRINCIPAL_CLAIM_KEY = "quarkus.oidc.token.principal-claim";
    static final String AUDIENCE_KEY = "quarkus.oidc.token.audience";
    static final String TENANT_ENABLED_KEY = "quarkus.oidc.tenant-enabled";

    /**
     * Runs as early as an observer of the startup event can, so that the
     * refusal arrives before anything else the boot does can look like the
     * cause.
     */
    void refuseToStartOnAWrongIdentity(
            @Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        verify(ConfigProvider.getConfig());
    }

    /**
     * The two settings, or a refusal naming the one that is wrong.
     *
     * <p>Takes the {@link Config} so that a test can run it against an
     * assembled configuration rather than only against the one it booted with
     * — which is what lets the red state be observed without a second
     * application.
     */
    public static void verify(Config config) {
        if (!config.getOptionalValue(TENANT_ENABLED_KEY, Boolean.class).orElse(true)) {
            return;
        }

        String claim = config.getOptionalValue(PRINCIPAL_CLAIM_KEY, String.class)
            .orElse("");
        if (!EXPECTED_PRINCIPAL_CLAIM.equals(claim)) {
            throw new IllegalStateException(
                "'" + PRINCIPAL_CLAIM_KEY + "' is '" + claim + "' and must be '"
                    + EXPECTED_PRINCIPAL_CLAIM + "'. The principal is this service's "
                    + "authorship record and the owner of every private entry; resolved "
                    + "from any other claim it becomes an identifier that can change, and "
                    + "an author that changes stops matching the entries it wrote — "
                    + "silently, because the write still succeeds and the read simply "
                    + "returns nothing.");
        }

        Optional<String> audience = config.getOptionalValue(AUDIENCE_KEY, String.class);
        if (audience.isEmpty() || audience.get().isBlank()) {
            throw new IllegalStateException(
                "'" + AUDIENCE_KEY + "' is not set, so no audience would be validated and "
                    + "a token minted for any other resource would be accepted here. "
                    + "ADR-0034 gives the platform one audience and has every recipient "
                    + "validate it against the identifier its own installation "
                    + "configures. Set MEMORY_OIDC_AUDIENCE.");
        }
    }
}
