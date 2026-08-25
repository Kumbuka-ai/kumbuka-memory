package ai.kumbuka.memory.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of an entry: {@code (logical_id, version)}.
 *
 * <p>There is no surrogate row id, and that is the model rather than an
 * economy. {@code logical_id} is what an entry IS across its versions, and
 * {@code version} is a coordinate within it — so a reference to an entry is a
 * logical id, and a reference to one particular state of it carries the
 * version too. A surrogate key would have made "which row" and "which entry"
 * the same question, and they are not: this edition edits the head in place
 * and holds one version, while the shape is the one a copy-on-write edition
 * appends further versions against, against an unchanged identity.
 */
public class MemoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    public UUID logicalId;
    public int version;

    public MemoryId() {
    }

    public MemoryId(UUID logicalId, int version) {
        this.logicalId = logicalId;
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoryId other)) {
            return false;
        }
        return version == other.version && Objects.equals(logicalId, other.logicalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalId, version);
    }
}
