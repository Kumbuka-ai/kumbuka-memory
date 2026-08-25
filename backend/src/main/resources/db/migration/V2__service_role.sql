-- ===========================================================================
-- V2: the service's own database role, and every privilege it holds.
--
-- The service connects as `kumbuka_memory` and as nothing else. It OWNS
-- NOTHING. Everything it may do in this schema is written out below, one
-- privilege at a time, on one named table at a time.
--
-- WHY THE MIGRATOR KEEPS OWNERSHIP
--
-- An owner can run `ALTER TABLE ... DISABLE ROW LEVEL SECURITY` and
-- `DROP POLICY`. `FORCE ROW LEVEL SECURITY` subjects an owner to the policies
-- when it QUERIES; it does not stop it from removing them. So a runtime role
-- that owned these two tables would hold the ability to switch off its own
-- tenant isolation — and these are the two tables the private-memory
-- guarantee and the tenant boundary actually live on. Ownership would also
-- hand it the full implicit privilege set, TRUNCATE included, which bypasses
-- row-level security independently of every policy; and a sweep that hands
-- over every relation in a schema hands over the Flyway history table with
-- them, because that table lives in the schema too. A runtime role that can
-- rewrite the history table can make a schema lie about its own version.
--
-- So this service enumerates. The cost is real and is accepted: a table added
-- by a later migration receives NO privilege automatically, and a migration
-- that forgets its grant produces a service that cannot read its own new
-- table. That failure is loud and immediate. The failure it replaces is
-- silent and permanent.
--
-- The objection to enumeration is that a grant list drifts from the schema it
-- describes. It drifts when nobody is watching it. `ServiceRolePrivilegeIT`
-- reads the catalogue, derives the table list from the catalogue rather than
-- from a constant, requires every table in this schema to carry exactly the
-- four privileges below for the runtime role, and requires
-- `flyway_schema_history` to carry none — and it observes its own detection
-- working in both directions on every build: a privilege added must be
-- reported, and a privilege missing must be reported too.
--
-- WHY THERE IS A DELETE HERE
--
-- The sibling service that shares this arrangement grants three privileges
-- and not four, because no path in it deletes. This one does: an entry can be
-- forgotten, and a subject's entries have to be removable rather than merely
-- flagged. An erasure obligation that is honoured by setting a boolean is not
-- an erasure. The `is_deleted` column is a tombstone for the head-versus-
-- history model and is a different thing from removal.
--
-- TWO ROLE ATTRIBUTES ARE LOAD-BEARING, AND THIS MIGRATION ONLY CHECKS THEM
--
--   NOSUPERUSER   a superuser bypasses row-level security unconditionally.
--   NOBYPASSRLS   so does a role carrying BYPASSRLS.
--
-- Either one silently evaporates the tenant filter: rows returned, no error,
-- every test green. The migrator cannot grant either attribute and cannot
-- take it away — those are superuser-only operations, and this service
-- migrates with CREATEROLE and nothing more. That is why the block below
-- RAISES instead of repairing. A migration that quietly stripped a security
-- attribute would be a migration that could quietly add one; refusing to run
-- against a wrongly-shaped role leaves the decision with whoever shaped it,
-- and leaves a message saying so.
--
-- THE OPERATOR BOUNDARY IS THE LINE THAT IS NOT HERE
--
-- No grant is issued to the provider role, and none to any other service's
-- role. They cannot read an entry because no privilege exists that would let
-- them, not because a rule in the application forbids it. There is
-- deliberately no statement below naming them: an assurance about an absence
-- is kept by writing nothing, and it is proven by a probe that observes the
-- refusal at the database — and observes the access a temporarily granted
-- privilege allows, because an absence that was never seen to matter is not a
-- boundary.
--
-- The boundary is now the absence of `USAGE` on this schema rather than the
-- absence of an entry in a table-grant list, and that is a stronger form of
-- the same decision: it holds automatically for every table this service will
-- ever add.
--
-- THE PASSWORD BELOW IS A PLACEHOLDER AND MUST BE ROTATED
--
-- It is written so that the service comes up against an empty database with
-- no manual step, which is what makes a cold start reproducible. It is not a
-- credential: any deployment reachable from outside a development machine
-- replaces it with `ALTER ROLE kumbuka_memory PASSWORD …` from its own secret
-- store, as an operational act outside this repository.
-- ===========================================================================

DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_memory') THEN
        -- A CREATEROLE migrator cannot confer SUPERUSER or BYPASSRLS, so a
        -- role created here structurally cannot carry either. The check below
        -- is for the other path: a role an operator created beforehand.
        CREATE ROLE kumbuka_memory LOGIN PASSWORD 'change-me-kumbuka-memory';
        RAISE NOTICE 'created role kumbuka_memory with the placeholder password — rotate it';
    END IF;

    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_memory';

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'kumbuka_memory carries superuser=% bypassrls=% — either one makes the '
            'row-level-security policies in V3 inert, and this migration will not '
            'create a schema whose isolation cannot hold. Recreate the role without '
            -- Cast to text before the placeholder. RAISE formats a boolean
            -- through the type's own output function, which yields `t` and
            -- `f`; the message is read by whoever has to repair the role, at
            -- deployment time, and `superuser=f bypassrls=t` is a puzzle where
            -- `superuser=false bypassrls=true` is an instruction.
            'them.', is_super::text, is_bypass::text;
    END IF;

    -- Raw SQL and psql sessions land in the service's own schema rather than
    -- in `public`. Hibernate and Flyway are pinned by configuration and do
    -- not depend on this; it is here so an unqualified statement typed by a
    -- human fails in the right place.
    --
    -- Since PostgreSQL 16 a CREATEROLE role may only ALTER a role it holds
    -- ADMIN OPTION on, which it does for a role it created itself. The other
    -- path is a role an operator created beforehand, and there the statement
    -- is refused — with a message about privileges that says nothing about
    -- what is actually wrong, so it is named here instead.
    BEGIN
        EXECUTE 'ALTER ROLE kumbuka_memory SET search_path = memory';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE EXCEPTION
            'the migrating role % may not configure kumbuka_memory. That happens when '
            'the service role was created by somebody else, so this migrator holds no '
            'ADMIN OPTION on it. Grant it (GRANT kumbuka_memory TO %I WITH ADMIN '
            'OPTION) and re-run, or let this migration create the role itself.',
            current_user, current_user;
    END;
END
$do$;

-- ---------------------------------------------------------------------------
-- The privileges. This block is the whole entitlement of the runtime role in
-- this schema, and it is meant to be read as a list rather than trusted as a
-- rule.
--
-- The REVOKE first, and the asymmetry is deliberate. A collective REVOKE can
-- only ever remove, so it cannot widen anything and it makes the GRANTs below
-- the exact statement of what is held rather than an addition to whatever was
-- held before. A collective GRANT is the opposite in every respect, which is
-- why there is none.
--
-- `flyway_schema_history` is inside this schema and is therefore covered by
-- the REVOKE and named in no GRANT.
-- ---------------------------------------------------------------------------
REVOKE ALL ON ALL TABLES IN SCHEMA memory FROM kumbuka_memory;
REVOKE ALL ON SCHEMA memory FROM kumbuka_memory;

-- Reaching the schema at all. USAGE is not a privilege on any table in it,
-- and CREATE is deliberately not granted: a role holding CREATE on its own
-- schema could add a table, own it, and hold the full implicit privilege set
-- on it — the enumeration defeated in one statement.
GRANT USAGE ON SCHEMA memory TO kumbuka_memory;

-- Two tables, four privileges, every one of them named. No TRUNCATE, no
-- TRIGGER, no REFERENCES.
GRANT SELECT, INSERT, UPDATE, DELETE ON memory.memory           TO kumbuka_memory;
GRANT SELECT, INSERT, UPDATE, DELETE ON memory.content_relation TO kumbuka_memory;
