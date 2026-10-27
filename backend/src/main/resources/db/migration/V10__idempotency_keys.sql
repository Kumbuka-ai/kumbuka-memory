-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V10: the 24-hour memory of idempotency keys.
--
-- N-1: an image before this one neither reads nor writes this table, and
-- nothing else in the schema changes, so it runs on unchanged.
--
-- WHY
--
-- The service contract (C10.4): `create` takes an idempotency key, on REST as
-- the header `Idempotency-Key` and on MCP as the argument `idempotency_key`;
-- a call repeated by the same caller in the same scope with the same key,
-- within 24 hours of the first, creates nothing and answers what the first
-- call produced, in the entry's current state, and the same key with other
-- arguments is refused. This migration lays down the ledger and nothing that
-- uses it: the code that reads and writes it follows in a later release, so
-- that the release which needs the table meets it already there.
--
-- The shape follows the dispatch service's ledger (dispatch V14), which
-- answers the same rule, with two differences of this schema.
--
-- WHY THE ENTRY IS HELD BY ITS LOGICAL ID, WITHOUT A FOREIGN KEY
--
-- What the first call answered with is held by its durable identity, the
-- entry's `logical_id`, and the repeat reads the entry again, so the answer
-- is its current state and never a stale copy. A foreign key cannot hold it:
-- the entry's key is the pair (`logical_id`, `version`), and this edition
-- withdraws by deleting the row, which a restricting key would forbid and a
-- cascading one would turn into a ledger that forgets a key on withdrawal.
-- A repeat whose entry is gone is a case for the code that reads the ledger.
--
-- WHY THE ARGUMENTS ARE STORED AS A DIGEST
--
-- The rule asks only "the same call again, or another call under a key
-- already spent". A SHA-256 over the canonical arguments answers that and
-- carries nothing readable; storing the arguments would put an entry's
-- content into a second table, beside the one the boundaries were built for.
--
-- WHY NO TIME DEFAULT
--
-- `first_seen_at` is written by the application from its injected clock
-- (C20.1), like every time of this service that a rule reads; a database
-- default would be a second clock.
--
-- ADDITIVE AND N-1 COMPATIBLE (DEC-0018)
--
-- One new table with its policy and its grant. No column, constraint or
-- index of an existing table is touched.
-- ===========================================================================

CREATE TABLE memory.idempotency_key (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,

    -- Per caller and per scope: two callers may choose the same key without
    -- either being answered the other's entry.
    caller_subject   TEXT         NOT NULL,
    idempotency_key  TEXT         NOT NULL,

    -- Which call the key was spent on, and on what arguments. A key spent
    -- on another call is spent, even where the arguments happen to match.
    call_name        TEXT         NOT NULL,
    argument_digest  TEXT         NOT NULL,

    -- The entry the first call answered with.
    entry_logical_id UUID         NOT NULL,

    -- When the key was first spent; an entry older than 24 hours is
    -- replaced in place by the next call that spends the key.
    first_seen_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_key_once
        UNIQUE (scope_id, caller_subject, idempotency_key)
);

-- Row-level security in the form V3 established, which the completeness
-- probe requires of every table carrying `tenant_id`.
ALTER TABLE memory.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory.idempotency_key FORCE  ROW LEVEL SECURITY;

CREATE POLICY idempotency_key_tenant_isolation ON memory.idempotency_key
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- A new relation needs its grant in the migration that adds it: a relation
-- nobody granted is a refused statement on the first call that touches it.
-- SELECT to recognise a repeat, INSERT to remember a first call, UPDATE to
-- replace an entry older than 24 hours. No DELETE: an expired entry is
-- overwritten in place. The key is a UUID with a default, so there is no
-- sequence to grant.
GRANT SELECT, INSERT, UPDATE ON memory.idempotency_key TO kumbuka_memory;

COMMENT ON TABLE memory.idempotency_key IS
    'The 24-hour memory behind the idempotency key of create: which caller '
    'spent which key on which call, and which entry the first call answered with.';
COMMENT ON COLUMN memory.idempotency_key.argument_digest IS
    'SHA-256 over the canonical form of the call''s arguments. Enough to tell '
    'a repeat from a reuse, and carrying nothing readable.';
