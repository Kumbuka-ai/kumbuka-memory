-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V9: indexes and checks named after their table and their kind.
--
-- N-1: no statement of any image reads an index or a constraint by its name,
-- so an image before this one runs on unchanged against the renamed schema.
--
-- WHY
--
-- The naming rule (C18.3) is `idx_<table>_…`, `uq_<table>_…`, `ck_<table>_…`
-- and `fk_<table>_<target>…`, so that a refusal of the database names the
-- table and the kind of rule it broke. V1 and V4 named two indexes `ix_` and
-- nine checks without any prefix; V8 already renamed the two channel checks
-- it replaced. The names those migrations gave stand in MigrationNamingTest's
-- APPLIED list, because a migration once run is never edited.
--
-- WHAT WAS CHECKED BEFORE RENAMING
--
-- That no code reads any of these names. In the service's main code the old
-- names stood in two comments and in the text of one exception message,
-- which this release rewrites; no statement, mapping or handler matches a
-- constraint or an index by one of them. The one constraint name the
-- service does read, to tell an occupied key from any other refused insert,
-- is `uq_memory_shared_key` / `uq_memory_private_key`, which already follow
-- the rule and are not renamed.
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- A rename changes no definition: every check holds what it held, every
-- index serves what it served. Nothing is dropped and nothing is narrowed.
-- ===========================================================================

ALTER TABLE memory.memory RENAME CONSTRAINT memory_content_len TO ck_memory_content_len;
ALTER TABLE memory.memory RENAME CONSTRAINT memory_key_format TO ck_memory_key_format;
ALTER TABLE memory.memory RENAME CONSTRAINT memory_lock_check TO ck_memory_lock;
ALTER TABLE memory.memory
    RENAME CONSTRAINT memory_reference_no_credentials TO ck_memory_reference_no_credentials;
ALTER TABLE memory.memory RENAME CONSTRAINT memory_state_check TO ck_memory_state;
ALTER TABLE memory.memory RENAME CONSTRAINT memory_type_check TO ck_memory_type;

ALTER TABLE memory.content_relation
    RENAME CONSTRAINT content_relation_kind_check TO ck_content_relation_kind;

ALTER TABLE memory.digest_preference
    RENAME CONSTRAINT digest_preference_types_known TO ck_digest_preference_types_known;
ALTER TABLE memory.digest_preference
    RENAME CONSTRAINT digest_preference_types_present TO ck_digest_preference_types_present;

ALTER INDEX memory.ix_content_relation_from RENAME TO idx_content_relation_from;
ALTER INDEX memory.ix_content_relation_to RENAME TO idx_content_relation_to;
