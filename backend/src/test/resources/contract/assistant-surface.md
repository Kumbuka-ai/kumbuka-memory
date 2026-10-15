---
type: concept
title: "The memory service's assistant surface: six verbs under the service's own prefix, answers that name the next step, and refusals built from declared patterns"
created: 2026-10-06
domain: memory
---
<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

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

The generic surface (REST) keeps its verbs, its answers and its refusals exactly as they are, and
so does everything behind it. Where the generic surface departs from the rules this contract
follows, the departure is recorded in section 8 and is not changed by this contract.

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

A **listing** (`memory_query`) carries `objects`, each in the shape above, and beside them
`next_cursor` and `unaddressable`; it does not repeat the page size it was asked in (ADR-0049).
`next_cursor` is the one continuation: present, it is what `after` takes for the next page; absent,
the page is the last. It is bound to the scope -- by its technical identity, so a scope of the same
name in another estate is another scope --, the selector, the type and text, the sort and the
direction it was handed out for, and is refused for any other.

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
  "message": "memory_update is refused: the entry was changed since you read it. Read the entry again with memory_read and repeat the call with the new conflict token.",
  "data": {
    "attempted": "memory_update",
    "state": "...",
    "next": [ { "call": "memory_read", "does": "Reads the entry again and hands out a fresh conflict token." } ]
  }
}
```

`data.attempted` is present on every refusal. A refusal of a call's arguments, on every call, also
carries `data.offenders`, naming the arguments and never their values (DEC-0042): `type` for a type
`memory_create` or `memory_query` was sent, and for those of `memory_digest` the position of each
wrong one as the argument's path, such as `types[1]` (DEC-0043 R19). A refusal of a name the call
does not declare carries `data.accepts` instead, the names the call declares at that level, and
never the name that arrived (DEC-0043 R19); a refusal of a value outside a fixed set -- a type, an
order, a direction -- lists the declared values under `data.accepts` beside `data.offenders`. `data.state` and
`data.next` are present where the refusal concerns an entry this caller may see. A refusal of a
stale conflict token additionally carries the entry as this caller's own read would answer it
(DEC-0041).

### 4.2 Rules for every message

- It names the call the caller made, under the name the caller used on this surface. Names from
  inside the service never appear.
- Every other call it names -- in the message and in `data.next` -- is named in the vocabulary of
  this surface. A pattern never names a call literally; it names a step, written below as
  `<create>`, `<read>`, `<update>`, `<query>`, `<digest>`, and the surface fills in its own call for
  that step.
- It repeats no value the caller sent -- no address, scope, key, selector, type, cursor, page size
  or argument value -- and neither does any member of `data` (DEC-0042). It names the argument and
  the form that argument takes. A refusal is written to logs, a value can be content, and the
  caller holds what it sent.
- It gives the specific reason in one sentence.
- It ends with the remedy: the act the caller performs next, stated as what to do and never as
  what to avoid (DEC-0042). The remedy is the last column of the table below; `data.next` lists the
  calls it names.
- It never repeats the content of an entry, and it never repeats the value of a reference.
- Its message and its remedy are texts of the service's message bundles, and a placeholder in
  them takes only a call, an argument, a position or a bound the declaration builds (DEC-0043 R18).
- It is built from the pattern alone. A sentence produced inside the service never reaches the
  caller, in whole or in part.

### 4.3 The one deliberately indistinguishable refusal

An entry that does not exist, an entry this caller may not see and a scope that cannot be resolved
are answered alike: reason `NOT_FOUND`, the fixed message

```
nothing is addressed here. Check the address, and that you are a member of the scope it names.
```

which is the one the service answers on its generic surface, and `data` carrying `attempted` and
nothing else. Reason, message, data and the way the refusal is transported are identical whichever
of the three is the cause (DEC-0042).

### 4.4 Refusals of this surface

| Reason | Message pattern | What the caller can do |
|---|---|---|
| `NOT_FOUND` | 4.3 | Check the address, and that you are a member of the scope it names. |
| `SCOPE_LOCKED` | `<call> is refused: the scope is locked, and a locked scope takes writes from nobody. Read the scope with <read>, <query> or <digest>, and write to it once it is unlocked.` | Read the scope with `<read>`, `<query>` or `<digest>`, and write to it once it is unlocked. |
| `SCOPE_READ_ONLY` | `<call> is refused: your right in this scope is to read it. Read the scope with <read>, <query> or <digest>, and ask the scope's owner for the write right to change it.` | Read the scope with `<read>`, `<query>` or `<digest>`, and ask the scope's owner for the write right to change it. |
| `ADDRESS_MALFORMED` | `<call> was given an address or a scope in a form this service does not carry. Write an address as memory://<scope>/<selector>/<id>, and a scope in lowercase letters and digits, in segments joined by single hyphens.` | Write an address as memory://<scope>/<selector>/<id>, and a scope in lowercase letters and digits, in segments joined by single hyphens. |
| `KEY_MALFORMED` | `<call> was given a key or a selector in a form this service does not store. Write a key as <selector>.<id> in lowercase letters and digits, in segments joined by single dots or hyphens; its selector begins with a letter and runs to at most sixteen characters.` | Write a key as <selector>.<id> in lowercase letters and digits, in segments joined by single dots or hyphens; its selector begins with a letter and runs to at most sixteen characters. |
| `SELECTOR_ABSENT` | `<call> was given a key that names no selector. The part of a key before its first dot is the selector the entry stands under. Write the key as <selector>.<id>.` | Write the key as <selector>.<id>. |
| `SELECTOR_RESERVED` | `The selector of this key is reserved for entries the service lays down itself. Choose another selector for the key, such as decision, convention or constraint.` | Choose another selector for the key, such as decision, convention or constraint. |
| `SELECTOR_MISMATCHED` | `The key begins with another selector than the one <call> names. Make the selector argument and the part of the key before its first dot the same.` | Make the selector argument and the part of the key before its first dot the same. |
| `ALREADY_EXISTS` | `<call> is refused: an entry already stands at this address. Read the entry with <read> and change it with <update>, or create the new entry under another key.` | Read the entry with `<read>` and change it with `<update>`, or create the new entry under another key. |
| `CONFLICT_TOKEN_STALE` | `<call> is refused: the entry was changed since you read it. Read the entry again with <read> and repeat the call with the new conflict token.` | Read the entry again with `<read>` and repeat the call with the new conflict token. |
| `UPDATE_EMPTY` | `<call> names nothing to change. Send at least one of content, type and reference in fields.` | Send at least one of content, type and reference in fields. |
| `TYPE_UNKNOWN` | `<call> was sent a type outside the entry types, which accepts lists. Send each type as one of the values accepts lists.` -- `data.accepts` lists the entry types | Send each type as one of the values accepts lists. |
| `CONTENT_ABSENT` | `<call> needs content: an entry without content is not stored. Send the text of the entry as content.` | Send the text of the entry as content. |
| `CONTENT_OVERSIZE` | `The content is longer than an entry holds, which is at most <limit> characters. Shorten the content to at most <limit> characters, or split it into several entries.` | Shorten the content to at most <limit> characters, or split it into several entries. |
| `REFERENCE_CREDENTIAL_BEARING` | `The reference carries a credential and is not stored. Remove the user information and every secret parameter from the URL, then send it again.` | Remove the user information and every secret parameter from the URL, then send it again. |
| `PAGE_SIZE_REJECTED` | `page_size is outside what <call> takes. Send page_size as a whole number from 1 to <max>.` | Send page_size as a whole number from 1 to <max>. |
| `CURSOR_MALFORMED` | `after is not a cursor <query> handed out. Call <query> again from the first page, or send as after the next_cursor of the page you read last.` | Call `<query>` again from the first page, or send as after the next_cursor of the page you read last. |
| `CURSOR_FOREIGN` | `after was handed out by <query> for another scope, selector, type, text, sort or direction. Call <query> again from the first page.` | Call `<query>` again from the first page. |
| `SORT_UNDECLARED` | `sort names an order <call> does not declare. Send sort as one of the values accepts lists.` -- `data.accepts` lists the orders | Send sort as one of the values accepts lists. |
| `ACTOR_UNKNOWN` | `The token is valid but names no subject to act as. Authenticate as a subject and call again with that token.` | Authenticate as a subject and call again with that token. |
| `ARGUMENT_UNKNOWN` | `<call> was sent an argument outside the names it declares at that level, which are: <list>. Name each argument as one of these.` | Name each argument as one of these. |
| `ARGUMENT_MISSING` | `<call> needs <n>. Send <n> with the call.` | Send the argument with the call. |
| `ARGUMENT_INVALID` | `<path> is not of the form <call> takes for it. Send <path> in the form the input schema of the call gives it.` -- `<path>` is the argument, or the position of an element as `types[1]` | Send the argument in the form the input schema gives it. |
| `UNEXPECTED_FAILURE` | `<call> failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report the reference under data.report to the operator of this service.` -- `data.report` carries the reference, which is written to the service's log with the failure | Report the reference to the operator of this service. |

