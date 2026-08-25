-- ===========================================================================
-- A migration carrying DML, for MigrationCallbackWitnessIT and for nothing
-- else. It lives in the TEST resources and is never on the application's
-- Flyway path.
--
-- WHY IT EXISTS
--
-- The tenant-binding callback is registered by class name in
-- `quarkus.flyway.callbacks`, reflectively, and never as a CDI bean. A
-- callback left out of that line is silently never registered. While every
-- shipped migration is pure DDL — which is the whole of V1 to V3 — nothing in
-- this service would notice, because row-level security filters DML only.
--
-- So the probe needs a migration that writes, and there is not one to point
-- at yet. This is that migration. The schema, the policy and the roles it
-- writes against are the real ones; only this statement is the test's. When a
-- real DML migration ships, this file should be deleted and the probe pointed
-- at that instead.
--
-- The tenant id is a placeholder rather than a read of the session setting,
-- and that is the point. Taking it from `app.tenant_id` would make the row
-- trivially satisfy the policy it is supposed to be tested against, and the
-- probe would prove nothing. As a literal it must match a setting that only
-- the callback binds — so with the callback absent, the WITH CHECK clause
-- compares against NULL and the insert is refused.
--
-- The migrator OWNS this table and is still refused, which is FORCE ROW LEVEL
-- SECURITY doing its work — and the reason FORCE is in V3 even though the
-- runtime role is not an owner.
-- ===========================================================================

INSERT INTO memory.memory
    (tenant_id, scope_id, is_private, owner_subject, type, key, content)
VALUES
    ('${memoryTenantId}'::uuid,
     '00000000-0000-0000-0000-000000000010'::uuid,
     false,
     'witness-subject',
     'decision',
     'witness-entry',
     'written by a migration carrying DML');
