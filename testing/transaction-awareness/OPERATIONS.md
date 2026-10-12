# Pooled Gateway operation

Gateway supports hostname-selected pools only. This document defines the
contract, not a deployment or execution receipt.

## Configuration

Use one authoritative PostgreSQL writer and consistent settings across replicas.

```yaml
routing:
  hostnameSuffixToPool:
    dw.example.test: pool-one
transactionAwareness:
  enabled: true
  identityKey: ${ENV:GATEWAY_TRANSACTION_IDENTITY_KEY}
  adminToken: ${ENV:GATEWAY_TRANSACTION_ADMIN_TOKEN}
  terminalRetentionSeconds: 120
  maxInFlightRequests: 16
  completionThreads: 4
  requestTimeoutMillis: 120000
  processInfoTimeoutMillis: 5000
  completionTimeoutMillis: 10000
  pool:
    enabled: true
```

Each key must contain at least 32 UTF-8 bytes. The two keys must differ. Generate
and store them outside source control. Do not rotate them with work in flight.
The administration token supplements the Gateway API-role check.

Missing, duplicate, or malformed Host returns 400. Unmapped Host returns 421.
Exact host mappings override suffix mappings. A suffix matches one tenant label,
not its apex or nested labels. Never infer a pool from a database name. Do not
use credentials, SQL, cookies, or X-Trino-Routing-Group for pool selection.

Preserve Host at the trusted ingress. Do not accept client forwarded headers at
the HTTP server. The proxy strips untrusted forwarding metadata and emits its
own coordinator-facing metadata. Trino authenticates the original credentials.
Host is not proof of authorization.

## Admission and ownership

Register backend metadata and use `/gateway/v1/pools/{pool}` to configure,
register, certify, and admit members. Only POOLED configuration is accepted.
Registration, a legacy active flag, and cached health do not grant admission.
The database serializes certified member admission with lifecycle transitions.

New independent work uses a randomized eligible-member list. Cached health only
orders candidates. Reselection is bounded and occurs before dispatch only.
No request is replayed on another coordinator after dispatch.

Transactions and queries retain durable owner and coordinator bindings. Every
routed hostname must identify the owning pool. Basic credential ownership also
includes the canonical host. Continuations use owner credentials or their
recorded result capability as defined by the protocol. Unknown ownership,
wrong-pool requests, forged capabilities, and changed coordinator identity fail
closed. Do not search arbitrary coordinators for an unknown query.

Coordinators must expose ready node and coordinator process identities through
`/v1/info`. A compatible coordinator build is required. Stock comparison images
without this identity contract cannot be admitted as members.

## Drain and retirement

Use the fenced pool lifecycle API. The old route override, fixed-slot cutover,
resume, reincarnation, and rollout administration APIs have been removed.

Draining stops new independent admissions. Already admitted statements, queries,
and transactions remain on their original member. Pending or uncertain requests,
open transactions, and undelivered results remain drain blockers. Cache expiry,
zero monitor query counts, elapsed time, and a client disconnect do not establish
completion.

Seal only after authoritative obligations permit it. Respect owner epochs,
operation replay receipts, serving floors, and retirement claims. Preserve
historical incarnations and ledger rows. Never clear uncertain state to permit
retirement. Coordinator loss still loses its in-memory transactions; Gateway
does not recover or migrate them.

## Capacity, timeouts, and failures

Each replica bounds managed query requests and reserves completion capacity.
Excess work is rejected before admission or dispatch. This is not a fleet-wide
throughput guarantee. The admission budget is bounded by requestTimeoutMillis
and routing.asyncTimeout. Identity probes and dispatch use the remaining budget.

After dispatch, timeout or cancellation does not release outcome processing.
Completion uses a request snapshot, not recycled servlet state. Persistence has
its own completion budget. A failed persistence attempt retains uncertainty;
it does not justify replay. Database outages fail admission and owner lookup
closed. Connection and JDBC timeout rounding can extend observed latency.

The PostgreSQL pool reserves connection capacity for completion. Configure its
connection, socket, statement, and lock timeouts through dataStore. These govern
Gateway metadata, not Trino query duration. Keep database lock ordering and
admission barriers compatible across replicas.

Auxiliary HTTP requests are not query or transaction completion receipts. Do not
use them to infer successful query delivery or drain safety. Optional upstream
OAuth affinity does not enable OAuth/Bearer admission through the Basic adapter.

## Upgrade and verification

Complete existing work before replacing the old routing version and config.
Remove routingRules, default-group, router-module, and route-cookie settings.
Do not mix old routing replicas with pool-only replicas. Do not change host
placement or ownership keys while work is in flight.

See [README.md](README.md) for supported fixtures and commands. Use independent
Gateway processes sharing PostgreSQL. Mock-only tests do not establish their
serialization. See [VALIDATION.md](VALIDATION.md) for the checklist and
[BASELINE.md](BASELINE.md) for comparisons. Keep actual environments, logs,
deployment results, and performance evidence private.
