# Extraction inventory

A measurement of where the memory domain sits inside `kumbuka-server` today, what
would break elsewhere if it moved, what precedent an independent service already
sets, and which behavioural guarantees would have to be proven again.

**This document decides nothing.** It proposes no cut, no ordering, no repository
layout and no transition form for existing data. Where the measurement made a
decision visible, the decision is recorded under *Open questions* and left there.

**Method.** Every figure is derived from the checked-out sources, not from a search
index. Java types were enumerated by walking the source tree; coupling was computed
from the sources with comments stripped, so a type named only in prose does not
count as a reference. Catalogue figures come from a throwaway PostgreSQL 16 instance
carrying the full migration chain; production was not touched. Where a search was
used, its result was cross-checked against a directory listing, and an empty search
is reported as *not measured* rather than as absence.

Measured against `kumbuka-server` at commit `d04dd70`, `ee-server` and
`kumbuka-dispatch` at their current heads.

---

## A. The cut in `kumbuka-server`, at symbol level

### Headline

Production sources only (`backend/*/src/main`), 79 types, 8296 lines.

| Class | Types | Share | Lines | Share |
|---|---:|---:|---:|---:|
| Memory | 22 | 27.8 % | 3099 | 37.4 % |
| Platform | 30 | 38.0 % | 3021 | 36.4 % |
| **Contested** | **27** | **34.2 %** | **2176** | **26.2 %** |
| Total | 79 | | 8296 | |

**Neither side is the smaller one.** By line count the two decided sides are within
one percentage point of each other. The contested block is a third of the type count
and a quarter of the lines, and it is large enough to determine the answer on its own:

- if every contested type went to memory: 49 types / 5275 lines — **63.6 %** of the code
- if every contested type stayed on the platform: 22 types / 3099 lines — **37.4 %**

The question "which side is smaller" therefore has no answer independent of how the
27 contested types are assigned. That assignment is a design decision and is not made
here.

Test sources are counted separately: 85 classes, 12680 lines, i.e. the test tree is
larger than the production tree it covers.

### Memory (22 types, 3099 lines)

| Type | Lines | Basis |
|---|---:|---|
| `admin.AdminEntriesResource` | 257 | console surface for entries |
| `admin.MemoryExceptionMappers` | 45 | error mapping for the memory paths |
| `admin.ProtectedEntryExceptionMapper` | 45 | error mapping for lock semantics |
| `domain.ContentUnit` | 152 | `@MappedSuperclass` of `Memory` |
| `domain.Memory` | 82 | entity of the `memory` table |
| `domain.MemoryLock` | 59 | lock semantics |
| `domain.MemoryType` | 51 | mnemonic type space |
| `domain.SourceChannel` | 92 | write-channel provenance |
| `domain.SystemSubject` | 25 | sentinel stored in `memory.owner_subject` |
| `mcp.MemoryTools` | 524 | MCP surface, the six `memory_*` verbs |
| `mcp.dto.Dtos` | 151 | MCP DTOs, references `Memory` |
| `overlay.GuidanceLoadException` | 26 | overlay error path |
| `overlay.GuidanceLoader` | 212 | loads the overlay entries |
| `overlay.GuidanceOverlay` | 362 | overlay boundary above the store |
| `repo.MemoryRepository` | 528 | core repository |
| `repo.ProtectedEntryException` | 43 | error path of lock semantics |
| `repo.ReservedNamespaceGuard` | 87 | reserved key space |
| `repo.SharedMemoryRepository` | 159 | shared-scope reads |
| `util.MemoryContentValidator` | 30 | length of `memory.content` |
| `util.MemoryKeyValidator` | 53 | shape of `memory.key` |
| `util.ReferenceUrlValidator` | 58 | validates `memory.reference`; DB backstop is the CHECK `memory_reference_no_credentials` |
| `util.SystemKeyNamespace` | 58 | reserved key namespace |

### Platform (30 types, 3021 lines)

