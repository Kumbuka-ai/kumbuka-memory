/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.surface.AnswerShape;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The members the declaration names for each answer are the members the wire
 * records carry, on both surfaces.
 *
 * <p>{@link AnswerShape} sits in the surface package, which may not reach the
 * payload records; this holds the two together, so that a member added to an
 * answer and left out of the declaration turns red here rather than reaching
 * the router as an answer it was never told about.
 */
class AnswerShapeTest {

    private static final Map<AnswerShape, List<Class<?>>> CARRIED_BY = Map.of(
        AnswerShape.ENTRY, List.of(Payloads.EntryPayload.class,
            AssistantPayloads.AssistantEntryPayload.class),
        AnswerShape.ENTRY_CREATED, List.of(Payloads.EntryPayload.class,
            AssistantPayloads.AssistantEntryPayload.class),
        AnswerShape.ENTRY_LISTING, List.of(Payloads.PagePayload.class,
            AssistantPayloads.AssistantPagePayload.class),
        AnswerShape.WITHDRAWN, List.of(Payloads.WithdrawnPayload.class,
            AssistantPayloads.AssistantWithdrawnPayload.class),
        AnswerShape.DIGEST_VIEW, List.of(Payloads.DigestPayload.class),
        AnswerShape.ENTRY_TAGS, List.of(Payloads.TagsPayload.class,
            AssistantPayloads.AssistantTagsPayload.class));

    @Test
    void every_declared_answer_names_the_members_its_records_carry() {
        assertThat(CARRIED_BY).as("every shape is held against a record")
            .containsOnlyKeys(AnswerShape.values());
        CARRIED_BY.forEach((shape, records) -> records.forEach(carrier ->
            assertThat(shape.members())
                .as("%s against %s", shape, carrier.getSimpleName())
                .containsExactlyElementsOf(wireNames(carrier))));
    }

    /**
     * The names an answer about one entry declares under {@code fields} are
     * the names the one projection carries, {@code tags} among them (V11).
     */
    @Test
    void every_answer_about_one_entry_names_the_fields_the_projection_carries() {
        for (AnswerShape shape : AnswerShape.values()) {
            List<String> expected = shape.members().contains("fields")
                ? wireNames(Payloads.EntryFieldsPayload.class) : List.of();
            assertThat(shape.fields())
                .as("%s declares the fields of its entry", shape)
                .containsExactlyElementsOf(expected);
        }
        assertThat(AnswerShape.ENTRY.fields()).as("the field a read carries the tags in")
            .contains("tags");
    }

    @Test
    void the_check_reports_a_member_left_out() {
        assertThat(List.of("address", "fields", "conflict_token"))
            .as("RED STATE, observed: the declaration of ENTRY without next differs from "
                + "what EntryPayload carries")
            .isNotEqualTo(wireNames(Payloads.EntryPayload.class));
    }

    /** The names a record carries on the wire, read off its accessors. */
    private static List<String> wireNames(Class<?> carrier) {
        return Arrays.stream(carrier.getRecordComponents())
            .map(RecordComponent::getAccessor)
            .map(accessor -> {
                JsonProperty property = accessor.getAnnotation(JsonProperty.class);
                return property == null ? accessor.getName() : property.value();
            })
            .toList();
    }
}
