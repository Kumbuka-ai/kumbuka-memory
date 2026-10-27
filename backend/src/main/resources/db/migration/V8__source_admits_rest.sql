-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V8: the two channel checks admit `rest`.
--
-- N-1: an image before this one writes only `console`, `mcp`, `system` or
-- `import`, which both checks still admit, and it reads no constraint name,
-- so it runs on unchanged against this schema.
--
-- WHY
--
-- `source` and `updated_source` name the channel an entry was written
-- through, derived on the server from the write path and never sent by a
-- caller. The service has two channels of its own, MCP and REST, and the
-- checks of V1 admit only the first of them. The derivation that writes
-- `rest` comes with a later release; this one only widens what the column
-- may hold, so that the release which writes it meets a schema that is
-- already there (expand before use).
--
-- WHY UNDER NEW NAMES
--
-- A check is widened by dropping it and adding its successor; nothing
-- alters a check in place. The successor is added under the name the
-- naming rule gives it (C18.3), `ck_<table>_<what>`, rather than under the
-- old name only for V9 to rename it again. One statement per table, so the
-- table is locked and scanned once.
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- Both checks are wider than before: every row they admitted they admit
-- still. No column is touched.
-- ===========================================================================

ALTER TABLE memory.memory
    DROP CONSTRAINT memory_source_check,
    ADD CONSTRAINT ck_memory_source
        CHECK (source IN ('console','mcp','system','import','rest')),
    DROP CONSTRAINT memory_updated_source_check,
    ADD CONSTRAINT ck_memory_updated_source
        CHECK (updated_source IN ('console','mcp','system','import','rest'));