`admin.AdminConnectorResource` (118), `admin.AdminSettingsResource` (76),
`admin.AdminUsersResource` (306), `admin.AuthLoginResource` (49),
`admin.CredentialsResource` (93), `admin.CurrentSessionId` (51),
`admin.ScopeExceptionMappers` (77), `admin.SessionResource` (262),
`admin.SessionsResource` (106), `audit.TeamAuditService` (30),
`auth.PathBasedTenantResolver` (32), `config.OidcPrincipalClaimGuard` (138),
`domain.GovernanceAudit` (61), `domain.Team` (44), `domain.TeamSettings` (101),
`domain.UiSettings` (115), `domain.UserAccount` (142), `domain.UserStatus` (49),
`keycloak.DefaultTenantUserScope` (37), `keycloak.KeycloakAdminService` (378),
`keycloak.TenantUserScope` (52), `repo.TeamSettingsRepository` (55),
`service.ScopeReadOnlyException` (38), `service.WritePolicyResolver` (66),
`version.VersionHeaderFilter` (29), `version.VersionResource` (81),
`version.VersionRouteHeaderFilter` (37), `waitlist.WaitlistIntakeResource` (241),
`wellknown.ConnectorMetadataConfig` (59),
`wellknown.ProtectedResourceMetadataResource` (98).

### Contested (27 types, 2176 lines) — named individually

These are the expensive finds: each is used from both sides, or its purpose spans
both. None is resolved here.

| Type | Lines | Why it is contested |
|---|---:|---|
| `config.MemoryConfig` | 89 | **Misnamed.** It is the service-wide configuration interface — `publicBaseUrl`, `mcpPublicUrlTemplate`, `authBaseUrl`, `consoleBaseUrl`, `tenantId`, `realm`, `connectorClientId`, plus `loadContextPerTypeLimit` and `systemGuidancePath`. Only two of nine members are memory-specific, yet seven types depend on it from both sides. |
| `domain.Scope` | 67 | Container of memory (`memory.scope_id` FK) **and** platform directory: the view `platform.scope_access` selects from `scope`. |
| `domain.ScopeKind` | 47 | `private` / `global` is a memory visibility rule; the scope itself is directory data. |
| `repo.ScopeRepository` | 144 | Scope lifecycle, used from the memory paths. |
| `util.ScopeSlugValidator` | 42 | Slug shape of the scope. |
| `util.SlugPatterns` | 46 | Slug patterns shared by scope and team. |
| `admin.AdminScopesResource` | 160 | References `SharedMemoryRepository` **and** `TeamAuditService`, `TeamSettings`, `TeamSettingsRepository`. |
| `admin.AdminOverviewResource` | 87 | References `Memory`, `MemoryType`, `SharedMemoryRepository` **and** `UserAccount`. |
| `admin.dto.AdminDtos` | 234 | Carries `Memory`, `SourceChannel` **and** `TeamSettings`, `UiSettings`. |
| `service.MemberWritePolicy` | 88 | References `SourceChannel` **and** `UserAccount`. |
| `projection.ScopeStatsRefresher` | 103 | Native SQL over `memory`, `scope` and `scope_stats` in one statement — the provider-facing projection boundary. |
| `erasure.TenantDataPurgeService` | 115 | Deletes through the `Memory` entity **and** native SQL over `team`, `team_settings`, `user_account` in one transaction. |
| `erasure.MemberErasureService` | 78 | Erases a subject's memory as part of the directory erasure path. |
| `erasure.EraseSubjectResource` | 117 | Entry point of the erasure path. |
| `erasure.PurgeTenantResource` | 111 | Entry point of the purge path. |
| `erasure.ErasureConfig` | 44 | Configuration of the erasure path. |
| `tenancy.DefaultSingleTenantResolver` | 30 | Tenant resolution — required by both sides. |
| `tenancy.HibernateTenantResolver` | 39 | as above |
| `tenancy.RequestScopedTenantContext` | 85 | as above |
| `tenancy.StringUuidConverter` | 32 | as above |
| `tenancy.TenantBindingInterceptor` | 40 | Binds the `app.tenant_id` GUC — required by both sides. |
| `tenancy.TenantBound` | 29 | as above |
| `tenancy.TenantContext` | 51 | as above |
| `tenancy.TenantDatabaseBinding` | 135 | Sets `app.tenant_id` inside the transaction — required by both sides. |
| `tenancy.TenantRequestFilter` | 62 | as above |
| `tenancy.TenantResolver` | 46 | as above |
| `tenancy.TenantyMigrationCallback` | 55 | Sets the GUC before each migration — see section D. The class name carries a typo in the source and is quoted as it stands. |

