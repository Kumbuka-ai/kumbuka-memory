---
type: concept
title: "The memory service's assistant surface: six verbs under the service's own prefix, answers that name the next step, and refusals built from declared patterns"
created: 2026-10-06
domain: memory
---

# The memory service's assistant surface

This document is the verb contract of the memory service for its assistant-facing surface (MCP),
on the service's own adapter. It is written against the service as measured on 2026-10-06 and
serves REQ-0084; its answer and refusal shapes follow DEC-0040, DEC-0041 and DEC-0042, and its
rules DEC-0043. The tool descriptions in section 5, the message patterns in section 4.4 and the
table in section 6 are normative text: the conformance probes of the service take their expected
values from this document, never from the code.

The memory service is the one service of the platform that had no assistant surface of its own.
An installation without the router reaches a service only through that service's own adapter, so
without one the memory of a community installation cannot be used by an assistant at all.

The generic surface (REST) keeps its verbs, its answers and its refusals exactly as they are. Where
it departs from the rules this contract follows, the departure is recorded in section 8 and is not
changed by this contract.

## 1. What the service holds

An **entry** is one text of at most 1500 characters, standing at an address:

```
memory://<scope>/<selector>/<id>
```

Its **key** is `<selector>.<id>`: lowercase letters, digits, dots and hyphens. The selector is a
lowercase letter followed by at most fifteen lowercase letters, digits, underscores or hyphens;
`system` is reserved for entries the service lays down itself.

An entry has a **type**, one of `constraint`, `decision`, `convention`, `glossary`, `status` and
`open_question`, and optionally a **reference**: a provenance URL that is stored and never fetched,
and that carries no credential.

An entry has no intermediate state. It is in force from the call that creates it until the call
that withdraws it; there is no draft, no hand-over and nothing that only somebody else can move.
That is why this surface carries the six verbs of the service and no process verbs beyond them.

## 2. Who may call

The platform's read contract answers, per scope, whether a caller may read and whether it may
write. A scope can be locked; a locked scope takes no write from anyone. There is no part a caller
takes in a single entry: whoever may write to the scope may change and withdraw every entry in it
that it may see.

## 3. The answer

Every answer about one entry has this shape (DEC-0040):

```
{
  "address": "memory://kumbuka/convention/branch-names",
  "fields": {
    "scope": "kumbuka", "key": "convention.branch-names", "type": "convention",
    "content": "...", "reference": null, "state": "...", "private": false,
    "created_at": "...", "updated_at": "..."
  },
  "conflict_token": "...",
  "next": [
    { "call": "memory_read",     "does": "Reads the entry again and hands out a fresh conflict token." },
    { "call": "memory_update",   "does": "Changes the content, the type or the reference; needs the conflict token." },
    { "call": "memory_withdraw", "does": "Takes the entry out of force; needs the conflict token." }
  ]
}
```

`next` lists exactly the calls this caller can make successfully on this entry (section 6): every
listed call succeeds for this caller, and every call that would succeed is listed. `memory_read` is
always among them, because an answer about an entry is proof that this caller may read it. The list
is therefore never empty, and `waiting_for` never arises on this surface.

**Every address is complete.** Every address anywhere in an answer or a refusal is the complete
URI, exactly as every call takes it.

**No argument is accepted and discarded.** Every call refuses an argument it does not declare, by
name, including an argument nested in `fields`; and every argument a call declares has the effect
its declaration states.

A **listing** (`memory_query`) carries `entries`, each in the shape above, and beside them
`truncated`, `total`, `page_size`, `cursor`, `order` and `unaddressable`.

A **withdrawal** answers with `address`, `outcome` (`destroyed` or `tombstoned`),
`address_released` and `next`.

A **digest** answers with `address`, `scopes`, `selected_types`, `summary` (per type: `type`,
`count`, `token_estimate`, `carried`), `total_entries`, `total_token_estimate`, `sections` (per
carried type: `type` and `entries`, each with `address`, `key` and `content`), `unaddressable` and
`truncated`.

## 4. The refusal

### 4.1 Shape

```
{
  "reason": "CONFLICT_TOKEN_STALE",
  "message": "memory_update is not possible on memory://kumbuka/convention/branch-names: the entry was changed since you read it. Read it again with memory_read and repeat the call with the new conflict token.",
  "data": {
    "attempted": "memory_update",
    "state": "...",
    "next": [ { "call": "memory_read", "does": "Reads the entry again and hands out a fresh conflict token." } ]
  }
}
```

`data.attempted` is present on every refusal. `data.state` and `data.next` are present where the
refusal concerns an entry this caller may see. A refusal of a stale conflict token additionally
carries the entry as this caller's own read would answer it (DEC-0041).

### 4.2 Rules for every message

- It names the call the caller made, under the name the caller used on this surface. Names from
  inside the service never appear.
