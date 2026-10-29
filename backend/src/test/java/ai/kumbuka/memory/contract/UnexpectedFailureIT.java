/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
 * A failure nobody foresaw, forced on both surfaces, answered as
 * {@code UNEXPECTED_FAILURE} 500 with {@code data.reference}, a fresh UUID
 * that stands in the log beside it (C8.3).
 *
 * <p>The log line carries the reference, the call, and the class names of the
 * failure and its causes — never a message of any of them, never a stack
 * frame, and never the exception object (DEC-0051, C13.2). A message is text
 * nobody in the service wrote; a database names the row it rejected, and the
 * row holds the content. So each forced failure carries a marker in its
 * message, and where it has a cause, a second one there, and neither may be
 * found in what the log received.
 *
 * <p>RED STATE, observed 2026-10-29 against the tree before the REST
 * catch-all: the withdrawal that fails below the generic surface answered the
 * framework's bare 500 without the envelope, and
 * {@link #over_rest_an_unexpected_failure_answers_500_with_a_reference} turned
 * red on its status body; the MCP cases turned red on {@code data.reference},
 * which was {@code data.report}, and on the stack frames in the log line.
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
            Contract.patternOf("UNEXPECTED_FAILURE"), Map.of("call", withdraw));
        assertThat(refusal.text())
            .as("the address the caller sent is not repeated (DEC-0042)")
            .doesNotContain(ADDRESS);
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
    void a_call_without_an_address_answers_the_same_form_and_names_no_scope() {
        String query = Contract.toolFor("query");
        Mcp.Result refusal = Mcp.call(query, args("scope", SCOPE));

        String reference = assertUnexpected(refusal, query,
            Contract.patternOf("UNEXPECTED_FAILURE"), Map.of("call", query));
        assertThat(refusal.message())
            .as("the scope the caller sent is not repeated (DEC-0042)")
            .doesNotContain(SCOPE);
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
            Contract.patternOf("UNEXPECTED_FAILURE"), Map.of("call", update));
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

    /**
     * Over REST the same failure answers 500 in the envelope, naming the call
     * as the REST surface names it, with the reference that stands in the
     * log once (C8.3).
     */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void over_rest_an_unexpected_failure_answers_500_with_a_reference() {
        String token = SurfaceFixture.call().get(SurfaceFixture.API + "/" + SCOPE
            + "/convention/branch-names").header("ETag");

        io.restassured.response.Response answer = SurfaceFixture.call()
            .header("If-Match", token)
            .post(SurfaceFixture.API + "/" + SCOPE + "/convention/branch-names:withdraw");

        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(500);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(answer.jsonPath().getString("data.attempted")).isEqualTo("withdraw");
        String reference = answer.jsonPath().getString("data.reference");
        assertThat(reference).matches(UUID_FORM);
        assertThat(answer.asString())
            .doesNotContain(FailingWithdrawal.MESSAGE_MARKER)
            .doesNotContain(FailingWithdrawal.CAUSE_MARKER);
        assertThat(logged.stream().filter(line -> rendered(line).contains(reference)))
            .as("one line carries the reference").hasSize(1)
            .allSatisfy(line -> assertShape(line, "memory_withdraw", List.of(
                IllegalStateException.class, IllegalArgumentException.class),
                FailingWithdrawal.MESSAGE_MARKER, FailingWithdrawal.CAUSE_MARKER));

        io.restassured.response.Response assistant = SurfaceFixture.call()
            .header("Kumbuka-Surface", "assistant").header("If-Match", token)
            .post(SurfaceFixture.API + "/" + SCOPE + "/convention/branch-names:withdraw");
        assertThat(assistant.statusCode()).isEqualTo(500);
        assertThat(assistant.jsonPath().getString("data.attempted"))
            .as("with the header the call is named as the assistant surface names it")
            .isEqualTo("memory_withdraw");
    }

    /** A route the framework does not serve is the framework's answer, in the envelope. */
    @Test
    @TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT)
    void a_route_nobody_serves_is_not_found_and_not_a_failure() {
        io.restassured.response.Response answer = SurfaceFixture.call()
            .get(SurfaceFixture.API + "/" + SCOPE + "/convention/branch-names/more");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
        assertThat(logged).as("nothing is logged as a failure").isEmpty();
    }

    private static final String UUID_FORM =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static String assertUnexpected(Mcp.Result refusal, String tool, String pattern,
                                           Map<String, String> values) {
        assertThat(refusal.isError()).as("%s", refusal).isTrue();
        assertThat(refusal.reason()).isEqualTo("UNEXPECTED_FAILURE");
        Matcher message = Contract.message(pattern, values).matcher(refusal.message());
        assertThat(message.matches())
            .as("the message is the contract's pattern: %s", refusal.message())
            .isTrue();
        assertThat(refusal.string("data.attempted")).isEqualTo(tool);
        // The reference travels under data.reference, which the message points
        // at: a reference is no placeholder a text may take (C8.3).
        String reference = refusal.string("data.reference");
        assertThat(reference).as("the reference to report").matches(UUID_FORM);
        return reference;
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
        assertThat(text).contains(call)
            .as("the class names say what failed; no stack frame stands in the line")
            .doesNotContain("\tat ");
        assertThat(text)
            .as("the class names of the failure and of each cause, outermost first")
            .contains(String.join(" <- ", chain.stream().map(Class::getName).toList()));
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
