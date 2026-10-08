package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.adapter.mcp.McpTools;
import ai.kumbuka.memory.domain.MemoryException;
import ai.kumbuka.memory.domain.NextStep;
import ai.kumbuka.memory.surface.AssistantVerb;
import ai.kumbuka.memory.surface.CallException;
import ai.kumbuka.memory.surface.ReasonCatalogue;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import ai.kumbuka.memory.surface.SurfaceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration against the contract, with no service running.
 *
 * <p>Every expectation is read from the contract copy. The declaration is
 * only ever the thing measured: comparing it with itself would pass whatever
 * it said.
 */
class DeclarationConformanceTest {

    // ======================================================================
    // The tool list
    // ======================================================================

    @Test
    void the_tool_list_is_exactly_the_six_tools_of_section_5() {
        assertThat(toolNames())
            .as("a tool the contract does not name is an addition nobody agreed to, and a "
                + "tool it names and the surface lacks is an omission no caller can notice")
            .containsExactlyElementsOf(Contract.tools());
        assertThat(Contract.tools())
            .as("section 5 names six")
            .hasSize(6);
    }

    @Test
    void every_description_is_the_contract_s_normative_text() {
        Map<String, String> expected = Contract.descriptions();
        for (McpTools.Tool tool : McpTools.declared()) {
            assertThat(tool.description())
                .as("the description of %s is normative text, compared character for "
                    + "character with the contract's lines joined by one space", tool.name())
                .isEqualTo(expected.get(tool.name()));
        }
    }

    // ======================================================================
    // The schemas
    // ======================================================================

    @Test
    void every_schema_is_closed_at_every_level() {
        for (McpTools.Tool tool : McpTools.declared()) {
            List<String> open = new ArrayList<>();
            collectOpenObjects(tool.name(), tool.inputSchema(), open);
            assertThat(open)
                .as("an object schema without additionalProperties: false admits an argument "
                    + "the call does not declare, and a caller that sends one is told nothing")
                .isEmpty();
        }
    }

    @Test
    void every_schema_carries_exactly_the_arguments_of_section_5() {
        for (McpTools.Tool tool : McpTools.declared()) {
            Contract.Arguments expected = Contract.argumentsOf(tool.name());
            Map<String, Object> schema = tool.inputSchema();

            Map<String, Boolean> top = new LinkedHashMap<>(expected.top());
            if (!expected.fields().isEmpty()) {
                top.put(AssistantVerb.FIELDS, true);
            }
            assertThat(argumentsOf(schema))
                .as("%s's top-level arguments, and which of them are required. Those that "
                    + "choose the target are top-level; everything written is under fields",
                    tool.name())
                .containsExactlyEntriesOf(top);

            if (expected.fields().isEmpty()) {
                continue;
            }
            assertThat(argumentsOf(objectAt(schema, AssistantVerb.FIELDS)))
                .as("%s's arguments under fields", tool.name())
                .containsExactlyEntriesOf(expected.fields());
        }
    }

    // ======================================================================
    // The reason catalogue
    // ======================================================================

    @Test
    void the_catalogue_declares_every_reason_of_section_4_4_and_nothing_else() {
        List<String> fromContract = new ArrayList<>(Contract.surfaceReasons());
        fromContract.addAll(Contract.genericOnlyReasons());
        assertThat(ReasonCatalogue.byCode().keySet())
            .as("a reason in neither list cannot be returned, and one in a list must be "
                + "declared")
            .containsExactlyInAnyOrderElementsOf(fromContract);
    }

    @Test
    void the_catalogue_covers_every_reason_the_service_can_raise() {
        List<String> raisable = new ArrayList<>();
        Arrays.stream(MemoryException.Reason.values()).map(Enum::name).forEach(raisable::add);
        Arrays.stream(SurfaceException.Reason.values()).map(Enum::name).forEach(raisable::add);
        Arrays.stream(CallException.Reason.values()).map(Enum::name).forEach(raisable::add);
        for (String raised : raisable) {
            assertThat(ReasonCatalogue.byCode())
                .as("%s can be raised, so it is declared, under its own code or the one it "
                    + "is collapsed into", raised)
                .containsKey(ReasonCatalogue.wireCode(raised));
        }
        assertThat(Arrays.stream(CallException.Reason.values()).map(Enum::name).toList())
            .as("the four reasons this surface adds")
            .containsExactlyInAnyOrder("ARGUMENT_UNKNOWN", "ARGUMENT_MISSING",
                "ARGUMENT_INVALID", "UNEXPECTED_FAILURE");
    }

