package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.surface.CatalogueWithAGap;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real start of the application, refused because the reason catalogue
 * leaves out a reason the service can raise (DEC-0043).
 *
 * <p>The application is started whole — database, migrations, every other
 * start check — with one difference: the catalogue the start is checked
 * against lacks one entry. It must not come up, and what it says on the way
 * down must name the reason, so that the failure observed here is this one
 * and not a database that was not there.
 */
@QuarkusMainTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(CatalogueStartIT.AGapInTheCatalogue.class)
class CatalogueStartIT {

    /** The service as it is, but checked against a catalogue with a gap. */
    public static class AGapInTheCatalogue implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(CatalogueWithAGap.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.port", "0", "quarkus.http.test-port", "0");
        }
    }

    @Test
    void the_application_does_not_start_and_names_the_undeclared_reason(
            QuarkusMainLauncher launcher) {
        LaunchResult started = launcher.launch();

        String said = started.getOutput() + "\n" + started.getErrorOutput();
        assertThat(started.exitCode())
            .as("a reason the service can raise is not in the catalogue, so the "
                + "application does not start. It said:%n%s", said)
            .isNotZero();
        assertThat(said)
            .as("and it says which reason, so that this failure is told apart from any "
                + "other a start can have")
            .contains("the reason catalogue does not declare")
            .contains(CatalogueWithAGap.LEFT_OUT);
    }
}
