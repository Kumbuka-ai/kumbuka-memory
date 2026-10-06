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

**Two caller surfaces, carrying the same six verbs** — create, read, update,
withdraw, query and digest. An entry stands at `memory://<scope>/<selector>/<id>`
and has no intermediate state: it is in force from the call that creates it
until the call that withdraws it.

- **The generic surface (REST)**, under `/api`. An address maps onto the path
  (`/api/<scope>/<selector>/<id>`), the compound verbs are written in colon
  notation (`:withdraw`, `:digest`), and the conflict token travels as `ETag`
  and `If-Match`. This is the surface the platform's router calls.
- **The assistant surface (MCP)**, at `/mcp`: JSON-RPC 2.0 speaking
  `initialize`, `tools/list` and `tools/call`, with six tools `memory_create`,
  `memory_read`, `memory_update`, `memory_withdraw`, `memory_query` and
  `memory_digest`. It is what lets an installation without the router — the
  community edition on its own — connect an assistant to its memory at all.

On the assistant surface the tool list, the input schemas, the descriptions,
the names offered as next steps and the catalogue of refusal reasons all come
from **one declaration**, served at `GET /mcp/declaration`. Every input schema
is closed at every level, so an argument the call does not declare is refused
by name rather than dropped. Every refusal is `{ reason, message, data }`, and
its message is built from the reason's declared pattern alone: no sentence from
inside the service, no content of an entry and no value of a reference reaches
the caller through it. A failure nobody foresaw answers `UNEXPECTED_FAILURE`
with a report reference that stands in the log beside the failure. **The service
does not start** when it can raise a reason the catalogue does not declare.

Both surfaces are authenticated the same way: a bearer token validated by this
service, the acting identity derived from its `sub` claim and never from an
argument. What a caller may do is the platform's read contract, per scope.

What the assistant surface does not do yet: it offers no discovery of its
authorization server, so a client has to be configured with a token rather
than connected by address alone; reading by technical address stays on the
generic surface; and the service holds no relations between entries to carry.

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

**Identity.** Both caller surfaces validate the same token against the same
settings; the assistant surface adds none of its own.

| Variable | Default | What it is |
|---|---|---|
| `MEMORY_OIDC_ISSUER` | `http://localhost:8180/realms/kumbuka` | The issuer whose tokens are accepted. |
| `MEMORY_OIDC_CLIENT_ID` | `kumbuka-memory` | This service's client in that realm. |
| `MEMORY_OIDC_AUDIENCE` | `https://platform.kumbuka.ai/mcp` | The audience a token must carry. |

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
internal network and from nowhere else. It carries both caller surfaces as well
as the health routes.

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