The eleven `tenancy.*` types are contested as a block, not individually: they are
infrastructure both sides need. Section C shows that an independent service does not
share them but carries its own equivalents.

### MCP verbs

Six, all on `mcp.MemoryTools`, all in the memory class:
`memory_remember`, `memory_recall`, `memory_update`, `memory_forget`,
`memory_scopes`, `memory_load_context`.

### REST surfaces

Memory: `/api/scopes/{slug}/entries` with `/{id}` and `/{id}:remap`
(`admin.AdminEntriesResource`).

Contested: `/api/overview` (`AdminOverviewResource`), `/api/scopes` with
`/{slug}`, `:archive`, `:unarchive`, `:lock`, `:unlock` (`AdminScopesResource`),
`/api/internal/erase-subject`, `/api/internal/purge-tenant`.

Platform: `/api/users`, `/api/settings`, `/api/connector`, `/api/credentials`,
`/api/sessions`, `/api/auth/login`, `/api/auth/me`, `/api/version`,
`/api/public/waitlist-intake`, `/.well-known/oauth-protected-resource`.

### Flyway migrations

21 files (`V5` is absent from the sequence). Mapped by the objects they create:

- **memory only** (5): `V8__memory_reference`, `V10__memory_content_length`,
  `V12__memory_protected`, `V19__content_unit_channel_and_deleted_flag`,
  `V20__drop_protected_delete_block`
- **mixed** (4): `V1__init` (`memory`, `scope`, `team`, `user_account`),
  `V2__scope_settings_source`, `V3__tenancy_rls` (policies across five tables),
  `V16__contentunit_mnemonic_head` (`content_relation`, `memory`, `scope`)
- **platform only** (12): `V4`, `V6`, `V7`, `V9`, `V11`, `V13`, `V14`, `V15`,
  `V17`, `V18`, `V21`

No migration is memory-only from the beginning of the chain: `V1` creates the memory
table alongside the directory tables, and `V3` applies RLS to both in one file.

### Configuration keys

Eleven `kumbuka.*` keys. Memory-specific: `kumbuka.load-context.per-type-limit`,
and the system-guidance path read through `MemoryConfig.systemGuidancePath()`. The
remaining nine are service identity and URLs.

---

## B. What breaks in `ee-server`

`ee-server` has three modules (directory listing): `abuse-control/`, `importer/`,
`tenant-resolution/`.

### Production references — three sites in two modules

| Module | File | Line | Reference |
|---|---|---:|---|
| `abuse-control` | `src/main/java/ai/kumbuka/saas/ratelimit/McpWriteRateLimitInterceptor.java` | 3, 49 | imports `ai.kumbuka.mcp.MemoryTools`; resolves the target by scanning `MemoryTools.class.getDeclaredMethods()` for `memory_remember` |
| `abuse-control` | `src/main/java/ai/kumbuka/saas/ratelimit/ConsoleWriteRateLimitFilter.java` | 3, 60, 66 | imports `ai.kumbuka.admin.AdminEntriesResource`; resolves `create` and `update` from `getDeclaredMethods()` |
| `importer` | `src/main/java/ai/kumbuka/saas/importer/ExtractionValidator.java` | 6, 53 | imports `ai.kumbuka.util.MemoryContentValidator`; `MAX_CONTENT = MemoryContentValidator.MAX_LEN` |

Both rate-limit sites resolve by **reflection over method identity** and both fail
loud rather than degrading: each throws `IllegalStateException` with the text
`REFUSING TO ENFORCE: ... The OSS seam moved — realign this ... instead of running
unlimited.` If the memory types moved, both would throw at class initialisation
rather than silently stop limiting. That is a property worth preserving, and it means
the break is immediate and visible rather than latent.

