package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.FailingCallerActor;
import ai.kumbuka.memory.surface.FailingWithdrawal;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.surface.UnexpectedFailures;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import static ai.kumbuka.memory.contract.Mcp.args;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failure nobody foresaw, forced on the assistant surface, answered as
 * {@code UNEXPECTED_FAILURE} with a reference that stands in the log beside it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(UnexpectedFailureIT.BrokenEdition.class)
class UnexpectedFailureIT {

    /** An edition whose withdrawal breaks, and an identity that breaks for one subject. */
    public static class BrokenEdition implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingWithdrawal.class, FailingCallerActor.class);
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            // A second application in one test JVM: let the OS choose its port
            // rather than race the previous one for the fixed test port.
            return Map.of("quarkus.http.test-port", "0");
        }
    }

    private static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String KEY = "convention.branch-names";
    private static final String ADDRESS = "memory://" + SCOPE + "/convention/branch-names";

    private final List<LogRecord> logged = new ArrayList<>();
    private Handler handler;
    private Logger watched;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void oneEntryAndAnEar() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            KEY, "convention", "feature/<slug>"));
        watched = Logger.getLogger(UnexpectedFailures.class.getName());
        handler = new Handler() {
            @Override
            public void publish(LogRecord line) {
                logged.add(line);
            }

            @Override
            public void flush() {
                // The list is the sink; nothing is buffered.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        handler.setLevel(Level.ALL);
        watched.addHandler(handler);
    }

    @AfterEach
    void stopListening() {
        watched.removeHandler(handler);
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_call_with_an_address_answers_unexpected_failure_and_the_reference_is_logged() {
        String token = Mcp.call(Contract.toolFor("read"), args("address", ADDRESS))
            .string("conflict_token");
        String withdraw = Contract.toolFor("withdraw");

        Mcp.Result refusal = Mcp.call(withdraw,
            args("address", ADDRESS, "conflict_token", token));

        String reference = assertUnexpected(refusal, withdraw,
            Contract.patternOf("UNEXPECTED_FAILURE"),
            Map.of("call", withdraw, "address", ADDRESS));
        assertLoggedWithTheFailure(reference, FailingWithdrawal.DEFECT);
        assertThat(refusal.text()).doesNotContain(FailingWithdrawal.DEFECT);

        assertThat(Mcp.call(Contract.toolFor("read"), args("address", ADDRESS)).isError())
            .as("Nothing was changed: the entry still stands")
            .isFalse();
    }

    @Test
    @TestSecurity(user = FailingCallerActor.FAILING_SUBJECT)
    void a_call_without_an_address_answers_the_form_that_names_the_scope() {
        String query = Contract.toolFor("query");
        Mcp.Result refusal = Mcp.call(query, args("scope", SCOPE));

        String reference = assertUnexpected(refusal, query,
            Contract.unexpectedFailureWithoutAddress(), Map.of("call", query, "scope", SCOPE));
        assertLoggedWithTheFailure(reference, "the identity derivation broke");
    }

    private static String assertUnexpected(Mcp.Result refusal, String tool, String pattern,
                                           Map<String, String> values) {
        assertThat(refusal.isError()).as("%s", refusal).isTrue();
        assertThat(refusal.reason()).isEqualTo("UNEXPECTED_FAILURE");
        Matcher message = Contract.message(pattern, values, "ref").matcher(refusal.message());
        assertThat(message.matches())
            .as("the message is the contract's pattern: %s", refusal.message())
            .isTrue();
        assertThat(refusal.string("data.attempted")).isEqualTo(tool);
        return message.group(1);
    }

    private void assertLoggedWithTheFailure(String reference, String defect) {
        assertThat(logged)
            .as("the reference the caller is told to report stands in the log, with the "
                + "failure it refers to")
            .anySatisfy(line -> {
                assertThat(line.getLevel().intValue()).isGreaterThanOrEqualTo(
                    Level.SEVERE.intValue());
                assertThat(line.getMessage() + " " + Arrays.toString(line.getParameters()))
                    .contains(reference);
                assertThat(line.getThrown()).isNotNull();
                assertThat(line.getThrown().getMessage()).isEqualTo(defect);
            });
    }
}
