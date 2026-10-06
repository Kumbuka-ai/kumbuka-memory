package ai.kumbuka.memory.contract;

import ai.kumbuka.memory.domain.EntryAddress;
import ai.kumbuka.memory.domain.EntryView;

import java.time.Instant;

/** Projections of an entry for probes that need one without a database. */
final class Views {

    private Views() {
    }

    /** An entry in force, projected for a caller who may or may not write. */
    static EntryView entry(boolean writable) {
        Instant at = Instant.parse("2026-10-06T00:00:00Z");
        return new EntryView(new EntryAddress("kumbuka", "convention", "branch-names"),
            "kumbuka", "convention.branch-names", "convention", "a text", null, "published",
            false, at, at, at.toString(), writable);
    }
}