### Test references — four files

- `tenant-resolution/src/test/java/ai/kumbuka/saas/e2e/TenantBoundMemoryOps.java`
  — `Memory`, `MemoryType`, `SourceChannel`, `MemoryRepository`
- `tenant-resolution/src/test/java/ai/kumbuka/saas/e2e/TenantIsolationDataLayerIT.java`
  — `Memory`
- `abuse-control/src/test/java/ai/kumbuka/saas/ratelimit/McpWriteRateLimitIT.java`
  — `MemoryTools`, `Memory`, `MemoryType`, `SourceChannel`, `MemoryRepository`
- `abuse-control/src/test/java/ai/kumbuka/saas/ratelimit/ConsoleWriteRateLimitFilterTest.java`
  — `AdminEntriesResource`

A further grep hit in `RateLimitScaleGateTest` is a false positive: the string
`"In-Memory"` is a scale-mode label, not a type reference.

### Test substrate

`tenant-resolution/src/test/java/ai/kumbuka/saas/e2e/SaasKumbukaDbResource.java`
creates the runtime role as `CREATE ROLE kumbuka LOGIN NOSUPERUSER NOBYPASSRLS`
(line 68) and then grants breadth rather than a list:

```sql
GRANT USAGE ON SCHEMA public TO kumbuka
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO kumbuka
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO kumbuka
```

The schema itself is the snapshot `src/test/resources/kumbuka-schema.sql`, 727 lines.
The class comment describes it as "V1–V7"; the file contains `content_relation`,
which enters the chain at `V16`, so that description is stale.

### `ee-server`'s own migration chain

Two files, both under `db/ee-migration/` rather than `db/migration/`:
`abuse-control/.../V10000__tenant_limits.sql` and
`importer/.../V10001__import_credits.sql`. Neither names `memory` or
`content_relation`.

### The CI latch

`.github/workflows/ci.yml` reads the failsafe XML reports and counts executed tests
(`tests - skipped`). The comment states: *"TenantIsolationDataLayerIT is the
security-bearing in-JVM layer: zero executed tests is RED, not green."* A missing
report file is also an error.

**What the test actually proves.** Its two methods are
`beta_cannot_read_alphas_memory` and `unbound_read_is_fail_closed`. The class comment
describes the subject as the *mechanism*: the write and read go through
`TenantBoundMemoryOps`, a `@TenantBound @Transactional` wrapper that mirrors the real
MCP tool, so that "the interceptor sets the `app.tenant_id` GUC from the bound tenant
inside the tx, so Postgres RLS (FORCE) + Hibernate `@TenantId` both apply, with the
runtime app role `kumbuka` (NOBYPASSRLS)".

So the property under test is the generic two-layer isolation of the data layer, and
it is generic enough to survive a change of subject — but the subject as written is a
memory row, asserted through `List<Memory>` and `m.content`. After an extraction
`ee-server` has no memory to write. Whether the latch gets a new subject or travels
with the memory is recorded under *Open questions*.

---

## C. The pattern in `kumbuka-dispatch`

Established by directory listing, not by search: the repository holds 27 production
Java files and 7 migrations, enumerated in full.

### Schema, role and migrator

- `V1__init.sql` creates `CREATE SCHEMA IF NOT EXISTS dispatch` and places its tables
  inside it (`dispatch.scope`, …).
- `V2__service_role.sql` creates `kumbuka_dispatch LOGIN` explicitly **without**
  `SUPERUSER` and **without** `BYPASSRLS`, noting that a `CREATEROLE` migrator cannot
  confer either. It then pins `ALTER ROLE kumbuka_dispatch SET search_path = dispatch`.
  The file states that the service "needs no GRANT for its own tables, and a grant
  list is a thing that drifts" — ownership replaces enumeration.
- `V3__tenancy_rls.sql` enables and forces RLS and writes policies with **both**
  `USING` and `WITH CHECK`, with an explicit note that `USING` alone would let a
  session insert a row under a foreign tenant.

