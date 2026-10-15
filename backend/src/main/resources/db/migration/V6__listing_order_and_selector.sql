-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V6: what a paged listing reads by.
--
-- A listing is read in one of two orders, by key or by the moment the entry
-- was laid down with the key as second key, and it may be narrowed to a
-- selector. Neither had an index that serves it.
--
-- THE SELECTOR AS A COLUMN
--
-- The selector is the part of the key before its first dot, and it was read as
-- `key LIKE '<selector>.%'`. Two things were wrong with that. A selector may
-- carry `_`, which LIKE reads as any one character, so selector `a_b` listed
-- the entries of `a-b`. And a LIKE prefix on a bound parameter is not
-- something an index on the key reliably serves under the default collation.
-- A generated column turns the narrowing into an equality: it has no pattern
-- characters, and an ordinary btree on (scope_id, selector, key) serves both
-- the narrowing and the key order behind it.
--
-- `GENERATED ALWAYS ... STORED`, so no writer can make the column disagree with
-- the key, whether or not it came through a verb. A key without a dot yields
-- the whole key; such a row is unaddressable and no listing reads it (the
-- ADDRESSABLE predicate in MemoryRepository), so the value is never used.
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- A new column and two new indexes, nothing dropped, renamed or narrowed. The
-- previous image does not map the column, inserts without naming it, and is
-- served exactly as before; Hibernate's `validate` checks the columns an image
-- maps, not the ones it does not. Adding a stored generated column rewrites
-- the table once, which for this table is a matter of its size in rows, not of
-- an outage.
--
-- No grant: the service role's SELECT/INSERT/UPDATE/DELETE on memory.memory is
-- table-wide (V2) and covers a column added afterwards.
-- ===========================================================================

ALTER TABLE memory.memory
    ADD COLUMN selector TEXT GENERATED ALWAYS AS (split_part(key, '.', 1)) STORED;

-- The listing narrowed to a selector, in key order.
CREATE INDEX idx_memory_scope_selector_key ON memory.memory (scope_id, selector, key);

-- The listing in the order the entries were laid down, the key breaking ties.
CREATE INDEX idx_memory_scope_created_key ON memory.memory (tenant_id, scope_id, created_at, key);
