/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.adapter.payload;

import ai.kumbuka.memory.domain.EntryView;
import ai.kumbuka.memory.domain.MemoryService;
import ai.kumbuka.memory.domain.Page;
import ai.kumbuka.memory.surface.SurfaceDeclaration;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The answers of the assistant surface.
 *
 * <p>The same shapes the generic surface answers — {@code address},
 * {@code fields}, the conflict token beside them, the next steps beside both
 * (DEC-0040) — with one difference, and it is the reason these are records of
 * their own rather than the generic ones: {@code next} names calls of THIS
 * surface. The generic answer offers {@code read memory://…}; an assistant
 * that called that would be calling something this surface does not carry.
 *
 * <p>The fields of an entry go through {@link Payloads.EntryFields#of}, the
 * one projection of a stored entry, so the two surfaces cannot come to answer
 * different field sets for the same entry.
 */
public final class AssistantPayloads {

    private AssistantPayloads() {
    }

    /** An answer about one entry, and one entry of a listing. */
    public record EntryAnswer(
        @JsonProperty("address") String address,
        @JsonProperty("fields") Payloads.EntryFields fields,
        @JsonProperty("conflict_token") String conflictToken,
        @JsonProperty("next") List<Payloads.NextPayload> next) {

        public static EntryAnswer of(EntryView entry) {
            return new EntryAnswer(entry.address().canonical(), Payloads.EntryFields.of(entry),
                entry.conflictToken(),
                Payloads.NextPayload.of(SurfaceDeclaration.nextFor(entry)));
        }
    }

    /**
     * A page of entries, with every reason it might not be the whole set.
     *
     * <p>The same members as on the generic surface
     * ({@link Payloads.PageResponse}): {@code objects}, {@code next_cursor}
     * absent on the last page, and {@code unaddressable}.
     */
    public record PageAnswer(
        @JsonProperty("objects") List<EntryAnswer> objects,
        @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.NON_NULL)
        @Nullable String nextCursor,
        @JsonProperty("unaddressable") long unaddressable) {

        public static PageAnswer of(Page page) {
            return new PageAnswer(
                page.objects().stream().map(EntryAnswer::of).toList(),
                page.nextCursor(), page.unaddressable());
        }
    }

    /** The answer to a withdrawal: what happened, and whether the address is free. */
    public record WithdrawalAnswer(
        @JsonProperty("address") String address,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("address_released") boolean addressReleased,
        @JsonProperty("next") List<Payloads.NextPayload> next) {

        public static WithdrawalAnswer of(MemoryService.Withdrawn withdrawn) {
            return new WithdrawalAnswer(withdrawn.address().canonical(),
                withdrawn.outcome().name().toLowerCase(Locale.ROOT),
                withdrawn.addressReleased(),
                Payloads.NextPayload.of(SurfaceDeclaration.nextAfter(withdrawn.addressReleased())));
        }
    }
}
