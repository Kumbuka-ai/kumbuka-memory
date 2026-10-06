package ai.kumbuka.memory.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The start check, handed a catalogue that leaves one raisable reason out.
 *
 * <p>Enabled only by the test profile that selects it, so that a real start
 * of the application can be observed refusing. Everything else about the
 * start is the service's own.
 */
@Alternative
@ApplicationScoped
public class CatalogueWithAGap extends DeclarationGuard {

    /** A reason the domain raises on every create with too long a text. */
    public static final String LEFT_OUT = "CONTENT_OVERSIZE";

    @Override
    void onStart(@Observes StartupEvent event) {
        super.onStart(event);
    }

    @Override
    protected Map<String, ReasonCatalogue.Reason> catalogue() {
        Map<String, ReasonCatalogue.Reason> gap = new LinkedHashMap<>(super.catalogue());
        gap.remove(LEFT_OUT);
        return gap;
    }
}
