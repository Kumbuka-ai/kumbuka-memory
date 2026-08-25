-- ===========================================================================
-- V1: the memory schema.
--
-- One PostgreSQL instance, one database, one NAMED schema and one database
-- role per service. `memory` is that schema. Nothing here reaches into
-- another service's schema: no foreign key, no join, no view. A reference to
-- an object owned elsewhere is stored as an address and never resolved.
--
-- WHY THIS CHAIN BEGINS AT ONE RATHER THAN CONTINUING AN EXISTING ONE
--
-- The shape below is in force elsewhere today, inside a chain of twenty-one
-- migrations that also builds the tenancy anchor. Four of those twenty-one
-- touch both sides — the first creates this table together with the anchor's
-- own — so no migration in that chain is memory-pure from its beginning.
-- There is nothing to split off and carry over, which is why this service
-- writes its own first migration instead of inheriting one.
--
-- WHAT IS CARRIED OVER, AND WHAT IS NOT
--
-- Carried over, unchanged and by name: the columns, the eight check
-- constraints, the five indexes on the entry table and the three on the
-- relation table, the policy shape (V3), the acyclicity trigger and the
-- function behind it. These are the model in force and they are restated
-- rather than redesigned.
--
-- NOT carried over: the physical column ORDER. In the schema this shape comes
-- from, the order is a record of the sequence in which twenty-one migrations
-- added things, and it carries no assurance at all — the primary key's own
-- columns sit eleventh and twelfth. Here the columns are grouped by what they
-- are for. Nothing in the catalogue, in the ORM mapping or in a query depends
-- on the ordinal, and a reader does depend on the grouping.
--
-- ALSO NOT carried over: the foreign key from `scope_id` to the anchor's
-- `scope` table. That reference now crosses a service boundary, where a hard
-- foreign key is forbidden. It is held by the read contract instead — a
-- runtime read of `platform.scope_access` through ScopeDirectory — which is
-- the only remaining direction of that relationship. Measurement of the
-- existing schema found exactly one foreign key leading out of these two
-- tables and none leading in, so this is the whole of the coupling and it
-- ends here.
--
-- ON THE SCHEMA ITSELF
--
-- Flyway is configured with this schema as its default (see
-- application.properties), so it creates the schema before running this
-- migration and places its history table inside it. The statement below is
-- therefore normally a no-op, and it is written anyway: the schema is the
-- first thing this service owns, and a reader should find that fact in the
-- migration rather than only in a property file.
--
-- `gen_random_uuid()` is a core function since PostgreSQL 13 and needs no
-- extension — which is one superuser-only operation this migrator, which
-- holds CREATEROLE and nothing more, does not have to carry.
-- ===========================================================================

CREATE SCHEMA IF NOT EXISTS memory;