### Persistence configuration

```properties
quarkus.flyway.default-schema=dispatch
quarkus.flyway.schemas=dispatch
quarkus.flyway.create-schemas=true
quarkus.flyway.username=${DISPATCH_MIGRATOR_USERNAME:postgres}   # migrator ≠ runtime user
quarkus.flyway.callbacks=ai.kumbuka.dispatch.tenancy.TenantMigrationCallback,\
                         ai.kumbuka.dispatch.tenancy.SchemaOwnershipCallback
quarkus.hibernate-orm.database.generation=validate
quarkus.hibernate-orm.database.default-schema=dispatch
```

Two points carry beyond this service. First, the persistence unit names its schema
explicitly, so `validate` resolves against `dispatch` rather than against whatever
`current_schema()` happens to be. Second, the Flyway callbacks are registered through
`quarkus.flyway.callbacks` — see section D for why that key matters.

### Tenant resolution

`tenancy/` holds twelve types: `ConfiguredTenantResolver`, `HibernateTenantResolver`,
`SchemaOwnershipCallback`, `StringUuidConverter`, `TenantBindingInterceptor`,
`TenantBound`, `TenantContext`, `TenantDatabaseBinding`, `TenantMigrationCallback`,
`TenantRequestFilter`, `TenantResolver`, `ThreadLocalTenantContext`. The names are
near-identical to `kumbuka-server`'s eleven `tenancy.*` types: the pattern is already
duplicated between the two services rather than shared through a common artefact.

### Reading the platform directory

`platform/ScopeDirectory.java` reads the directory through exactly one view:

```java
em.createNativeQuery("""
        SELECT scope_id, tenant_id, slug, archived
        FROM platform.scope_access
```

The class comment records that it holds `SELECT` on exactly one view and not on the
platform's base tables. It also sets a second GUC, `app.subject`, alongside
`app.tenant_id`.

### Boundary tests

`boundary/MissingGrantProbeIT`, `boundary/ServiceRoleConformanceIT`,
`boundary/ViewOwnerGuardIT`; and in `tenancy/`: `ColdStartIT`, `FailClosedProbeIT`,
`MigrationCallbackWitnessIT`, `RawSqlArchitectureTest`, `RowLevelSecurityProbeIT`,
`TenancyCompletenessIT`, `TenantBindingEdgeCaseIT`.

### Not measurable here: the MCP surface

`kumbuka-dispatch` has **no** MCP surface. Its `pom.xml` names no MCP dependency and
its configuration none; the only REST class is `api/WhoamiResource`. Authentication is
OIDC as a bearer-token service (`application-type=service`, `token.audience`,
`roles.role-claim-path=realm_access/roles`, `token.principal-claim=sub`). The
question how an independent service authenticates an *MCP* surface therefore has no
precedent in this repository and is reported as not measured rather than as answered.

---

## D. The behavioural contract

For each guarantee: where it is enforced, and which executing gate holds it today.