On this surface the conflict token is a required argument wherever a call takes one, so its absence
is answered as `ARGUMENT_MISSING`; an argument that narrows a query and is not declared is answered
as `ARGUMENT_UNKNOWN`; and a field that is fixed for the life of an entry is not an argument of any
call here, so naming one is answered as `ARGUMENT_UNKNOWN` as well. A failure the service did not
foresee, including a session the read contract could not be bound for, is answered as
`UNEXPECTED_FAILURE` and never as a sentence of its own.

Raised on the generic surface only, and declared in the catalogue all the same:
`CONFLICT_TOKEN_MISSING`, `FIELD_IMMUTABLE`, `PAYLOAD_MALFORMED`,
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
> Reads one entry by its complete address and hands out a fresh conflict token. Reads only. An
> unused address and one hidden from you are answered alike.

Top-level: `address`. Callable by anyone who may read the scope.

**`memory_update`**
> Changes the content, the type or the reference of an entry; its address is fixed for its life.
> Needs the conflict token of the latest read and is refused if the entry changed since. Callable
> by anyone who may write to the scope.

Top-level: `address`, `conflict_token`. Under `fields`: at least one of `content`, `type`,
`reference`. Result: the entry stays in force and carries a new conflict token.

**`memory_withdraw`**
> Takes an entry out of force. Final: the entry stays out of force, and the answer says whether its
> address is free again. Needs the conflict token of the latest read.

