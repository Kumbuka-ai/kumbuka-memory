package ai.kumbuka.memory.adapter.mcp;

import ai.kumbuka.memory.surface.Argument;
import ai.kumbuka.memory.surface.AssistantVerb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool list, as {@code tools/list} answers it, derived from the declaration.
 *
 * <p>Nothing here is written by hand: the names, the descriptions and every
 * schema come from {@link AssistantVerb}. A tool list kept beside the
 * declaration would be a second statement of the surface, and the two would
 * disagree the first time one of them changed.
 *
 * <h2>Closed at every level</h2>
 *
 * Every object schema carries {@code additionalProperties: false}, the
 * {@code fields} object included. One closed level is as much use as none:
 * the argument a caller misspells is as likely to sit inside {@code fields}
 * as beside it, and a schema that admits it tells the caller it was read.
 */
public final class McpTools {

    private McpTools() {
    }

    /** One tool as the protocol lists it. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    public static List<Tool> declared() {
        return Arrays.stream(AssistantVerb.values())
            .map(verb -> new Tool(verb.call(), verb.description(), schemaOf(verb)))
            .toList();
    }

    static Map<String, Object> schemaOf(AssistantVerb verb) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Argument argument : verb.topArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        if (verb.writesFields()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            List<String> requiredFields = new ArrayList<>();
            for (Argument argument : verb.fieldArguments()) {
                fields.put(argument.name(), property(argument));
                if (argument.required()) {
                    requiredFields.add(argument.name());
                }
            }
            Map<String, Object> schema = closedObject(fields, requiredFields);
            schema.put("description", AssistantVerb.FIELDS_DESCRIPTION);
            properties.put(AssistantVerb.FIELDS, schema);
            required.add(AssistantVerb.FIELDS);
        }
        return closedObject(properties, required);
    }

    private static Map<String, Object> property(Argument argument) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", argument.type());
        property.put("description", argument.description());
        property.putAll(argument.keywords());
        return property;
    }

    private static Map<String, Object> closedObject(Map<String, Object> properties,
                                                    List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }
}
