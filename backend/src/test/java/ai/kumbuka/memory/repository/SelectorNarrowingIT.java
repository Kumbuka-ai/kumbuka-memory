/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.memory.repository;

import ai.kumbuka.memory.domain.PageRequest;
import ai.kumbuka.memory.domain.QueryFilter;
import ai.kumbuka.memory.domain.SortKey;
import ai.kumbuka.memory.platform.PlatformFixture;
import ai.kumbuka.memory.surface.SurfaceFixture;
import ai.kumbuka.memory.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A selector narrows to that selector and is never read as a pattern.
 *
 * <h2>Why this is asked of the repository and not of the verb</h2>
 *
 * The selector grammar admits {@code _} and the key grammar does not, so no
 * entry can stand under a selector with an underscore — and the verb, which
 * checks the selector by composing a key from it, refuses {@code a_b} as
 * {@code KEY_MALFORMED} before any statement runs (measured 2026-10-11). The
 * verb cannot show the defect. The statement can: it read the selector as
 * {@code key LIKE 'a_b.%'}, where {@code _} is any one character, and so
 * listed the entries of {@code a-b}. Asked here, with the grammar out of the
 * way, the probe turns red the moment the narrowing is a pattern again.
 *
 * <p>The red run: the narrowing written back as {@code m.key LIKE :prefix}
 * with {@code selector + ".%"} bound, unescaped — the first probe lists
 * {@code a-b.one} and fails. Observed on 2026-10-11.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SelectorNarrowingIT {

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject MemoryRepository entries;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @BeforeEach
    void anEntryUnderAHyphenatedSelector() {
        SurfaceFixture.clearEntries();
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "a-b.one", "decision", "under a hyphen"));
        SurfaceFixture.plant(SurfaceFixture.Planted.shared(SubstrateDatabaseResource.SCOPE_ID,
            "a-bc.two", "decision", "under a longer selector"));
    }

    @Test
    void an_underscore_in_a_selector_is_a_letter_and_not_a_wildcard() {
        assertThat(keysUnder("a_b"))
            .as("'_' is a letter of the selector grammar and LIKE's 'any one character'. "
                + "Read as a pattern, selector a_b listed the entries of a-b")
            .isEmpty();
    }

    @Test
    void a_selector_lists_its_own_entries_and_not_those_of_a_longer_one() {
        assertThat(keysUnder("a-b")).containsExactly("a-b.one");
    }

    private List<String> keysUnder(String selector) {
        PageRequest page = new PageRequest(null, SortKey.ID, SortKey.Direction.ASC,
            PageRequest.DEFAULT_PAGE_SIZE);
        return entries.page(new MemoryRepository.Keyset(SCOPE, selector,
                SubstrateDatabaseResource.PROBE_SUBJECT, new QueryFilter(null, null), page, null))
            .entries().stream().map(m -> m.key).toList();
    }
}