Top-level: `address`, `conflict_token`. Callable by anyone who may write to the scope.

**`memory_query`**
> Lists the entries of a scope page by page, optionally narrowed by selector, type or text. Reads
> only. Use it to find an address.

Top-level: `scope`, optional `selector`, optional `type`, optional `text` (a text the entry must
contain), optional `after` (the next cursor of the previous page), optional `sort` (id or
created_at, default id; created_at carries the key as second key), optional `direction` (asc or
desc, default asc), optional `page_size` (1 to 200, default 50). Callable by anyone who may read
the scope.

**`memory_digest`**
> Returns everything in force in a scope, whole and grouped by type, with a tally per type. Reads
> only. Carries the types the scope's digest selection names, or those that types names. Call it
> once at the start of a session.

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
  way the generic surface is on every route it has, and that answers a refusal of a tool call as a
  tool result marked as an error, carrying the envelope of section 4.1.
- A request that is not a call of a tool -- a body that is not JSON, a method or a tool the adapter
  does not have, `arguments` that are present and are not an object -- is answered as an error of
  the protocol, and not in the envelope of section 4.1.
- Input schemas closed at every level.
- A reason catalogue covering every reason the service can raise on either surface, with the
  check at start that section 4.4 states.
- `UNEXPECTED_FAILURE` on every path of this surface that can fail unforeseen, with the report
  reference written to the log beside the failure. The log receives the reference, the call, and
  the types and stack frames of the failure and of its causes. It never receives a message of the
  failure or of any cause: a database names the row it rejected, and that row holds the content.
