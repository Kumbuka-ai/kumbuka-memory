# kumbuka-memory

The memory service.

Curated entries, their scoping, their authorship, and the typed relations
between them.

## What is here today

The **substrate**: its own schema, its own database role, its own migration
sequence, row-level security on the tenancy axis, the read contract with the
platform's scope directory, and the operator boundary — plus a probe watching
each of them hold.

Since this release it also carries what a **deployment** needs: a container
image, and a health endpoint for the orchestrator.

**There is still no caller surface.** No MCP adapter, no REST resource, no
identity configuration. How an independent service authenticates an MCP
surface has no precedent in this platform and has to be designed rather than
copied; half of that design would get decided in passing by an OIDC block
written now. The service starts, migrates, holds its schema, and says whether
it is alive. That is the whole of it.

So this deployment is deliberately **empty**: the schema, the role, the grants
and the operator wall land before there is anything to lose, and the deploy
path is exercised while nothing can break.

## Configuration

Everything that names an environment is an environment variable with a
`MEMORY_` prefix. Every one has a development default pointing at localhost —
there is no deployment hostname anywhere in this repository, and none belongs
here.

**Two connections, deliberately different roles.**

| Variable | Default | What it is |
|---|---|---|
| `MEMORY_DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/kumbuka` | The database. Both connections use it. |
| `MEMORY_DB_USERNAME` | `kumbuka_memory` | The **runtime** role. Owns nothing, holds `USAGE` on this schema and the privileges V2 enumerates, and carries neither superuser nor `BYPASSRLS` — which is what makes the policies bind it. |
| `MEMORY_DB_PASSWORD` | `change-me-kumbuka-memory` | See the rotation note below. |
| `MEMORY_MIGRATOR_USERNAME` | `postgres` | The **migrating** role. `CREATEROLE` and `CREATE` on the database, and the owner of everything it creates. Never the role the service connects as: an owner can drop a policy and disable row-level security. |
| `MEMORY_MIGRATOR_PASSWORD` | `postgres` | |
| `MEMORY_TENANT_ID` | `00000000-0000-0000-0000-000000000001` | The tenancy axis for this deployment. Read by the tenant resolver and by the Flyway callback that binds it for migrations carrying DML. Deployment identity, not a knob. |

**Rotate the service role's password BEFORE the service first connects.** V2
creates the role with a placeholder, and the placeholder is in this public
repository. Rotating after the first connection is a repair; rotating before
it is a precondition, and only one of the two ever leaves a window.

## Health

`/q/health/live` and `/q/health/ready` on port 8080.

Readiness is the one worth asking: the datasource extension contributes a
check to it, so `UP` means the service reached the database **as its own
unprivileged role** — a rotated password, a missing `CONNECT` or an absent
role all show up there. `HealthEndpointIT` asserts the datasource check is
named in the payload, because without it the route answers `UP` with an empty
check list and an orchestrator would release a dependent on that answer.

**The port is not published at the edge.** It is reachable on the stack's
internal network and from nowhere else. Publishing it would expose a verb
surface that does not exist.

## Building and testing

```
cd backend
mvn verify
```

The suite runs with **no opt-in profile**, and it needs Docker: every
load-bearing statement this service makes is a statement about a running
PostgreSQL, and the tests start one themselves through Testcontainers. A gate
that has to be switched on is one that will be found switched off.

DevServices are deliberately disabled. A development datasource connects as a
superuser, a superuser bypasses row-level security unconditionally, and every
isolation assertion would then pass against a schema with the policies
deleted.

## Releasing

A release is cut from a **tag** and from nothing else; the version is derived
from the tag and read from no file. Pushing `vX.Y.Z` re-runs the full suite at
the tag, then builds and pushes
`ghcr.io/kumbuka-ai/kumbuka-memory:{tag, version, latest}`, then creates the
GitHub release — in that order, so a release never points at an image that was
not pushed.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE).