-- Never reachable via PUBLIC. What the service role may do in this schema is
-- enumerated in V2, statement by statement; PUBLIC gets nothing, and no role
-- acquires anything here by default.
REVOKE ALL ON SCHEMA memory FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- memory — one curated entry.
--
-- IDENTITY. The primary key is the composite (logical_id, version). There is
-- no surrogate row id: `logical_id` is the entry's identity across versions
-- and `version` is the coordinate within it. In this edition an edit mutates
-- the head in place and `version` stays 1; the shape is the same one a
-- copy-on-write edition appends higher versions against, which is why it is
-- built this way from the start rather than migrated into later.
--
-- THE TENANCY AXIS. `tenant_id` under exactly this name on every
-- tenant-scoped table in this schema — the policies in V3 filter on it and
-- the completeness probe reads the catalogue and fails on any table that does
-- not carry it.
--
-- THE SCOPE. `scope_id` is stored and never resolved from here: no foreign
-- key, no join. Resolving it is a runtime read of the platform's published
-- access contract. `is_private` denormalises that scope's kind onto the row,
-- because the two partial unique indexes below need it in the predicate and a
-- join into another service's table is exactly what must not happen.
--
-- THE EIGHT CHECKS. Named individually and spelled out rather than folded
-- into an enum type: a named constraint is what a failure message says, and
-- these are the messages a caller sees when a write is refused. Their names
-- are carried over unchanged.
-- ---------------------------------------------------------------------------
CREATE TABLE memory.memory (
    -- identity
    logical_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    version        INT          NOT NULL DEFAULT 1,

    -- where the entry belongs
    tenant_id      UUID         NOT NULL,
    scope_id       UUID         NOT NULL,
    is_private     BOOLEAN      NOT NULL,
    owner_subject  TEXT         NOT NULL,

    -- what the entry is
    type           VARCHAR(32)  NOT NULL,
    key            TEXT,
    content        TEXT         NOT NULL,
    reference      TEXT,

    -- what state it is in
    state          VARCHAR(16)  NOT NULL DEFAULT 'published',
    lock           VARCHAR(16)  NOT NULL DEFAULT 'none',
    is_head        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    valid_from     TIMESTAMPTZ,
    valid_until    TIMESTAMPTZ,

    -- who wrote it, and through which channel
    source         VARCHAR(16)  NOT NULL DEFAULT 'mcp',
    updated_by     TEXT,
    updated_source VARCHAR(16),

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT memory_pkey PRIMARY KEY (logical_id, version),

    -- The entry contract's length limit, at the database. The application
    -- validates on every write path; this is the backstop underneath, and it
    -- is the one a direct SQL caller still meets.
    CONSTRAINT memory_content_len
        CHECK (char_length(content) <= 1500),

    -- Keys are optional. When present they are lower-case kebab, optionally
    -- dotted — an address a person types, so it is constrained to a shape a
    -- person can retype from memory.
    CONSTRAINT memory_key_format
        CHECK (key IS NULL OR key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'),

    -- The lock axis. 'system' marks a seeded entry; 'admin' is reserved and
    -- not enforced here; 'none' is an ordinary row.
    CONSTRAINT memory_lock_check
        CHECK (lock IN ('system','admin','none')),

    -- The provenance pointer must not carry a credential. Basic-auth in the
    -- authority, and the common secret-bearing query parameters, are both
    -- refused. The application validates the same thing on the write path;
    -- this is the half that also holds against SQL that never went through
    -- it, and P-7 is the probe that watches it hold.
    CONSTRAINT memory_reference_no_credentials
        CHECK (reference IS NULL
               OR (reference !~* '^[a-z][a-z0-9+.-]*://[^/@[:space:]]*@'
                   AND reference !~* '[?&](token|password|passwd|secret|api[_-]?key|access[_-]?token)=')),

    -- The channel the entry was written through. Server-derived from the
    -- write path, never a client-supplied flag.
    CONSTRAINT memory_source_check
        CHECK (source IN ('console','mcp','system','import')),

    CONSTRAINT memory_state_check
        CHECK (state IN ('draft','proposed','published','superseded')),

    CONSTRAINT memory_type_check
        CHECK (type IN ('decision','convention','constraint','open_question','glossary','status')),

    -- The channel of the LAST in-place edit. NULL until an entry is first
    -- edited, which is why it is nullable where `source` is not.
    CONSTRAINT memory_updated_source_check
        CHECK (updated_source IN ('console','mcp','system','import'))
);

CREATE INDEX idx_memory_tenant_scope ON memory.memory (tenant_id, scope_id);
CREATE INDEX idx_memory_owner        ON memory.memory (tenant_id, owner_subject, scope_id);

-- The two key uniqueness rules, differentiated by scope kind and both
-- partial, because a rule that also covered deleted rows and older versions
-- would be a rule about history rather than about what is currently in force.
--
-- shared (global / project): one canonical live head per key, author-independent.
CREATE UNIQUE INDEX uq_memory_shared_key
    ON memory.memory (scope_id, key)
    WHERE is_head AND NOT is_deleted AND NOT is_private AND key IS NOT NULL;

-- private: per author — two owners' identical private keys coexist.
CREATE UNIQUE INDEX uq_memory_private_key
    ON memory.memory (scope_id, owner_subject, key)
    WHERE is_head AND NOT is_deleted AND is_private AND key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- content_relation — typed, directional relations between entries.
--
-- Targets are logical ids and never keys; `to_version` NULL means the
-- relation tracks the target's head, and a set value pins it to one version.
--
-- NO foreign key to `memory`, and the reason is not the service boundary this
-- time: `logical_id` is deliberately non-unique across versions, and a
-- relation has to survive an entry's tombstone. Both make a hard reference
-- the wrong instrument.
-- ---------------------------------------------------------------------------
CREATE TABLE memory.content_relation (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    from_logical_id UUID         NOT NULL,
    to_logical_id   UUID         NOT NULL,
    to_version      INT,
    kind            VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT content_relation_kind_check
        CHECK (kind IN ('supersedes','refines','references'))
);

CREATE INDEX ix_content_relation_from ON memory.content_relation (tenant_id, from_logical_id);
CREATE INDEX ix_content_relation_to   ON memory.content_relation (tenant_id, to_logical_id);

-- ---------------------------------------------------------------------------
-- The acyclicity guard on `supersedes`.
--
-- A supersession chain that closes a cycle has no head, and every reader that
-- walks it either loops or picks arbitrarily. `refines` and `references` are
-- unconstrained; only supersession claims an ordering, so only supersession
-- needs one.
--
-- WHY THE search_path IS PINNED ON THE FUNCTION, AND WHY THE BODY STILL
-- NAMES THE TABLE UNQUALIFIED
--
-- The function this one restates carries no `proconfig` at all: it resolves
-- `content_relation` through whatever `search_path` the calling session
-- happens to have. A session that puts another schema first — a temporary
-- table by that name is enough — gets a guard that silently walks the wrong
-- table and admits the cycle, with no error anywhere. That is a security
-- function that fails open under a condition any caller can create.
--
-- `SET search_path` on the function is the repair, and it is deliberately the
-- ONLY repair: the body still writes `content_relation` unqualified, so the
-- pin is what is actually holding the guard together and a probe can see it
-- doing so. Schema-qualifying the body as well would make the pin
-- unfalsifiable — correct, and never again observed working. P-5 inserts a
-- cycle-closing edge under a deliberately altered session search_path and
-- requires the same refusal.
--
-- `pg_temp` is listed last rather than omitted: it is searched implicitly and
-- first when it is not named, which would reopen the same hole through a
-- temporary table.
--
-- The walk runs as the caller and is therefore subject to the same policies
-- the caller is. That is correct rather than incidental — a cycle inside one
-- tenant is the only cycle this table can have.
-- ---------------------------------------------------------------------------
CREATE FUNCTION memory.content_relation_acyclic_supersedes()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = memory, pg_temp
AS $$
BEGIN
    IF NEW.kind = 'supersedes' THEN
        IF NEW.from_logical_id = NEW.to_logical_id THEN
            RAISE EXCEPTION
                'content_relation: supersedes cannot be self-referential (logical_id=%)',
                NEW.from_logical_id
                USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (
            WITH RECURSIVE reach(node) AS (
                SELECT NEW.to_logical_id
                UNION
                SELECT cr.to_logical_id
                  FROM content_relation cr
                  JOIN reach r ON cr.from_logical_id = r.node
                 WHERE cr.kind = 'supersedes'
            )
            SELECT 1 FROM reach WHERE node = NEW.from_logical_id
        ) THEN
            RAISE EXCEPTION
                'content_relation: supersedes edge %->% would create a cycle',
                NEW.from_logical_id, NEW.to_logical_id
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER content_relation_acyclicity
    BEFORE INSERT OR UPDATE ON memory.content_relation
    FOR EACH ROW EXECUTE FUNCTION memory.content_relation_acyclic_supersedes();