| Guarantee | Enforced at | Gate |
|---|---|---|
| RLS enabled and forced; per-table tenant policies | `V3__tenancy_rls.sql` (5 tables), `V16` (`content_relation`), `V6` (`scope_stats`), `V14` (`governance_audit`) | `tenancy/CrossTenantIsolationIT`, `projection/ScopeStatsTenantIsolationIT`, `tenancy/PlatformScopeAccessIT`, and in `ee-server` `TenantIsolationDataLayerIT` |
| `app.tenant_id` GUC bound to the open transaction | `tenancy.TenantDatabaseBinding`, `tenancy.TenantBindingInterceptor`, `tenancy.TenantBound` | `tenancy/TenantGucBindingIT`, `tenancy/TenantGucProbe`, `tenancy/TenantBindingInterceptorTest`, `tenancy/TenantIdCompletenessTest` |
| Scope model `private` / `global` and its visibility rules | `domain.ScopeKind`, `repo.SharedMemoryRepository` | `repo/PrivateIsolationTest`, `admin/AdminPrivateInvariantTest` |
| Lock semantics | `domain.MemoryLock`, `repo.MemoryRepository`, `repo.ProtectedEntryException` | `domain/MemoryLockTest`, `repo/SystemLockDeleteTest`, `repo/SystemLockedRowGuardsTest`, `mcp/ScopeLockEnforcementIT`, `admin/ScopeLockRestIT` |
| Mute logic | `service.MemberWritePolicy`, `user_account.muted` (`V9`) | `mcp/MemberMuteIT`, `mcp/MemoryToolsMuteTest` |
| Erasure and purge path | `erasure.TenantDataPurgeService`, `erasure.MemberErasureService` | `erasure/TenantDataPurgeServiceTest`, `erasure/MemberErasureServiceTest`, `erasure/PurgeTenantResourceTest`, `erasure/EraseSubjectResourceTest` |
| Overlay boundary | `overlay.GuidanceOverlay`, `overlay.GuidanceLoader` | eight tests under `overlay/`, plus `wellknown/ProtectedResourceMetadataTest` |
| Key shape and content length | `util.MemoryKeyValidator`, `util.MemoryContentValidator` | `util/MemoryKeyValidatorTest`, `util/MemoryContentValidatorTest` |
| Reserved `system` key namespace | `util.SystemKeyNamespace`, `repo.ReservedNamespaceGuard` | `util/SystemKeyNamespaceTest`, `repo/SystemNamespaceGuardTest`, `admin/AdminEntriesReservedDeleteRestIT` |
| Provenance of the write channel | `domain.SourceChannel`, pre-persist checks in `domain.Memory` | `domain/SourceChannelTest`, `repo/ChannelCompatibilityTest` |
| `memory.reference` carries no credentials | `util.ReferenceUrlValidator` plus the CHECK `memory_reference_no_credentials` | `util/ReferenceUrlValidatorTest` covers the Java validator; **no test names the CHECK**, so the database backstop is ungated |
| Acyclicity of `supersedes` edges | `V16` trigger `content_relation_acyclicity` on the function `content_relation_acyclic_supersedes()` | **none** |
| `app.tenant_id` set before each Flyway migration | `tenancy.TenantyMigrationCallback` | **no witness** |

### Two guarantees without an executing gate

**Acyclicity.** The trigger and its function are created in `V16`. Cross-checked
against the enumerated test tree — 85 files — the string `content_relation` occurs in
none of them. Neither does `acyclic` or `supersedes`. The guard is therefore never
exercised by the suite. A separate measurement additionally established that the
function carries `proconfig = none`, i.e. no pinned `search_path`, and resolves
`content_relation` unqualified from its body.

**The migration callback.** `tenancy.TenantyMigrationCallback` is declared
`@ApplicationScoped` and extends `BaseCallback`; its Javadoc states it is "picked up
by `quarkus-flyway` via the `Callback` SPI". The `application.properties` of
`kumbuka-server` contains only `quarkus.flyway.migrate-at-start` and
`quarkus.flyway.baseline-on-migrate` — there is **no `quarkus.flyway.callbacks` key**.
`kumbuka-dispatch` sets that key explicitly and additionally carries
`MigrationCallbackWitnessIT`. In `kumbuka-server` the only test that names the
callback is `tenancy/TenantRawSqlArchitectureTest`, an architecture rule, not a
witness of execution.

Whether the callback runs was **not measured here**: establishing it would require a
change to program code, which this measurement does not make. What is measured is
that the registration key the surrounding configuration uses elsewhere is absent, and
that no test witnesses the callback's effect.

---

## E. The handover object

Catalogue state after the full chain on a throwaway PostgreSQL 16 instance.

### `memory` — 21 columns

`tenant_id uuid`, `owner_subject text`, `scope_id uuid`, `type varchar(32)`,
`key text`, `content text`, `created_at timestamptz`, `updated_at timestamptz`,
`source varchar(16)`, `reference text`, `logical_id uuid`, `version integer`,
`is_head boolean`, `state varchar(16)`, `is_private boolean`,
`valid_from timestamptz`, `valid_until timestamptz`, `updated_by text`,
`updated_source varchar(16)`, `lock varchar(16)`, `is_deleted boolean`.

