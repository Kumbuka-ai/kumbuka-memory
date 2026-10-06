package ai.kumbuka.memory.surface;

import java.util.Map;

/**
 * One argument of one call on the assistant surface, as the declaration states it.
 *
 * <p>The input schema, the check that refuses an undeclared argument, the
 * refusal for a missing one and the declaration served at
 * {@code /mcp/declaration} are all read off this record. That is the point of
 * it: a schema assembled in one place and a check written in another would
 * describe two surfaces, and the caller would only ever see one of them.
 *
 * @param name        the name the caller writes
 * @param type        the JSON type: {@code string}, {@code integer} or {@code array}
 * @param required    whether the call is refused without it
 * @param placement   top-level, or under {@code fields}
 * @param description what the value IS, written so that it completes the
 *                    sentence an {@code ARGUMENT_MISSING} refusal begins:
 *                    "memory_read needs address: <description>."
 * @param keywords    further JSON-schema keywords the argument publishes,
 *                    such as an enumeration or bounds; never {@code type} or
 *                    {@code description}
 */
public record Argument(String name, String type, boolean required, Placement placement,
                       String description, Map<String, Object> keywords) {

    public static final String STRING = "string";
    public static final String INTEGER = "integer";
    public static final String ARRAY = "array";

    /** Where an argument sits (DEC-0040). */
    public enum Placement {

        /** Chooses the target, or is a transport artefact such as the conflict token. */
        TOP,

        /** Is written into the entry. */
        FIELDS
    }

    public Argument {
        keywords = Map.copyOf(keywords);
    }

    public static Argument top(String name, String type, boolean required,
                               String description) {
        return new Argument(name, type, required, Placement.TOP, description, Map.of());
    }

    public static Argument top(String name, String type, boolean required,
                               String description, Map<String, Object> keywords) {
        return new Argument(name, type, required, Placement.TOP, description, keywords);
    }

    public static Argument field(String name, boolean required, String description) {
        return new Argument(name, STRING, required, Placement.FIELDS, description, Map.of());
    }

    public static Argument field(String name, boolean required, String description,
                                 Map<String, Object> keywords) {
        return new Argument(name, STRING, required, Placement.FIELDS, description, keywords);
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
