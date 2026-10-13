-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V7: an index for every order a listing is read in, led by the tenant.
--
-- A page is read in key order or in creation order, over a whole scope or
-- narrowed to a selector: four shapes. V6 served two of them, and a plan
-- probe (PageListingPlanIT) asked the planner about the statement the service
-- actually sends, as the service role, on two selectors of 3000 entries and a
-- neighbouring tenant of as many. Measured red on the V6 state:
--
--   * key order over a whole scope: no index in that order, so a sequential
--     scan of the scope and a sort, for every page;
--   * creation order narrowed to a selector: V6's creation index carries no
--     selector, so the walk passed over every entry of the other selectors;
--   * key order narrowed to a selector, walked backward: V6's selector index
--     does not begin with the tenant, so the walk passed over the neighbour's
--     entries first.
--
-- Every statement the service sends carries the tenant twice over (the ORM's
-- tenant filter and the policy of V3), so every index here leads with it.
--
-- The fourth shape, creation order over a whole scope, keeps V6's index.
--
-- WHAT A PAGE ALSO COUNTS
--
-- Every page reports how many entries of the scope carry a key this surface
-- cannot address. That count read the whole scope on every page. The partial
-- index below holds only such entries, which this service never writes, so it
-- is empty in the ordinary case and the count reads nothing. Its predicate is
-- spelled exactly as the statement spells it, which is what lets the planner
-- prove that the one implies the other.
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- Four new indexes, nothing dropped, renamed or narrowed. V6's selector index
-- is superseded by the second one here and is left standing: dropping it is a
-- contraction for a later release, once no image that could prefer it runs.
-- No grant: an index needs none.
-- ===========================================================================

-- Key order over a whole scope.
CREATE INDEX idx_memory_listing_key
    ON memory.memory (tenant_id, scope_id, key);

-- Key order inside one selector.
CREATE INDEX idx_memory_listing_selector_key
    ON memory.memory (tenant_id, scope_id, selector, key);

-- Creation order inside one selector, the key breaking ties.
CREATE INDEX idx_memory_listing_selector_created_key
    ON memory.memory (tenant_id, scope_id, selector, created_at, key);

-- The entries a listing cannot address, for the count every page carries.
CREATE INDEX idx_memory_unaddressable
    ON memory.memory (tenant_id, scope_id)
    WHERE key IS NULL OR position('.' in key) <= 1;