- Constraints: primary key `memory_pkey`; foreign key `memory_scope_id_fkey → scope`;
  eight CHECKs — `memory_content_len`, `memory_key_format`, `memory_lock_check`,
  `memory_reference_no_credentials`, `memory_source_check`, `memory_state_check`,
  `memory_type_check`, `memory_updated_source_check`
- Indexes (5): `memory_pkey`, `idx_memory_owner`, `idx_memory_tenant_scope`,
  `uq_memory_private_key`, `uq_memory_shared_key`
- Policy: `memory_tenant_isolation`, `cmd=ALL`
- Triggers: none
- `relrowsecurity = true`, `relforcerowsecurity = true`

### `content_relation` — 7 columns

`id uuid`, `tenant_id uuid`, `from_logical_id uuid`, `to_logical_id uuid`,
`to_version integer`, `kind varchar(16)`, `created_at timestamptz`.

- Constraints: primary key `content_relation_pkey`; CHECK `content_relation_kind_check`;
  no foreign key
- Indexes (3): `content_relation_pkey`, `ix_content_relation_from`,
  `ix_content_relation_to`
- Policy: `content_relation_tenant_isolation`, `cmd=ALL`
- Trigger: `content_relation_acyclicity` → `public.content_relation_acyclic_supersedes`
- `relrowsecurity = true`, `relforcerowsecurity = true`

### Structural boundary

- **Sequences: none** — the cluster carries zero sequences, so the question does not
  arise. (Confirms the earlier measurement.)
- **Indexes follow their table** under a schema change; measured previously as zero
  orphans.
- **Exactly one object does not follow**: the function
  `public.content_relation_acyclic_supersedes()`, `proconfig = none`. The trigger stays
  attached to the table while the function stays behind.
- **Foreign keys out of memory: one** — `memory.scope_id → scope`.
- **Foreign keys into memory: none.** No table in the schema points at `memory` or
  `content_relation`.

This document describes shape, not content: no row counts and no tenant data were
read, and no connection to a production database was opened.

---

## Open questions

Each of these became visible while measuring. None is answered here.

1. **How are the 27 contested types assigned?** The answer decides whether the memory
   side is 37.4 % or 63.6 % of the production code. No smaller sub-question exists that
   settles it.
2. **Where does `config.MemoryConfig` go?** It is service-wide configuration under a
   memory name, with two of nine members memory-specific and dependants on both sides.
   Splitting it, renaming it and moving it are three different answers.
3. **Where does `domain.Scope` go?** Memory hangs off it by foreign key; the platform
   directory view is defined over it. It is the only foreign key crossing the boundary.
4. **Do the eleven `tenancy.*` types move, get duplicated, or become shared?** An
   independent service already carries its own near-identical twelve, so duplication is
   the established precedent — but precedent is not a decision.
5. **Where does the erasure path go?** `TenantDataPurgeService` deletes memory and
   directory rows in one transaction. Splitting it turns one transaction into two, which
   is a durability question, not a packaging question.
6. **Where does `ScopeStatsRefresher` go?** It reads memory and writes the
   provider-facing projection in a single statement. It is the boundary object between
   the memory content and the provider's counts.
7. **Does the CI latch get a new subject, or does it travel with the memory?** The
   property the test proves is generic; the subject it uses is a memory row.
8. **Does the acyclicity guard get a gate before or after any move?** It has none today
   and its function resolves its table unqualified.
9. **Is the migration callback registered, and should it be witnessed?** Answering the
   first half requires a code change and was left undone here.
10. **Do the three `ee-server` production sites get realigned, or do the two
    `abuse-control` modules follow the memory?** Both currently bind by reflection over
    method identity and refuse to run unlimited when the seam moves.
11. **What happens to the mixed migrations?** Four of 21 create memory and directory
    objects in one file, including the very first. A build-to-specification approach does
    not inherit them, but the existing database was built by them.