- Every other call it names -- in the message and in `data.next` -- is named in the vocabulary of
  this surface. A pattern never names a call literally; it names a step, written below as
  `<create>`, `<read>`, `<update>`, `<query>`, `<digest>`, and the surface fills in its own call for
  that step.
- It names the complete address where there is one.
- It gives the specific reason in one sentence.
- It names what the caller can do instead; `data.next` lists the same calls.
- It never repeats the content of an entry, and it never repeats the value of a reference.
- It is built from the pattern alone. A sentence produced inside the service never reaches the
  caller, in whole or in part.

### 4.3 The one deliberately indistinguishable refusal

An entry that does not exist, an entry this caller may not see and a scope that cannot be resolved
are answered alike: reason `NOT_FOUND`, the one fixed message the service already answers on its
generic surface, and `data` carrying `attempted` and nothing else. Reason, message, data and the
way the refusal is transported are identical whichever of the three is the cause (DEC-0042).

### 4.4 Refusals of this surface

| Reason | Message pattern | What the caller can do |
|---|---|---|
| `NOT_FOUND` | 4.3 | check scope and address |
| `SCOPE_LOCKED` | `<call> is not possible in scope <scope>: the scope is locked and takes no write from anyone.` | `<read>`, `<query>`, `<digest>` |
| `SCOPE_READ_ONLY` | `<call> is not possible in scope <scope>: you may read this scope but not write to it.` | `<read>`, `<query>`, `<digest>` |
| `ADDRESS_MALFORMED` | `<value> is not an address this service carries. An entry stands at memory://<scope>/<selector>/<id>.` | correct the address |
| `KEY_MALFORMED` | `<key> is not a key this service stores. A key is <selector>.<id> in lowercase letters, digits, dots and hyphens.` | correct the key |
| `SELECTOR_RESERVED` | `The selector <selector> is reserved for entries the service lays down itself.` | use another selector |
| `SELECTOR_MISMATCHED` | `The key <key> begins with the selector <key selector>, but <call> names the selector <selector>.` | make the two agree |
| `ALREADY_EXISTS` | `<call> is not possible: an entry already stands at <address>. Change it with <update>.` | `<read>`, then `<update>` |
| `CONFLICT_TOKEN_STALE` | `<call> is not possible on <address>: the entry was changed since you read it. Read it again with <read> and repeat the call with the new conflict token.` | `<read>`, then repeat |
| `UPDATE_EMPTY` | `<call> on <address> names nothing to change. It can change content, type and reference.` | supply one of the three |
| `FIELD_IMMUTABLE` | `<field> of <address> is fixed for the life of the entry and cannot be changed by <call>.` | leave the field out |
| `TYPE_UNKNOWN` | `<value> is not a type of entry. The types are: <list>.` | use one of the list |
| `CONTENT_ABSENT` | `<call> needs content: an entry without content is not stored.` | supply content |
| `CONTENT_OVERSIZE` | `The content is <n> characters long. An entry holds at most <limit>.` | shorten it, or split it into several entries |
| `REFERENCE_CREDENTIAL_BEARING` | `The reference carries a credential and is not stored. Remove the user information and every secret parameter from the URL.` | correct the reference |
| `PAGE_SIZE_REJECTED` | `page_size = <value> is not valid for <call>: it is a whole number from 1 to <max>.` | correct the value |
| `CURSOR_MALFORMED` | `after = <value> is not a cursor <query> handed out.` | repeat `<query>` without `after` |
| `ACTOR_UNKNOWN` | `The token is valid but names no subject to act as.` | authenticate as a subject |
| `ARGUMENT_UNKNOWN` | `<call> has no argument named <n>. Its arguments are: <list>.` | correct the name |
| `ARGUMENT_MISSING` | `<call> needs <n>: <what it is>.` | supply it |
| `ARGUMENT_INVALID` | `<n> = <value> is not valid for <call>: <why>.` -- `<why>` is a sentence of the pattern's own | correct the value |
| `UNEXPECTED_FAILURE` | `<call> on <address> failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report reference <ref>.` -- for a call without an address, `<call> in scope <scope>`; `<ref>` is written to the service's log with the failure | report the reference |

On this surface the conflict token is a required argument wherever a call takes one, so its absence
is answered as `ARGUMENT_MISSING`; an argument that narrows a query and is not declared is answered
as `ARGUMENT_UNKNOWN`. A failure the service did not foresee, including a session the read contract
could not be bound for, is answered as `UNEXPECTED_FAILURE` and never as a sentence of its own.

Raised on the generic surface only, and declared in the catalogue all the same:
`CONFLICT_TOKEN_MISSING`, `PREDICATE_UNKNOWN`, `SELECTOR_ABSENT`, `PAYLOAD_MALFORMED`,
`VERB_NOT_CARRIED`, `ADDRESS_TRUNCATED`, `SESSION_NOT_BOUND`.

