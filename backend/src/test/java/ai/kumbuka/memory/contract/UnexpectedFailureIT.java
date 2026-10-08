package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.adapter.mcp.FailingCommit;
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

import java.io.PrintWriter;
import java.io.StringWriter;
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
 *
 * <p>The log line carries the reference, the call, and the types and stack
 * frames of the failure and its causes — never a message of any of them. A
 * message is text nobody in the service wrote; a database names the row it
 * rejected, and the row holds the content. So each forced failure carries a
 * marker in its message, and where it has a cause, a second one there, and
 * neither may be found in what the log received.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(UnexpectedFailureIT.BrokenEdition.class)
class UnexpectedFailureIT {

    /**
     * An edition whose withdrawal breaks, an identity that breaks for one
     * subject, and a commit that breaks when armed.
     */
    public static class BrokenEdition implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingWithdrawal.class, FailingCallerActor.class, FailingCommit.class);
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
    private static final String CONTENT = "feature/<slug>";
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
            KEY, "convention", CONTENT));
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
        FailingCommit.disarm();
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
        assertLoggedByShape(reference, withdraw, List.of(IllegalStateException.class,
            IllegalArgumentException.class), FailingWithdrawal.MESSAGE_MARKER,
            FailingWithdrawal.CAUSE_MARKER);
        assertThat(refusal.text())
            .doesNotContain(FailingWithdrawal.MESSAGE_MARKER)
            .doesNotContain(FailingWithdrawal.CAUSE_MARKER);

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
        assertLoggedByShape(reference, query, List.of(IllegalStateException.class),
            FailingCallerActor.DEFECT);
    }

    /**
     * A refusal that concerns an entry reads the entry a second time, to tell
     * the caller its state. A failure of that second read is not an answer
     * about visibility: it is logged, and the caller still gets the refusal of
     * the call, without a state nobody could read.
     */
    @Test
    @TestSecurity(user = FailingCallerActor.FAILING_SUBJECT)
    void a_failure_of_the_second_read_is_logged_and_the_refusal_still_answers() {
        String update = Contract.toolFor("update");
        Mcp.Result refusal = Mcp.call(update, args("address", ADDRESS,
            "conflict_token", "any", "colour", "red"));

        assertThat(refusal.isError()).as("%s", refusal).isTrue();
        assertThat(refusal.reason())
            .as("the typed refusal of the call, not the failure of the read behind it")
            .isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refusal.map("data"))
            .as("no state and no next of an entry that could not be read")
            .doesNotContainKeys("state", "next");

        assertThat(logged)
            .as("the failure of the second read is logged rather than taken for 'not visible'")
            .anySatisfy(line -> assertShape(line, update, List.of(IllegalStateException.class),
                FailingCallerActor.DEFECT));
    }

    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_call_whose_commit_fails_answers_unexpected_failure_and_changes_nothing() {
        String read = Contract.toolFor("read");
        String update = Contract.toolFor("update");
        String token = Mcp.call(read, args("address", ADDRESS)).string("conflict_token");

        FailingCommit.arm();
        Mcp.Result refusal;
        try {
            refusal = Mcp.call(update, args("address", ADDRESS, "conflict_token", token,
                "fields", Map.of("content", "changed by a call that never committed")));
        } finally {
            FailingCommit.disarm();
        }

        String reference = assertUnexpected(refusal, update,
            Contract.patternOf("UNEXPECTED_FAILURE"),
            Map.of("call", update, "address", ADDRESS));
        assertThat(logged)
            .as("the reference stands in the log, without the message of the failure")
            .anySatisfy(line -> {
                assertThat(rendered(line)).contains(reference);
                assertThat(rendered(line)).doesNotContain(FailingCommit.MARKER);
            });

        Mcp.Result after = Mcp.call(read, args("address", ADDRESS));
        assertThat(after.string("fields.content"))
            .as("Nothing was changed: the content is the one planted")
            .isEqualTo(CONTENT);
        assertThat(after.string("conflict_token"))
            .as("and the entry is the version read before the call")
            .isEqualTo(token);
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

    private void assertLoggedByShape(String reference, String call,
                                     List<Class<? extends Throwable>> chain,
                                     String... markers) {
        assertThat(logged)
            .as("the reference the caller is told to report stands in the log, with the "
                + "shape of the failure it refers to and none of its messages")
            .anySatisfy(line -> {
                assertThat(rendered(line)).contains(reference);
                assertShape(line, call, chain, markers);
            });
    }

    private static void assertShape(LogRecord line, String call,
                                    List<Class<? extends Throwable>> chain, String... markers) {
        assertThat(line.getLevel().intValue()).isGreaterThanOrEqualTo(Level.SEVERE.intValue());
        assertThat(line.getThrown())
            .as("the failure is not handed to the logger, which would print its messages")
            .isNull();
        String text = rendered(line);
        assertThat(text).contains(call).contains("\tat ");
        for (int i = 0; i < chain.size(); i++) {
            assertThat(text)
                .as("the type of the failure and of each cause")
                .contains((i == 0 ? "\n" : "\ncaused by ") + chain.get(i).getName());
        }
        for (String marker : markers) {
            assertThat(text)
                .as("no message of the failure or of a cause reaches the log")
                .doesNotContain(marker);
        }
    }

    /**
     * Everything a handler could print from a record: the format, its
     * parameters, and a thrown failure as a stack trace prints it — message
     * and causes included.
     */
    private static String rendered(LogRecord line) {
        StringBuilder all = new StringBuilder(String.valueOf(line.getMessage()))
            .append(' ').append(Arrays.toString(line.getParameters()));
        if (line.getThrown() != null) {
            StringWriter trace = new StringWriter();
            line.getThrown().printStackTrace(new PrintWriter(trace));
            all.append(' ').append(trace);
        }
        return all.toString();
    }
}
