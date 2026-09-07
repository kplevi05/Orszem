# Backend layering

One rule, applied to every business module:

```
api  ──►  application  ──►  domain  ◄──  infrastructure
```

Dependencies point inwards. `domain` depends on nothing.

## The layers

**`api`** — HTTP. Controllers, request and response DTOs. A controller maps an HTTP request
to a use case, maps the result back, and does nothing else. No business rules, no
repository calls, no orchestration. Response DTOs are separate types from application
results, so the HTTP contract can change without disturbing the layers beneath.

**`application`** — use cases. One class per meaningful operation, named for what it does
(`CreateReport`, `ArchiveReport`), holding orchestration and transaction boundaries. It
depends on `domain` types and on interfaces the domain declares — never on Spring MVC or a
concrete repository.

**`domain`** — the model and its rules. Entities, value objects, invariants, state
transitions, and the repository *interfaces* the domain needs. **This layer must not
depend on Spring MVC, JPA, or any HTTP or persistence type.** A domain rule should be
testable with no framework at all.

**`infrastructure`** — implementations of what the domain declares: repositories, external
clients, mappers. This is where the database, JDBC, HTTP clients and third-party libraries
live.

## Why controllers stay thin

The most common way a codebase like this rots is business logic accumulating in
controllers, because that is where the request first arrives. Once a rule lives in a
controller it can only be exercised over HTTP, it cannot be reused by a second entry point,
and it is invisible to anyone reading the domain. Keep them mapping-only.

## Module shape

Modules are vertical slices, not horizontal layers across the whole application:

```
hu.orszembejelento.backend.<module>
├── api
├── application
├── domain
└── infrastructure
```

Cross-module calls go through the other module's `application` layer, never directly into
its `domain` or `infrastructure`.

## Planned modules

`auth`, `users`, `reports`, `routing`, `moderation`, `analytics`, `audit`, plus `common`
for genuinely cross-cutting concerns.

These are **intent, not scaffolding.** None exists yet as an empty package. A module is
created when it has real behaviour, so the tree always reflects what the system actually
does. Hundreds of empty classes anticipating a design are a cost, not a head start.

## The Phase 1 reference slice

`meta` is the worked example, at two files:

- `meta/api/MetaController.kt` — maps `GET /api/v1/meta`, holds no logic
- `meta/application/GetApiMetaUseCase.kt` — returns the result type the controller maps

It has no `domain` or `infrastructure` because it has no rules and no persistence. That is
the point: layers appear when they carry something, not by default.

## `common`

`common/config` and `common/web` hold configuration and the single `/api/v1` path constant.
`common` is for genuinely cross-cutting infrastructure only. It is **not** a `utils`
dumping ground, and business logic never lands there.