- No change to the generic surface beyond what DEC-0042 binds on both surfaces: the code and the
  status of a refusal of a call's arguments, `offenders` naming arguments and never values, and no
  value the caller sent in any member of `data`.

## 8. Open points and recorded departures

- **The generic surface words its refusals from the patterns of section 4.4** (DEC-0043 R18), as
  this surface does, and repeats no value the caller sent in its message or in any member of
  `data`. Its refusals carry no `attempted` and no `next`. A refusal of the transport -- a body that
  is not JSON, a verb in colon notation the address does not carry, a method the address does not
  carry -- is raised before a verb is known, and its pattern names no call. A required argument
  absent or sent as `null` under `fields` is `ARGUMENT_MISSING` (400), as here (DEC-0043 R17). Its
  listing answers an undeclared argument as `ARGUMENT_UNKNOWN` (400), as this surface does, and so
  does an address that names one entry when it is sent a parameter, listing the parameters it
  accepts in `accepts`.
  This service declares no filter fields per scope, so it never answers `PREDICATE_UNKNOWN`.
- **A hop in front of the service can ask the generic listing for this surface's projection.**
  `GET` of a listing with the header `Kumbuka-Surface: assistant` answers as `memory_query` does
  here: `next` names this surface's calls and a refusal is built from the patterns of section 4.4,
  with the generic surface's HTTP status. Any other value of the header, an empty one or a second
  one, is refused as `ARGUMENT_INVALID` with 400 and no `data`, before the scope is looked at, so a
  scope the caller cannot see answers the same: the header is not an argument, and whether its value
  is one it takes is decidable without a scope. Only the listing reads the header so far.
- **The code for a write to an occupied address is not decided platform-wide** (DEC-0042, "What
  this does not settle"). This contract declares the code the service answers today,
  `ALREADY_EXISTS`.
- **A reference can be changed and cannot be removed.** The service has no way to express the
  removal. On this surface an empty `reference` on `memory_update` is refused as
  `ARGUMENT_INVALID` rather than accepted without effect.
- **The selector may carry an underscore and the key may not**, so a selector with an underscore
  never yields a key the service stores.
- **No edition of the open core keeps the address on withdrawal.** The last row of the table in
  section 6 is declared and cannot be observed there.
- **The router's catalogue is not yet taken from this declaration.** Until it is, the descriptions
  on the two paths differ, and the router's `memory_query` does not carry the four narrowing
  arguments.
- **A service on its own offers no discovery of its authorization server.** An assistant can
  therefore not be connected to it by address alone, and the check that two assistants of different
  vendors complete the service's processes without a skill is unproven on this path.
- **Reading by technical address is not offered on this surface**; it stays on the generic one.
- **`relate` and `unrelate` are not carried.** The service holds no relation between entries.
- **Whether the key or the address of an entry may stand in a log line is not decided.**
- **A stale conflict token is the one refusal that carries an address the caller sent.** It
  carries the entry as this caller's own read would answer it (DEC-0041), and a read answers with
  the entry's address. DEC-0042 says no member of `data` repeats a value the caller supplied; the
  two are not yet reconciled, and the entry is carried as DEC-0041 requires.
- **The `does` of a next step on this surface describes the call rather than instructing it.**
  DEC-0042 asks for the description of every next step as the act to perform. These texts are the
  calls' own declarations, and rewording them belongs to the decision on how the tool descriptions
  are worded and kept; the generic surface's `next` already states its steps as instructions.
