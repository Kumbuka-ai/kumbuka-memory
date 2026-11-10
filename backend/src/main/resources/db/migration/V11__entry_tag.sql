-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V11: which tags an entry carries.
--
-- N-1: an image before this one neither reads nor writes this table, and
-- nothing else in the schema changes, so it runs on unchanged. Its one
-- statement that meets the table is a withdrawal, which deletes an entry;
-- the foreign key below removes that entry's rows here with it, without the
-- old image knowing the table exists.
--
-- WHY
--
-- Tags are the platform's grouping vocabulary, declared per scope in
-- platform.tag and published to this service through the view
-- platform.tag_access. The vocabulary is the platform's; which entry carries
-- which tag is this service's, and lives here. A tag is held by its id and
-- never by its token: the token is resolved through the view at every read,
-- in the scope of the entry, so that a tag is only ever answered where the
-- caller may see both the entry and the scope it is declared in.
--
-- WHY THE ENTRY IS HELD BY (logical_id, version), WITH A FOREIGN KEY
--
-- The entry's durable key is the pair the primary key of memory.memory is
-- made of. In this edition an edit mutates the head in place and version
-- stays 1, so the pair names the entry for its whole life. A foreign key to
-- logical_id alone is not possible, because logical_id is not unique on its
-- own, and making it unique would narrow an existing table, which this
-- migration does not do (DEC-0018).
--
-- ON DELETE CASCADE because this edition withdraws by deleting the row
-- (HardDeleteWithdrawal): the tags an entry carried go with it, and nothing
-- here outlives what it describes. Unlike content_relation, an assignment
-- has no meaning without its entry.
--
-- WHY THE TAG HAS NO FOREIGN KEY
--
-- platform.tag is another service's table, and a hard reference across the
-- schema boundary is forbidden (V1). tag_id is stored and resolved at read
-- time through platform.tag_access, as scope_id is through
-- platform.scope_access.
--
-- WHO AND WHEN, AND NOTHING THAT COUNTS
--
-- attached_by is the subject that attached the tag and attached_at the
-- moment: who did what, when. No column counts or aggregates anybody's
-- actions (constraint.audit-no-activity-monitoring).
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- One new table with its index, its policy and its grant. No column,
-- constraint, index or policy of an existing table is touched.
-- ===========================================================================

CREATE TABLE memory.entry_tag (
    tenant_id         UUID         NOT NULL,

    -- the entry, by its durable key
    entry_logical_id  UUID         NOT NULL,
    entry_version     INT          NOT NULL DEFAULT 1,

    -- the tag, by its id in platform.tag; no foreign key across the schema
    -- boundary
    tag_id            UUID         NOT NULL,

    attached_by       TEXT         NOT NULL,
    attached_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT entry_tag_pkey PRIMARY KEY (entry_logical_id, entry_version, tag_id),

    CONSTRAINT fk_entry_tag_memory FOREIGN KEY (entry_logical_id, entry_version)
        REFERENCES memory.memory (logical_id, version) ON DELETE CASCADE
);

-- The filter of query and digest goes from a tag to its entries.
CREATE INDEX idx_entry_tag_tag ON memory.entry_tag (tag_id);

-- Row-level security in the form V3 established, which the completeness
-- probe requires of every table carrying `tenant_id`.
ALTER TABLE memory.entry_tag ENABLE ROW LEVEL SECURITY;

CREATE POLICY entry_tag_tenant_isolation ON memory.entry_tag
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- SELECT to read an entry's tags and to filter by them, INSERT to attach,
-- DELETE to detach. No UPDATE: an assignment is attached or detached, never
-- changed in place.
GRANT SELECT, INSERT, DELETE ON memory.entry_tag TO kumbuka_memory;

COMMENT ON TABLE memory.entry_tag IS
    'Which tag of the platform''s vocabulary an entry carries: the entry by its '
    'durable key, the tag by its id in platform.tag, resolved through '
    'platform.tag_access.';
COMMENT ON COLUMN memory.entry_tag.attached_by IS
    'The subject that attached the tag. Who did what, never a count.';
