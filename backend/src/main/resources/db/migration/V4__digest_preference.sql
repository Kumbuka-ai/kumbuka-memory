-- ===========================================================================
-- V4: the digest's type selection, as state per scope.
--
-- `digest` reports a summary for all six types and carries the CONTENT of the
-- chosen ones. Which types are chosen is not a property of the call — a caller
-- that had to name them every time would be carrying the estate's convention
-- in its own head — so it is state, it lives here, and a call may override it
-- for one answer without changing it.
--
-- THERE IS NO WRITE SURFACE FOR THIS TABLE, AND THAT IS THE DESIGN.
--
-- No verb of this service writes it, and the runtime role holds SELECT on it
-- and nothing else (see the grant at the foot of this file). Changing an
-- estate's selection is an operator act against the database, and the absence
-- of the grant is what makes that true rather than merely intended: a defect
-- in the service cannot write this table even if somebody added the code.
--
-- WHY THE DEFAULT IS A ROW AND NOT A COLUMN DEFAULT
--
-- A column default is invisible to the service: it would have to be restated
-- as a constant in Java to be usable, and then the default would live in two
-- places that nothing keeps in agreement. A row is readable, so the service
-- reads it — one statement, one source of truth, and a reader can ask the
-- database what the estate's default is instead of reading a class to find
-- out.
--
-- The default row's scope is the all-zero uuid. It is not a scope: no scope of
-- the platform carries it, `platform.scope` allocates with gen_random_uuid()
-- which never produces it, and a lookup for a real scope therefore never
-- collides with it. It reads as "no scope in particular", which is what a
-- default is.
--
-- WHY THE SEED CAN NAME A TENANT AT ALL
--
-- The community edition serves one tenant per installation (ADR-0035) and
-- TenantMigrationCallback binds `app.tenant_id` before every migration. So the
-- seed below writes the estate this installation is for, and the WITH CHECK of
-- the policy is what proves it: an unbound migration writes nothing and the
-- verification at the foot of this file turns that silence into an error.
-- ===========================================================================

CREATE TABLE memory.digest_preference (
    -- The tenancy axis, under the name every tenant-scoped table in this
    -- schema carries. The policy below filters on it.
    tenant_id      UUID    NOT NULL,

    -- The scope this selection is for, or the all-zero uuid for the estate's
    -- default. Stored and never resolved from here: no foreign key, no join
    -- into another service's schema.
    scope_id       UUID    NOT NULL,

    -- The types whose CONTENT the digest carries. The summary is reported for
    -- all six whatever stands here.
    types          TEXT[]  NOT NULL,

    -- Whether the digest of a project scope also carries the global scope.
    include_global BOOLEAN NOT NULL,

    CONSTRAINT digest_preference_pkey PRIMARY KEY (tenant_id, scope_id),

    -- The vocabulary, spelled out rather than referenced: `memory.type` is a
    -- check constraint and not a type, so there is nothing to point at. A
    -- selection naming a type the entry table cannot hold would produce a
    -- digest section that is empty for a reason no caller could discover.
    CONSTRAINT digest_preference_types_known
        CHECK (types <@ ARRAY['decision','convention','constraint',
                              'open_question','glossary','status']::TEXT[]),

    -- An empty selection is a digest with no content at all, which is a
    -- configuration nobody wants and everybody would read as a defect in the
    -- digest. Refused here rather than interpreted later.
    CONSTRAINT digest_preference_types_present
        CHECK (cardinality(types) > 0)
);

-- The lookup is always (tenant, scope) and is served by the primary key. No
-- second index: an index that no statement uses is a cost with no reader.

-- ---------------------------------------------------------------------------
-- The same two-layer isolation the other tables of this schema carry. ENABLE
-- binds every role except the owner; FORCE binds the owner too, which is what
-- makes the migrator's own seed below subject to the policy — and therefore
-- what makes an unbound migration write nothing instead of writing into the
-- wrong estate.
-- ---------------------------------------------------------------------------
ALTER TABLE memory.digest_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory.digest_preference FORCE  ROW LEVEL SECURITY;

CREATE POLICY digest_preference_tenant_isolation ON memory.digest_preference
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- The estate's default: every type but `open_question`, and the global scope
-- left out.
--
-- `open_question` is out because a digest is what is IN FORCE, and an open
-- question is the opposite of that — it is carried in the summary so that its
-- count is visible, and left out of the content so that a reader is not handed
-- unsettled material as if it were settled.
-- ---------------------------------------------------------------------------
INSERT INTO memory.digest_preference (tenant_id, scope_id, types, include_global)
VALUES (
    NULLIF(current_setting('app.tenant_id', true), '')::UUID,
    '00000000-0000-0000-0000-000000000000',
    ARRAY['constraint','decision','convention','glossary','status']::TEXT[],
    FALSE
);

-- The seed is verified rather than trusted. Under FORCE row-level security an
-- INSERT with no tenant bound does not raise — it is simply filtered, the
-- migration succeeds, and the default is missing in a way that surfaces much
-- later, in a digest that reports nothing and blames the entries.
DO $$
DECLARE
    seeded int;
BEGIN
    SELECT count(*) INTO seeded
      FROM memory.digest_preference
     WHERE scope_id = '00000000-0000-0000-0000-000000000000';

    IF seeded <> 1 THEN
        RAISE EXCEPTION
            'V4: the default digest selection was not written (rows=%). Under FORCE row '
            'level security an unbound migration writes nothing and reports success, so '
            'this is checked rather than assumed.', seeded
            USING ERRCODE = 'P0001',
                  HINT = 'app.tenant_id must be bound before this migration runs. It is '
                         'bound by TenantMigrationCallback, which Flyway loads from '
                         'quarkus.flyway.callbacks — a callback left out of that key is '
                         'never registered, with no warning anywhere.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- The grant: SELECT, and nothing else.
--
-- This is the one table of this schema the runtime role may not write. The
-- asymmetry with `memory` and `content_relation` is the point and is asserted
-- by ServiceRolePrivilegeIT, which carries the expectation per table rather
-- than one set for the schema.
-- ---------------------------------------------------------------------------
GRANT SELECT ON memory.digest_preference TO kumbuka_memory;
