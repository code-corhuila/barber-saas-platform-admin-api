# barber-saas-platform-admin-api

> platform-admin bounded context: service API

Part of the **Barber Saas** distributed system — team `barber-saas`, Grupo 2.
Governance and documentation live in [`barber-saas-docs`](https://github.com/code-corhuila/barber-saas-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `barber-saas-docs`.

---

## BarberSaaS — what this repository is

The **platform-admin** service of BarberSaaS (`platform-admin-service.yaml` 1.4.0): the operation of
the SaaS by the `SUPER_ADMIN` — subscription plans, the barbershops of the platform (through
barbershop-api's internal operations, `DEC-SHOP-06`), the daily trial expiration for the worker
(FR-026) and the plan chosen at sign-up for the owner-onboarding saga (`DEC-PLAT-04`) — and, for the
owner, its subscription and the plan payments through Wompi in sandbox (ADR-017). Java 21 · Spring Boot 3.5 · three Maven modules (ADR-012):

```
platform-admin-core      domain and use cases, no framework
platform-admin-adapters  HTTP in (filters, controllers, error envelope), JDBC and HTTP out
platform-admin-app       the composition root and application.yml
```

### How to start it

As part of the platform: `./scripts/up.sh dev` in `barber-saas-infra-postgres`. Alone, without a
database (in-memory repositories): `mvn -pl platform-admin-app -am spring-boot:run` with
`JWT_PUBLIC_KEY` set; `.env.example` lists every variable.

### Plan payments (ADR-017)

| Operation | Contract |
|---|---|
| `GET /api/v1/subscription` | `ADMIN_BARBERSHOP`: plan, status, `trialEndsAt` and `paidUntil` of the token's barbershop |
| `POST /api/v1/payments` | `ADMIN_BARBERSHOP`, `Idempotency-Key`, body `{planId}`: a `PENDING` payment and Wompi's checkout URL; 422 for an inactive plan or one with fewer barbers than the barbershop has |
| `GET /api/v1/payments` | The owner's own payments; every payment (or `?barbershopId=`) for `SUPER_ADMIN` |
| `POST /api/v1/payments/wompi/events` | Public, called by Wompi: 401 unless the checksum matches; `APPROVED` assigns the plan, sets `ACTIVE` and adds 30 paid days; a repeated event changes nothing |

Sandbox only: `WOMPI_PUBLIC_KEY` (`pub_test_…`), `WOMPI_INTEGRITY_SECRET` and `WOMPI_EVENTS_SECRET` come
from the environment and are never versioned. Wompi must reach the events route: a free tunnel in
development, the public address of the deployment (ADR-019) otherwise.

### Where the data is

`platform_admin.subscription_plan`, `payment` and `idempotency_key` in the single PostgreSQL instance,
migrated by `barber-saas-platform-admin-db`; this service connects as `platform_admin_app`. Barbershops
belong to barbershop-api: this service never reads or writes their schema (ADR-004, ADR-011).

### How it is tested

`mvn -B verify`: the token verifier, the JSON body rules and the HTTP contract with the whole
service started on in-memory repositories.
