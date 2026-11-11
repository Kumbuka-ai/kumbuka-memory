/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The identity of an assignment: the entry's durable key and the tag. */
public class EntryTagId implements Serializable {

    private static final long serialVersionUID = 1L;

    public UUID entryLogicalId;
    public int entryVersion;
    public UUID tagId;

    @SuppressWarnings("NullAway.Init") // Hibernate sets the fields by field access after this
    public EntryTagId() {
    }

    public EntryTagId(UUID entryLogicalId, int entryVersion, UUID tagId) {
        this.entryLogicalId = entryLogicalId;
        this.entryVersion = entryVersion;
        this.tagId = tagId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntryTagId other)) {
            return false;
        }
        return entryVersion == other.entryVersion
            && Objects.equals(entryLogicalId, other.entryLogicalId)
            && Objects.equals(tagId, other.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryLogicalId, entryVersion, tagId);
    }
}
