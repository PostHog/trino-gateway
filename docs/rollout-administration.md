# Fenced cell rollout administration

These APIs support a separately implemented deployment workflow. They do not
deploy Kubernetes resources, publish Git changes, authenticate canary queries,
or verify that an external deployment actually stopped. All replicas must enable
transaction awareness and run this version before an operation starts. Apply V9
first. Older replicas do not enforce operation ownership, so a mixed fleet must
not perform automated or manual cutovers.

## Authentication

All paths below are prefixed with `/gateway/transactions`. They retain the
Gateway's existing `API` role requirement and the separate configured transaction
administration capability. Existing `Authorization: Bearer <adminToken>` callers
remain compatible with their existing Gateway authentication configuration.

For form-authenticated deployments, configure a dedicated preset machine user
with only `API` privileges. Send preemptive HTTP Basic credentials and
`X-Gateway-Transaction-Admin-Token: <adminToken>`. This avoids a login request or
putting a session JWT into a deployment engine's state. Using the same token as
the machine password is one administrative capability, not two independent
authentication factors. Keep it in a secret reference, never a workflow output,
URL, log, Git document, or operation evidence. The API role alone is insufficient.
Transaction-administration role denials return HTTP 403. Other resources retain
their existing UI failure-envelope behavior.

## Read and compare-and-set routing

`GET /routes/{group}` returns `routingGroup`, `generation`, `backendName`, and
`backendIncarnation`. A route with no row has generation zero and null backend
fields. This GET does not create a route or change its generation.

`PUT /routes/{group}` requires:

```json
{
  "expectedGeneration": 7,
  "expectedBackendName": "blue",
  "backendName": "green",
  "backendIncarnation": "00000000-0000-0000-0000-000000000002"
}
```

The write checks the previous route and destination incarnation under the same
database transaction and routing-group lock. The destination must be active and
belong to the group. A success advances the route generation once. A stale retry
returns 409, including a blue-to-green-to-blue sequence. After a lost response,
only the exact intended tuple at the expected generation plus one can establish
that this write completed. Do not retry against an arbitrary newer generation.

`POST /backends/{name}/drain` also accepts optional `expectedIncarnation` and
`expectedGeneration` fields. Supply both for automation. The no-body legacy form
is retained. Drain status includes the stored `nodeId` and `coordinatorId` for
reincarnation recovery. A changed incarnation alone does not prove that the
workflow's intended replacement succeeded.

## Durable operation ownership

First establish the explicit source route during a controlled bootstrap. The
source must be active; do not bootstrap by guessing which backend currently owns
traffic. Then `POST /rollouts/{group}/acquire` with:

```json
{
  "operationId": "promotion-unique-id",
  "planHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "expectedRouteGeneration": 7,
  "sourceBackend": "blue",
  "sourceIncarnation": "00000000-0000-0000-0000-000000000001",
  "targetBackend": "green",
  "targetIncarnation": "00000000-0000-0000-0000-000000000002"
}
```

`planHash` is a SHA-256 identity of the caller's immutable deployment plan,
including pinned artifact, configuration, repository, and operation identities.
Gateway compares the hash; it cannot reconstruct or validate that external plan.
The target incarnation may be null only when no target ledger record exists.
Reusing an operation ID with a changed plan fails. Repeating the same acquisition
returns the existing operation, including a completed historical operation.

At most one unfinished operation owns a group. Ownership has no lease, expiration,
abort-release, or automatic takeover. A stalled publisher or uncertain outcome
keeps the operation blocked. A second promotion cannot acquire it. Query admission
and established transaction/query processing continue independently of ownership.

The operation response contains `routingGroup`, `operationId`, `plan`, `phase`,
`version`, `evidence`, and `publications`. `GET /rollouts/{group}` returns the
unfinished operation, or the latest completed one. Read responses contain no
credentials unless a caller incorrectly supplied credentials as evidence.

Every automated backend or route mutation must supply
`X-Gateway-Operation-Id` and `X-Gateway-Operation-Version`. Gateway checks ownership
and checkpoint version in the same database transaction as the mutation. A
stale operation is rejected even after completion. Without these headers, manual
mutations are rejected while an operation owns the group. Existing legacy
activation, destination changes, and deletion remain prohibited in transaction
mode. An operation must use CAS routing, never legacy cutover or route deletion.

## Checkpoints and publication claims

`PUT /rollouts/{group}/checkpoint` requires `operationId`, `expectedVersion`,
`phase`, and an object `evidence`. The version advances on each successful write.
Evidence adds immutable top-level keys: an existing value cannot be changed.
The complete stored evidence is limited to 64 KiB. Keep it to bounded identifiers
and proof references, not reports or credentials.

Phases are strictly ordered:

`CLAIMED → WARMED → VERIFIED → CUTOVER → DRAINING → SEALED → STOPPED → COMPLETE`

A same-phase checkpoint is allowed; skipping or reversing phases is not. A lost
response requires exact read-back of the operation version, phase, and immutable
evidence. Gateway checks committed destination routing before CUTOVER, source
draining before DRAINING, and the original source incarnation sealed before SEALED
or later phases. The workflow must independently verify exact Git revisions,
coordinator identity, authorization/catalog readiness, workers, and pod absence.
The evidence is a caller assertion, not a Gateway verification of external systems.

`POST /rollouts/{group}/publications/{warm|stop}/claim` requires `operationId`,
`expectedVersion`, and `planHash`. Warm is allowed only in CLAIMED; stop only in
SEALED. A first claim increments the version and permanently marks the publication
claimed. Every duplicate fails, including a duplicate with a refreshed version.
Claims cannot be cleared by checkpoints. Capture `warmPublication` or
`stopPublication` evidence with `branch`, `baseSha`, `headSha` (40 hexadecimal
characters), and positive integer `pullRequest` before advancing to WARMED or
STOPPED respectively. Capture it in the current phase before any merge wait.

A one-shot claim does **not** itself fence Git or Kubernetes. The external engine
must prove that claim, its single publication attempt, and the immutable-result
checkpoint cannot be replayed from an intermediate checkpoint. If that proof is
absent, do not use this API to claim safe publication. If a crash leaves an unknown
publication outcome, stop; do not claim again, recreate a branch, or force-push.

The source cannot resume or reincarnate while this operation owns it. Only the
target can resume/reincarnate during preparation. Hold ownership through the stop
publication, exact synchronization, and verified source pod absence. Only then
record STOPPED and COMPLETE. This prevents an authorized new resume from racing
the previous retirement workflow, provided external publication is correctly fenced.
Never automatically clear uncertain ledger rows or roll back a committed cutover.

## Reproducible tests

Run the `TestTransactionStore` and `TestTransactionAwarenessService` Maven tests.
The former accepts `TX_STORE_TEST_JDBC_URL`, `TX_STORE_TEST_USERNAME`, and optional
`TX_STORE_TEST_PASSWORD`; it creates and removes only its own randomly named schema.

For actual two-process HTTP tests, build Gateway classes, generate the dependency
classpath with Maven, and set `GATEWAY_TEST_CLASSPATH`, `GATEWAY_TEST_JAVA`, and
`GATEWAY_TEST_PG_BIN`. Run `python3 -m unittest -v test_rollout_api` from
`testing/transaction-awareness`. The fixture starts disposable loopback PostgreSQL,
two Gateway processes, and fake coordinator protocol servers. It does not prove
external publisher safety, a real coordinator rollout, or production readiness.
