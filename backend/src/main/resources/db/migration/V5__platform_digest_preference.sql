-- ===========================================================================
-- V5: a digest selection every tenant has, including the ones that did not
-- exist when this database was migrated.
--
-- THE DEFECT
--
-- V4 seeds ONE row, for the tenant named in `memory.tenant-id` at the moment
-- the migration ran. That is exactly right for the community edition, which
-- serves one tenant per installation (ADR-0035). In the enterprise
-- composition one database serves many, and every tenant but the one the
-- migration happened to be bound to has no row at all — so `digest` answers
-- it `500 no digest selection is stored`. Measured while building
-- `Kumbuka-ai/ee-memory` #1.
--
-- The consequence is not confined to the digest. After the production window
-- of sprint 189 an assistant loads its memory through `digest`, so a tenant
-- without a row starts every session with no memory at all.
--
-- WHY A ROW AND NOT A CONSTANT, AGAIN
--
-- The same reason V4 gives, and V4's reason is what settles this one: a
-- default restated in Java lives in two places that nothing keeps in
-- agreement, and a reader can no longer ask the database what the default is.
-- The operator's decision of 2026-09-22 also rules out the other candidate —
-- a write per tenant at provisioning — because that is precisely the
-- forgettable step whose omission produced this defect, and it would need a
-- backfill for every tenant that already exists.
--
-- WHAT A NULL TENANT MEANS
--
-- The all-zero uuid, in the `tenant_id` column, reads as "no tenant in
-- particular" — the same reading V4 gave it in `scope_id`. It is not a
-- tenant: `platform` allocates tenants with gen_random_uuid(), which never
-- produces it, so a lookup for a real tenant never collides with it.
--
-- WHAT THIS MIGRATION DOES NOT DO
--
-- It removes nothing. V4's policy stands unchanged and its WITH CHECK still
-- governs every write to this table, so nobody — service, tenant or migrator
-- without the local binding below — can write a row under the null tenant.
-- The grant is untouched: the runtime role holds SELECT and nothing else
-- (ADR-0005). There is still no write surface for this table.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- The second policy: the platform row is visible to everyone, and only for
-- reading.
--
-- PERMISSIVE is stated rather than left to the default, because the
-- permissive/restrictive distinction is the whole mechanism here: PostgreSQL
-- combines permissive policies with OR, so a SELECT sees V4's tenant rows OR
-- this one row. A RESTRICTIVE policy would be combined with AND and would
-- hide everything instead.
--
-- FOR SELECT is the other half. INSERT, UPDATE and DELETE fall to V4's policy
-- alone, whose WITH CHECK admits only the bound tenant's own rows — so the
-- platform row can be read by every tenant and written by none of them. The
-- asymmetry is the point and is asserted by PlatformDefaultRowIT.
--
-- The predicate is the null tenant and nothing else. Written without that
-- restriction it would make every row of every tenant readable by every
-- tenant, which is the isolation this schema exists to hold.
-- ---------------------------------------------------------------------------
CREATE POLICY digest_preference_platform_default ON memory.digest_preference
    AS PERMISSIVE
    FOR SELECT
    USING (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid);

-- ---------------------------------------------------------------------------
-- The platform row.
--
-- Its selection is V4's, value for value: this migration closes a gap in who
-- is served, and changing what they are served at the same time would make a
-- later difference in a digest impossible to attribute to either change.
--
-- IT IS ALSO WHAT MAKES THIS MIGRATION N-1 COMPATIBLE, AND THAT IS NOT A
-- HAPPY ACCIDENT
--
-- A 0.2.0 service against a V5 schema reads the selection with the older
-- statement: scope_id IN (scope, null-scope), ordered by scope alone. The
-- tenant's own default row and the platform row both carry the null scope,
-- so that ORDER BY does not separate them and LIMIT 1 takes whichever the
-- executor returns first. As long as the two rows say the same thing, the
-- answer is the same either way and a rolling deployment is uneventful.
--
-- Where they would NOT say the same thing is an estate that has changed its
-- own row — which is an operator act against the database, and the one case
-- in which an old pod could serve the platform's choice instead of the
-- estate's until it is replaced. Reported with this sub-sprint; it is a
-- property of the old statement, and the new one (which orders tenant before
-- platform explicitly) does not have it.
--
-- WHY THE BINDING IS SWITCHED FOR THE INSERT
--
-- FORCE ROW LEVEL SECURITY binds the owner too, so the migrator is subject to
-- V4's WITH CHECK exactly like the runtime role — and V4's WITH CHECK admits
-- only rows of the bound tenant. TenantMigrationCallback binds this
-- installation's tenant before every migration, so without the switch below
-- this INSERT would be refused. That refusal is the guarantee, not an
-- obstacle: it is what makes "nobody can write the platform row" true of the
-- migrator as well, and the only way past it is this deliberate, local,
-- restored-afterwards binding.
--
-- is_local = true on both calls: the binding lives in the transaction Flyway
-- runs this migration in and cannot outlive it on a pooled connection.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    bound_tenant text := current_setting('app.tenant_id', true);
    seeded       int;
BEGIN
    PERFORM set_config('app.tenant_id',
                       '00000000-0000-0000-0000-000000000000', true);

    INSERT INTO memory.digest_preference (tenant_id, scope_id, types, include_global)
    VALUES (
        '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000000',
        ARRAY['constraint','decision','convention','glossary','status']::TEXT[],
        FALSE
    );

    -- Read back rather than trusted, as V4 does and for a reason of the same
    -- shape: a row this migration believes it wrote and did not is a defect
    -- that surfaces much later, in a digest that reports nothing and blames
    -- the entries.
    SELECT count(*) INTO seeded
      FROM memory.digest_preference
     WHERE tenant_id = '00000000-0000-0000-0000-000000000000'
       AND scope_id  = '00000000-0000-0000-0000-000000000000';

    IF seeded <> 1 THEN
        RAISE EXCEPTION
            'V5: the platform digest selection was not written (rows=%). Under FORCE row '
            'level security a write the policy does not admit is not necessarily an '
            'error, so this is checked rather than assumed.', seeded
            USING ERRCODE = 'P0001',
                  HINT = 'the INSERT runs under a local binding of app.tenant_id to the '
                         'null uuid, because V4''s WITH CHECK admits only the bound '
                         'tenant''s own rows and FORCE row level security binds the '
                         'migrator too.';
    END IF;

    -- The installation's own tenant is put back, so nothing after this point
    -- runs under a binding this block chose. Nothing follows it in this
    -- migration today; a later statement appended below it would otherwise
    -- write into the null tenant without saying so.
    PERFORM set_config('app.tenant_id', COALESCE(bound_tenant, ''), true);
END $$;

-- ---------------------------------------------------------------------------
-- No grant is issued here, and the omission is the rule rather than a gap.
--
-- ADR-0005: a runtime role owns nothing and holds enumerated privileges. V4
-- granted SELECT on this table and nothing else, and reading one more row
-- through one more policy needs no privilege that SELECT does not already
-- carry. ServiceRolePrivilegeIT holds that expectation per table and turns
-- red if this file ever widens it.
-- ---------------------------------------------------------------------------