A reason that is in neither list cannot be returned: the service refuses to start with an undeclared
reason in its catalogue.

## 5. The verbs

Each entry gives the tool name, its description (normative), its arguments and who may call it.
Arguments follow DEC-0040: those that choose the target and the transport artefacts are top-level;
everything the call writes is under `fields`.

**`memory_create`**
> Lays down a new entry at a free address. The address is the scope and the key; the key begins
> with the selector. Refused if an entry already stands there: change it with memory_update.
> Callable by anyone who may write to the scope.

Top-level: `scope`, `selector`. Under `fields`: `key`, `type`, `content`, optional `reference`.
Result: the entry is in force.

**`memory_read`**
> Reads one entry by its complete address and hands out a fresh conflict token. Changes nothing.
> An address that does not exist and one you may not see are answered alike.

Top-level: `address`. Callable by anyone who may read the scope.

**`memory_update`**
> Changes the content, the type or the reference of an entry; its address is fixed for its life.
> Needs the conflict token of the latest read and is refused if the entry changed since. Callable
> by anyone who may write to the scope.

Top-level: `address`, `conflict_token`. Under `fields`: at least one of `content`, `type`,
`reference`. Result: the entry stays in force and carries a new conflict token.

**`memory_withdraw`**
> Takes an entry out of force. Final: no call restores it, and the answer says whether its address
> is free again. Needs the conflict token of the latest read.

Top-level: `address`, `conflict_token`. Callable by anyone who may write to the scope.

**`memory_query`**
> Lists the entries of a scope page by page, optionally narrowed by selector, type or text. Changes
> nothing. Use it to find an address.

Top-level: `scope`, optional `selector`, optional `type`, optional `text` (a text the entry must
contain), optional `after` (the cursor of the previous page), optional `page_size` (1 to 200,
default 50). Callable by anyone who may read the scope.

**`memory_digest`**
> Returns everything in force in a scope, whole and grouped by type, with a tally per type. Changes
> nothing. Carries the types the scope's digest selection names unless `types` says otherwise. Call
> it once at the start of a session.

Top-level: `scope`, optional `types` (a list of type names). Callable by anyone who may read the
scope.

## 6. How `next` is computed

| The answer is about | Condition | `next` |
|---|---|---|
| an entry in force | always | `memory_read` |
| an entry in force | the caller may write to the entry's scope at the moment of the answer | additionally `memory_update`, `memory_withdraw` |
| a withdrawal that released the address | -- | `memory_create` |
| a withdrawal that kept the address | -- | `memory_read` |

The `does` texts are the ones section 3 shows for the three calls on an entry in force. After a
withdrawal that released the address: `The address is free again; a new entry may be laid down at
it.` After one that kept it: `Reads the retired entry, which still stands at this address.`

The list is computed from the same answer that permits and refuses the calls. A second table beside
that answer would drift from it, and a listed call that is then refused is what the rule forbids.

## 7. What this contract requires of the service

- One declaration from which the tool list, the input schemas, the descriptions, the names in
  `next` and the reason catalogue are derived, served at `/mcp/declaration`.
- An adapter at `/mcp` that speaks `initialize`, `tools/list` and `tools/call`, authenticated the
  way the generic surface is, and that answers a refusal as a tool result marked as an error,
  carrying the envelope of section 4.1.
- Input schemas closed at every level.
- A reason catalogue covering every reason the service can raise on either surface, with the
  check at start that section 4.4 states.
- `UNEXPECTED_FAILURE` on every path of this surface that can fail unforeseen, with the report
  reference written to the log beside the failure.
- No change to the generic surface.

## 8. Open points and recorded departures

- **The generic surface departs from three rules and is left as it is.** Its messages are sentences
  written inside the service rather than built from a pattern; its refusals carry no `attempted`
  and no `next`; and it answers an undeclared narrowing argument as `PREDICATE_UNKNOWN`. Bringing
  it into line changes what a running installation answers and is a decision of its own.
- **The code for a write to an occupied address is not decided platform-wide** (DEC-0042, "What
  this does not settle"). This contract declares the code the service answers today,
  `ALREADY_EXISTS`.
- **The router's catalogue is not yet taken from this declaration.** Until it is, the descriptions
  on the two paths differ, and the router's `memory_query` does not carry the four narrowing
  arguments.
- **A service on its own offers no discovery of its authorization server.** An assistant can
  therefore not be connected to it by address alone, and the check that two assistants of different
  vendors complete the service's processes without a skill is unproven on this path.
- **Reading by technical address is not offered on this surface**; it stays on the generic one.
- **`relate` and `unrelate` are not carried.** The service holds no relation between entries.
- **Whether the key or the address of an entry may stand in a log line is not decided.**
