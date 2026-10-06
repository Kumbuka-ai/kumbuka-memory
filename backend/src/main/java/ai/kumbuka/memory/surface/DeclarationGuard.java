package ai.kumbuka.memory.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Refuses to start with an assistant surface that could not be served as
 * declared — above all with a reason the service can raise and the catalogue
 * does not word (DEC-0043).
 *
 * <p>At start and not at the first refusal: a missing pattern found by a
 * caller is found in production, by somebody who then gets an improvised
 * answer or none. Found here, it is a container that does not come up, with a
 * log line naming the reason it lacks.
 */
@ApplicationScoped
public class DeclarationGuard {

    private static final Logger LOG = Logger.getLogger(DeclarationGuard.class);

    void onStart(@Observes StartupEvent event) {
        SurfaceDeclaration.requireServable(catalogue());
        LOG.infof("assistant surface declared: %d calls, %d reasons",
            SurfaceDeclaration.calls().size(), catalogue().size());
    }

    /**
     * The catalogue the start is checked against.
     *
     * <p>An instance method only so that a start can be observed refusing an
     * incomplete catalogue in a test; the service checks the one it carries.
     */
    protected Map<String, ReasonCatalogue.Reason> catalogue() {
        return ReasonCatalogue.byCode();
    }
}
