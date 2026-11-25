<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# Embedding the permission check in a service

Every service checks, on every call, one thing in one place: does the caller hold the permission
the called method declares, and is the scope the call addresses one the caller may address. The
check is not written per service. It is the open security contract and its Quarkus binding, which
kumbuka-server publishes, and each service embeds them. This document describes how the memory
service did it, step by step, so that the next service -- a document service first -- is built
that way from its first commit.

What the service writes itself is small: the facts of a call (who calls, which permissions they
hold, which scopes they may address), the mapping from roles to its permissions, one annotation per
method, and the tests. What it must not write is a second check: no method looks at roles,
permissions or a write right of its own. Object rules -- a locked scope, a private entry, a
conflict token -- stay the service's and are decided after the check, never instead of it.

## 1. Dependencies and the repository

The contract and the binding are two artefacts, released together:

```xml
<properties>
    <kumbuka-security.version>0.13.0</kumbuka-security.version>
</properties>

<dependency>
    <groupId>ai.kumbuka</groupId>
    <artifactId>kumbuka-security-contract</artifactId>
    <version>${kumbuka-security.version}</version>
</dependency>
<dependency>
    <groupId>ai.kumbuka</groupId>
    <artifactId>kumbuka-security-quarkus</artifactId>
    <version>${kumbuka-security.version}</version>
</dependency>
```

- `kumbuka-security-contract` is plain Java: `CallFacts`, `ScopeGrant`, `PresentedCredential`,
  `AuthenticationContext`, the interface `CallFactsSource`, and the refusal reasons.
- `kumbuka-security-quarkus` is the binding: the augmentor that fetches the facts of every
  authenticated call and attaches the one check to the call's identity, the annotations
  `ScopePermission` and `ScopelessPermission`, the request-scoped `CurrentCallFacts`, a policy that
  refuses an anonymous call on every path but `/q/health` and `/q/metrics`, and the
  `FacadeConformanceGuard` for the tests. It sets `quarkus.security.deny-unannotated-members=true`,
  so a public method of a bean that declares any permission and declares none itself is refused at
  run time. It carries a Jandex index, so the service needs no index entry for it. Quarkus itself is
  `provided`: the service brings its own.

Both are resolved from GitHub Packages of `Kumbuka-ai/kumbuka-server`. Declare Maven Central first
and with the id `central` -- a repository a POM declares is asked before the super-POM's, so
without it every missing artefact is asked of GitHub Packages first and answered 401 -- and then
the packages repository:

```xml
<repositories>
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/Kumbuka-ai/kumbuka-server</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

GitHub Packages authenticates every read, a public package's included. Give the repository the id
your release workflow already authenticates for its deploy (`github` here), so that the release
resolves the check with the credentials it has and no second id needs credentials. A local build
needs a personal token with `read:packages` in `~/.m2/settings.xml` under the same id; without it
the build fails at resolution with `401 Unauthorized`.

## 2. The CI permission

The job that builds gets the package permission and the job's own token, and nothing else:

```yaml
jobs:
  backend:
    permissions:
      contents: read
      packages: read
    steps:
      - uses: actions/setup-java@<pinned commit>
        with:
          distribution: temurin
          java-version: '21'
          server-id: github
          server-username: MAVEN_USERNAME
          server-password: MAVEN_PASSWORD
      - name: mvn verify
        env:
          MAVEN_USERNAME: ${{ github.actor }}
          MAVEN_PASSWORD: ${{ secrets.GITHUB_TOKEN }}
        run: mvn -B -ntp verify
```

`setup-java` writes a `settings.xml` that authenticates exactly the id the POM declares. No secret
beyond `GITHUB_TOKEN` is named. A job-level `permissions:` block replaces the defaults, so list
`contents: read` for the checkout beside `packages: read`.

## 3. The facts of a call

The binding ships no `CallFactsSource`; a service that embeds it without one does not build. The
open core of the service carries the community implementation as a `@DefaultBean`, so that an
edition with its own way of establishing facts replaces it without touching the core. In memory it
is `platform.CommunityCallFacts`; it establishes, per call:

- **subject**: the token's principal (pin `quarkus.oidc.token.principal-claim=sub`);
- **roles**: the realm roles of the token, `realm_access.roles`;
- **permissions**: what those roles grant through the role mapping (step 4), across the tenant;
- **scopes**: every scope of the platform's scope view under the tenant and subject binding the
  service uses for every read of that view, each as a `ScopeGrant` with the scope's id and its slug
  -- carry both, so that no facade ever has to resolve one into the other;
- **tenant**: the tenant the installation resolves;
- **actor**: empty -- the community edition knows no delegated identity;
- **channel and authentication context**: as far as the token carries them (`azp`; `amr`, `acr`,
  `auth_time`), with a neutral value where it does not. No decision reads them.

The binding calls the source once per authenticated call, off the event loop, so it may read the
database; give the scope read its own transaction under the service's tenant binding. A source
that cannot establish facts returns empty, and every declared method refuses the call. A source
that throws fails the call (the binding of 0.13.0 answers that with 500).

The scope read is one more native statement against the view; if the service counts its native
statements in a test, count it and write its reason there.

## 4. The role mapping, and its catalogue test

The mapping from the platform's system roles to the service's permissions is code, not
configuration: in the community edition the system roles are fixed, there are no roles of a
tenant's own and no roles per scope. In memory it is `platform.SystemRoles`:

| Role | Permissions |
|---|---|
| `owner` | `memory.entry.read`, `memory.entry.create`, `memory.entry.update`, `memory.entry.withdraw`, `memory.entry.tag` |
| `memory-reader` | `memory.entry.read` |
| `memory-author` | `memory.entry.create`, `memory.entry.update`, `memory.entry.tag` |
| `memory-curator` | `memory.entry.withdraw` |

Every other role -- the administration role, the roles of the other services -- grants nothing
here. Permissions have the form `service.object.act`; the contract refuses facts with any other.

The table comes from the platform's permission catalogue, and a test holds the mapping against it
(`architecture/PermissionCatalogueTest`): the catalogue's rows written out in the test, the commit
of the catalogue they are taken from named in the test, every role of the table granting exactly
its row, the roles without a permission here granting none, and the mapping naming no role beyond
the table. The expectation is never read from the mapping.

## 5. The declarations

Put the declarations on the classes every call passes through, and only there, so that each verb
method is declared exactly once and no adapter reaches the domain past them. In memory these are the
domain services (`MemoryService`, `DigestService`): both adapters call the verb surface, and the
verb surface calls only these. The verb surface declares nothing, because it holds the grammar of
an address, and the grammar has to answer before the scope is looked at: the check runs before the
method body, so a declaration on the verb surface would answer a malformed address on a scope the
caller cannot see with `NOT_FOUND` instead of the grammar's own refusal.

A method that addresses a scope names the argument that carries the scope's slug. The argument is a
`String` parameter, named exactly as `params` says (compile with `-parameters`):

```java
@Transactional
@PermissionsAllowed(value = MemoryPermission.UPDATE, permission = ScopePermission.class,
    params = "scope")