    @Test
    void the_generic_surface_s_reasons_are_marked_as_its_own() {
        for (String code : Contract.genericOnlyReasons()) {
            assertThat(ReasonCatalogue.byCode().get(code).reach())
                .as("%s is raised on the generic surface only", code)
                .isEqualTo(ReasonCatalogue.Reach.GENERIC);
        }
        for (String code : Contract.surfaceReasons()) {
            assertThat(ReasonCatalogue.byCode().get(code).reach())
                .as("%s is a refusal of the assistant surface", code)
                .isEqualTo(ReasonCatalogue.Reach.BOTH);
        }
    }

    @Test
    void the_remedy_names_the_steps_the_contract_names() {
        for (Map.Entry<String, String[]> row : Contract.refusalRows().entrySet()) {
            String remedy = row.getValue()[1];
            List<String> expected = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("`<([a-z]+)>`").matcher(remedy);
            while (m.find()) {
                expected.add(Contract.toolFor(m.group(1)));
            }
            assertThat(ReasonCatalogue.byCode().get(row.getKey()).steps().stream()
                    .map(AssistantVerb::call).toList())
                .as("the calls %s's remedy names, which data.next lists", row.getKey())
                .containsExactlyElementsOf(expected);
        }
    }

    // ======================================================================
    // The next steps
    // ======================================================================

    @Test
    void the_next_steps_on_an_entry_are_the_table_s_with_the_contract_s_texts() {
        Map<String, String> does = Contract.doesOnAnEntry();
        assertThat(callsOf(SurfaceDeclaration.nextFor(Views.entry(true))))
            .as("a caller who may write")
            .containsExactlyElementsOf(Contract.nextOnAnEntry(true));
        assertThat(callsOf(SurfaceDeclaration.nextFor(Views.entry(false))))
            .as("a caller who may only read")
            .containsExactlyElementsOf(Contract.nextOnAnEntry(false));
        for (NextStep step : SurfaceDeclaration.nextFor(Views.entry(true))) {
            assertThat(step.does())
                .as("what %s does, as section 3 words it", step.call())
                .isEqualTo(does.get(step.call()));
        }
    }

    @Test
    void the_next_step_after_a_withdrawal_is_the_table_s_with_the_contract_s_text() {
        for (boolean released : List.of(true, false)) {
            List<NextStep> next = SurfaceDeclaration.nextAfter(released);
            assertThat(callsOf(next))
                .as("after a withdrawal that %s the address",
                    released ? "released" : "kept")
                .containsExactlyElementsOf(Contract.nextAfterWithdrawal(released));
            assertThat(next.get(0).does())
                .isEqualTo(Contract.doesAfterWithdrawal(released));
        }
    }

    // ======================================================================
    // Reading schemas
    // ======================================================================

    private static List<String> toolNames() {
        return McpTools.declared().stream().map(McpTools.Tool::name).toList();
    }

    private static List<String> callsOf(List<NextStep> next) {
        return next.stream().map(NextStep::call).toList();
    }

    @SuppressWarnings("unchecked")
    private static void collectOpenObjects(String path, Object schema, List<String> open) {
        if (!(schema instanceof Map<?, ?> map)) {
            return;
        }
        if ("object".equals(map.get("type")) && !Boolean.FALSE.equals(
                map.get("additionalProperties"))) {
            open.add(path);
        }
        Object properties = map.get("properties");
        if (properties instanceof Map<?, ?> props) {
            props.forEach((name, child) -> collectOpenObjects(path + "." + name, child, open));
        }
        collectOpenObjects(path + "[]", map.get("items"), open);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> argumentsOf(Map<String, Object> schema) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");
        Map<String, Boolean> arguments = new LinkedHashMap<>();
        properties.keySet().forEach(name -> arguments.put(name, required.contains(name)));
        return arguments;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectAt(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }
}