public Written<EntryView> update(Actor actor, String scope, String entryKey,
                                 @Nullable String conflictToken, Patch patch) { ... }
```

A method that addresses no scope says what it addresses after a colon -- the tenant or nothing --
and there is no default:

```java
@PermissionsAllowed(value = MemoryPermission.READ + ":tenant",
    permission = ScopelessPermission.class)
public EntryView readByLogicalId(Actor actor, UUID logicalId) { ... }
```

Keep the permission names as constants in one class (`domain.MemoryPermission`), because an
annotation takes a constant.

**A method whose scope is known only once its object is loaded** (memory's read by technical
address) declares the tenant, finds the object, and then reads it through the bean's own proxy by
the method that declares the scope (`self.read(actor, slug, key)` with `@Inject MemoryService
self`). The check then decides the loaded scope as it decides a named one -- a scope outside the
set is answered as one that does not exist -- and the service has made no check of its own.

**Something that reads further scopes along** (memory's digest reads the global scopes) takes them
from the scope set of the facts (`CurrentCallFacts.facts().scopes()`), never from its own reading
of the view.

**What the check answers, and how the adapters answer it.** A scope outside the set fails with the
framework's `NotFoundException`; answer it exactly as a scope that does not exist -- same status,
same body. Its reason is written to the log by the binding and nowhere else. Every other refusal
fails with `io.quarkus.security.ForbiddenException`; map it at the adapters' edge, once for both
surfaces, to the service's own code from the same facts. Memory answers a caller with no facts, no
permission of the service or no scope at all with `NOT_FOUND` (it sees no scope of the service), a
caller whose one permission is to read with `SCOPE_READ_ONLY`, and every other caller with
`ROLE_INSUFFICIENT` (403). Count nothing per identity: a refusal is logged by its code, never by
who was refused.

**The next steps an answer offers** are computed from the same facts: carry the permissions the call
holds on the projection beside the object rules, and list a call only where its permission is held
and no object rule refuses it. That decides what is offered, never what is allowed.

## 6. The guard

The binding's `FacadeConformanceGuard` runs in the tests over the declared classes, with the
catalogue as the expectation (`architecture/FacadeConformanceTest`): one `CatalogueEntry` per
method name, with its permission and what it addresses (`new ScopeBinding.Scope("scope")`,
`new ScopeBinding.Tenant()`, `new ScopeBinding.None()`). It reports a public method without a
declaration, a method without a row, a declaration that is not exactly one `ScopePermission` or
`ScopelessPermission`, a wrong permission, a wrong or missing scope binding, and a row without a
method. The rows are written in the test from the catalogue, never derived from the classes.

## 7. Tests under roles

With `quarkus.oidc.tenant-enabled=false` and `@TestSecurity`, a test identity carries no token,
and Quarkus applies no augmentor to it. Memory's test tree carries one `TestSecurityIdentityAugmentor`
bean (`realm/TestSubjectToken`) that gives the identity a token -- the subject as `sub`, the
annotation's roles as `realm_access.roles` -- and hands it to the binding's own augmentor, so that
the real facts source decides every test call. Every test then names its roles:

```java
@TestSecurity(user = SubstrateDatabaseResource.PROBE_SUBJECT, roles = "memory-reader")
```

The facts are established once per test method, when its identity is built, and serve every call
of the method over HTTP and to a bean alike. A test class reaching the platform's scope view must
grant it before the methods run.

Convert every test that decided writing by the view's write right to roles; do not delete one.

## 8. Red runs

Every new gate is seen red once, and keeps planted cases red on every build:

- `PermissionCatalogueTest` and `FacadeConformanceTest` carry a planted mapping and a planted
  facade, each reported for every offence the check exists for, on every build
  (`the_checks_report_each_offence_they_exist_for`).
- Seen red once by breaking the production code: a public method added to a declared class without
  a declaration (the guard reports `UNDECLARED` and `NOT_IN_CATALOGUE`); a role mapping that
  departs from the table (the catalogue test reports the row); and the end-to-end probes
  (`surface/PermissionCheckIT`): a subject holding `memory-reader` alone refused every write and
  answered every read; an agent's roles (`dispatch-reader`, `memory-reader`, `worklist-reader`)
  reading and refused update and withdraw; a scope outside the set answered byte for byte as a
  scope that does not exist, with the reason in the log; facts with an empty scope set refused; a
  subject holding `memory-author` refused by the lock of a locked scope after the permission.
