# Hostname-only pooled routing

In pooled mode, Gateway selects new independent work using only one `Host`
header. It ignores
`X-Trino-Routing-Group`, `Forwarded`, `X-Forwarded-*`, credentials, and query
properties for pool selection. Authentication and authorization stay on Trino.
Existing transactions and query continuations must retain their durable owner;
hostname selection must not redistribute in-flight work.

```yaml
routing:
  hostnameSuffixToPool:
    dw.example.test: pool-one
  hostnameToPool:
    warehouse-two.dw.example.test: pool-two
```

Exact hostname entries take priority. A suffix entry matches exactly one DNS
label before the suffix, such as `warehouse-one.dw.example.test`. It does not
match the apex `dw.example.test` or `a.b.dw.example.test`. Add an explicit exact
entry if an apex or operational hostname should route to a pool.

Only use a suffix mapping when all tenants under that domain share its pool.
A database-name label is not a pool identifier. Where tenants have distinct
durable placements, generate exact hostname entries from the control plane's
stored warehouse-to-pool assignments. Never infer placement from database-name
spelling, credentials, SQL, or an unverified request header. Publish a consistent
configuration across replicas before admitting new work at a new placement.

The selector lowercases ASCII DNS names and removes one root-zone trailing dot.
A request may include a decimal port from 1 through 65535; the port does not
change pool selection. Configuration keys cannot include ports or wildcards.
Malformed names, empty pool identifiers, and duplicate canonical configuration
keys fail provider creation. Configuration is immutable for that selector;
changes require replacing the running configuration, not refreshing a rules file.

Missing, duplicate, or malformed Host values return HTTP 400. An otherwise valid
hostname without a matching mapping returns HTTP 421. Empty maps deny all
selection. There is no header, default-group, file-rule, or external-router
fallback. Stock `routingRules` configuration, rule management endpoints, and UI
are retained for stock deployments, but cannot select compute in pooled mode.

## Proxy trust boundary

The public proxy must preserve the routed Host. Do not enable server-side
forwarded-header processing for untrusted callers: it can replace the hostname
before the selector sees the request. Discard client `Forwarded` and
`X-Forwarded-*` values and synthesize coordinator-facing forwarding metadata from
the same validated public host and fixed TLS termination configuration. Restrict
direct coordinator access. Host remains client-controlled routing information,
not authentication evidence. Gateway's tenant-principal admission restriction
must continue to match the coordinator's own qualification and authorization.

See the reproducible regression tests in `TestHostnameRoutingGroupSelector`.
Run them with Java 25:

```shell
./mvnw -pl gateway-ha -am -Dtest=TestHostnameRoutingGroupSelector \
  -Dsurefire.failIfNoSpecifiedTests=false -Dskip.pnpm test
```

## Configuration and upgrade boundary

Enable both flags explicitly for the pooled deployment:

```yaml
transactionAwareness:
  enabled: true
  identityKey: ${ENV:GATEWAY_TRANSACTION_IDENTITY_KEY}
  adminToken: ${ENV:GATEWAY_TRANSACTION_ADMIN_TOKEN}
  pool:
    enabled: true
```

Both flags default to `false`, preserving stock upstream routing. They are not
request-level fallbacks or emergency bypasses for an active pooled deployment.
Pooled mode requires PostgreSQL, separate shared identity and administration
keys, and coordinators exposing the process identity used by the pool protocol.
Each key must contain at least 32 UTF-8 bytes and the keys must differ.

Keep stock selectors and route-cookie settings disabled for this deployment.
Register and certify pool members through the pool API; an active registration
alone is not eligible. Custom fixed-backend cutover and rollout administration
APIs remain removed, independently of the stock-routing feature flags.

Use a coordinated Gateway upgrade and complete existing work before changing
routing mode. Ownership includes the canonical request host; old credential
bindings are not interchangeable with new bindings. Do not mix routing versions
or move a tenant's host mapping with work in flight. Never delete unresolved
ledger rows to permit an upgrade. Historical Flyway migrations and pool lock
rows remain required.


## Pooled routing behavior

### Hostname-only pool selection

Every proxied request must present one valid `Host` header that identifies a
configured pool. [Hostname routing](#hostname-only-pooled-routing) describes exact-host and
single-tenant-label suffix mappings, normalization, and rejected authorities.
Host chooses placement; it never authenticates a tenant. A database-name label
must map to the control plane's durable pool assignment, not an inferred pool.

There is no default-group fallback, routing-rule engine, external selector,
client `X-Trino-Routing-Group` selection, or cookie-based route selection. A
missing pool or a pool without eligible members fails closed. Legacy backend
activation and a healthy cache entry cannot bypass pool admission.

### New independent statements

For a statement without an existing transaction, Gateway obtains the pool's
current admitted members from PostgreSQL. Member activation requires the pool
lifecycle's validation receipt, required revisions, and observed coordinator
process identity. Gateway checks current pool mode, membership, phase, and the
tenant admission restriction inside the admission transaction before dispatch.
The validation certificate authorizes lifecycle admission; it is not a TLS
certificate or a substitute for credential verification.

Candidate ordering is shuffled, then may prefer members with healthy cached
status. Health is advisory: it cannot admit an uncertified member or reject an
otherwise eligible member merely because its cached status is unknown or stale.
Bounded reselection is allowed only before dispatch, within the original request
deadline. After dispatch, Gateway does not replay or move a statement whose
acceptance or outcome is uncertain.

#### Load-aware ordering

Set `routing.loadAwarePooledRoutingEnabled: true` to order members by load. The
default is `false`. When the flag is off, ordering does not change.

```yaml
routing:
  loadAwarePooledRoutingEnabled: true
```

Gateway calculates the load of each member from its last cluster statistics:

```text
load = queued + running * (1 + memoryPressure)
memoryPressure = reservedQueryMemory / maxQueryMemory, clipped to [0, 1]
```

Gateway keeps healthy members first. Then it uses "power of two choices": it
draws two random members of the best health tier, independently, and tries the
member with the lower load first. Unknown load is the highest load. The fallback members for pre-dispatch
reselection follow in health and load order.

Gateway does not always select the member with the lowest load. Load data can be
older than one refresh, and all Gateway replicas see the same data. With strict
lowest-load order, all new statements go to one member until the next refresh.
Two random draws spread new statements, also in a pool of two members. A member
with more load gets fewer new statements. In a tier of N members, the member with
the most load is tried first for about 1 in N² new statements.

Load is advisory, as health is:

- Running queries always count at full weight. CPU-bound queries can reserve
  almost no memory, but they still use the coordinator. Memory pressure adds up
  to one more unit per running query.
- If memory statistics are not known, the memory pressure is `0`, and the load
  is `queued + running`.
- A member without statistics sorts after members with statistics. It stays
  eligible.
- Missing statistics never cause a routing failure.

Statistics refresh at the interval `monitor.taskDelay`.

The `JMX` monitor reads memory from the
`trino.memory:type=ClusterMemoryPool,name=general` MBean when the flag is on. The
`METRICS` monitor reads memory only when you set
`monitor.reservedQueryMemoryMetricName` and `monitor.maxQueryMemoryMetricName`.
Other monitors do not supply memory statistics. Trino exports the memory pool
on `/metrics` only if `openmetrics.jmx-object-names` includes the MBean.

Members that report memory statistics never have a lower load than identical
members that do not. Members without memory statistics can have a lower load,
because their memory pressure counts as `0`. Configure all members of a pool the
same way.

#### Prometheus load source

The `INFO_API` monitor gives health only. It gives no load, so all members have
equal load. To get load without coordinator credentials, read it from a
Prometheus-compatible HTTP API that already scrapes the coordinators:

```yaml
monitor:
  loadPrometheusUrl: http://prometheus.example.com:9090
  loadQueuedQueriesQuery: >-
    max by (backend) (label_replace(trino_execution_querymanager_queuedqueries{pool="example"},
    "backend", "example-$1", "instance_name", "(.+)"))
  loadRunningQueriesQuery: >-
    max by (backend) (label_replace(trino_execution_querymanager_runningqueries{pool="example"},
    "backend", "example-$1", "instance_name", "(.+)"))
  loadReservedMemoryQuery: >-
    max by (backend) (label_replace(trino_memory_clustermemorypool_reserveddistributedbytes{pool="example",name="general"},
    "backend", "example-$1", "instance_name", "(.+)"))
  loadMaxMemoryQuery: >-
    max by (backend) (label_replace(trino_memory_clustermemorypool_totaldistributedbytes{pool="example",name="general"},
    "backend", "example-$1", "instance_name", "(.+)"))
  loadQueryTimeout: 5s
```

Change the selectors and labels in the example to match your scrape setup.

- Gateway sends `POST <loadPrometheusUrl>/api/v1/query`, with the query in a
  form body, once for each query in each refresh, independent of the number of
  members. The queries run at the same time. After the health checks, a refresh
  waits at most `loadQueryTimeout` for all of them. A proxy in front of the
  server must allow `POST` with a form body.
- Each query must return an instant vector. Each series must have a `backend`
  label equal to the Gateway backend name. Use `label_replace` to make this label.
- The configured `monitorType` still decides health. The load source never
  changes health.
- The queued and running queries are one pair. If a member has no value for one
  of them, its load is unknown. It is the highest load, and it sorts last in the
  fallback order. If no backend has a value,
  Gateway logs a warning. The memory queries are
  optional. Set both or neither. If a member has no value for one of them, its
  memory pressure is unknown.
- A failed request, a timeout, an unexpected response, a value that is not a
  finite number at least `0`, or two series for one member makes the value
  unknown. Gateway does not keep values from an earlier refresh.
- When the source is set, load-aware routing uses only its values, not the load
  or memory from the monitor. Other features, such as the query count router
  and the UI, still use the values from the monitor.
- The source is active only when `routing.loadAwarePooledRoutingEnabled` is
  `true`. Gateway validates the queries when the URL is set. `loadQueryTimeout` must be shorter than `taskDelay`.
- The source has no authentication. Use it on a trusted network.
- For a coordinator that reports, data is up to the scrape interval plus
  `taskDelay` old. An instant query returns the last sample within the lookback
  window of the server (5 minutes by default in Prometheus). Gateway does not
  check the sample age. If a coordinator stops reporting, its last value can
  stay for that window.

### Canary lane

A pool can run one extra Trino build, the canary, on lead members. Only requests
that arrive on a canary hostname reach it. Customers and every other request
never touch a lead.

#### Canary hostnames

Set the domains of the canary lane in the Gateway configuration:

```yaml
transactionAwareness:
  pool:
    canaryHostDomains:
      - canary.example.test
```

A request is a canary request when its `Host` is exactly one DNS label under one
of these domains, such as `acme.canary.example.test`. The domain itself and a name
with more labels, such as `a.b.canary.example.test`, are not canary requests. Gateway
ignores the port, the case, and a trailing dot, as it does when it selects a
pool. Each domain must be a lowercase DNS name without a port, a wildcard, or a
trailing dot. The default is an empty list, which means no canary lane.

The label only marks the request. A pool is still chosen by `hostnameToPool`, so
list every canary hostname there. Those exact entries are the allowlist: a host
without an entry returns HTTP 421 and reaches no pool. Do not add a
`hostnameSuffixToPool` entry for the canary domain, because it would let any
client name a canary host. Gateway refuses to start with such an entry.

The coordinator must qualify users on canary hosts as it does on other hosts.
Add the canary domain to the coordinator's host qualification domains. When a
tenant identity source is set, add it to `hostQualificationDomains` too, so both
sides derive the same principal. Gateway refuses to start if a canary domain is
missing from that list.

#### Pool settings

Two fields of the pool configuration, set alongside `maxSurge` and
`desiredRevision`, control the lane. Omitted fields mean `0` and an empty string.
The pool state returned by Gateway always includes `leadSurge`, `canaryRevision`,
`leadInUse` and `canaryServing`, and every member includes `lead`.

- `canaryRevision` is the build that leads run. It has the same rules as
  `desiredRevision`. An empty value means the pool has no canary.
- `leadSurge` (an integer from `0` to `100`, default `0`) is the number of lead
  slots.

`canaryServing` is the number of active members whose `configRevision` equals the
`canaryRevision`. It is `0` when the pool has no canary. It is for information:
it adds no reason to `blocked`.

#### Lead members

A lead is a member registered with `"lead": true`. It serves only canary
requests and sits outside every count of the main pool, so it neither uses the
surge budget nor counts as serving capacity. Registration of a lead needs all of
these:

- `repairFor` is absent. A lead is never a repair, and a repair cannot target a
  lead.
- `leadSurge` is greater than `0`.
- The pool's `canaryRevision` is not empty.
- `configRevision` equals the pool's `canaryRevision`.
- Fewer than `leadSurge` leads are in a live phase. A lead keeps its slot until
  it is retired or lost, so a draining or suspected lead still holds one.

A lead that breaks one of the first four rules returns `POOL_VALIDATION`, with a
message for that rule. A lead that has no free slot returns `POOL_SURGE_BUDGET`.
A registration that is not a lead uses the surge budget as before and ignores
leads. It cannot run the `canaryRevision`: Gateway refuses it with
`POOL_VALIDATION`, for a repair too, because canary requests and ordinary
requests would then share the build. The exception is a `canaryRevision` that
equals the `desiredRevision`, which is a normal revision again.

The main-pool rules do not count leads, and a lead drain is never blocked by
them: the repair capacity check, the surge budget, the minimum serving count for
a planned drain and for a publication, and the `counts`, `servingMembers`,
`liveMembers`, `surgeInUse` and `blocked` fields of the pool state.

#### Routing

These rules apply to new independent statements.

- A canary request, in a pool with a `canaryRevision`, runs only on active
  members whose `configRevision` equals that revision, leads included. If there
  are none, it fails with HTTP 503 and `X-Trino-Gateway-Error:
  REVISION_NOT_SERVING`. Gateway does not dispatch it to another build.
- A canary request, in a pool without a `canaryRevision`, is routed like an
  ordinary request.
- An ordinary request runs only on active members that are not leads. If there
  are none, it fails with the usual `ROUTING_STATE_NOT_ACTIVE`.

Continuations, transactions, and requests that are not new statements do not
change: a query keeps running on the member that accepted it, a lead included.
The canary lane never grants access, because the coordinator still authenticates
the credential. It adds no database read to a request.

#### Behavior to expect

- **A change of `canaryRevision`.** A canary request gets `REVISION_NOT_SERVING`
  until a lead on the new revision is active. This usually lasts a few minutes. It
  is on purpose: a canary request that succeeds ran on the canary build. With
  `leadSurge` `1` the lane stays at 503 for longer: the old lead holds the only
  slot until it is retired, and the new lead cannot register before that. With
  `leadSurge` `2` the new lead registers at once.
- **Publications and membership.** A lead takes part in publication receipts and
  membership changes. An active lead must acknowledge a publication before it
  commits. Admitting or draining a lead changes the membership generation, so an
  open publication that expects the old generation fails with
  `POOL_MEMBERSHIP_CHANGED` and the controller opens it again. A receipt names
  the member's process and the publication target, not the member's
  `configRevision`, so the controller can acknowledge for a lead as for any
  member. The admission barrier of an open publication asks a joining member to
  run the publication target. A lead runs the canary build and cannot, so it may
  join during the barrier. Its join is not a receipt: it is listed as missing
  until the controller acknowledges for it, and a main member still has to run the
  target.
- **Auxiliary requests.** Requests that are not new statements, such as
  `/v1/info`, follow the same lane. On a canary host they reach only the canary
  build, with `REVISION_NOT_SERVING` when there is none, and elsewhere they never
  reach a lead. A request that continues the work of a given backend, such as an
  OAuth2 handshake pinned to it, stays on that backend, a lead included.
- **Deploy order.** The migration adds the new pool and member columns when
  Gateway starts. Results that a new version records for pool configuration and
  member steps carry new fields, and an older version cannot replay them: a step
  that the controller resends to an older replica fails with `POOL_VALIDATION`.
  Deploy the new version to every replica before the controller sets
  `canaryRevision` or `leadSurge`, or registers a lead. Do not roll back while the
  controller can still resend steps that the new version recorded.

### Durable transaction and query ownership

PostgreSQL records the coordinator incarnation that owns a transaction or query,
together with owner identity and admission/result obligations. Statements in an
existing transaction and subsequent result polls, metadata requests, or
cancellations use that durable binding. The request hostname must identify the
same pool as the binding; changing Host or sending a routing-group header cannot
move existing work.

Unknown transaction ownership is rejected. A known query or transaction can
continue on its draining owner while lifecycle admission permits it, but cannot
be moved to a newly active member or a restarted coordinator with another
incarnation. Database unavailability, an identity mismatch, or an uncertain
outcome fails closed rather than choosing an arbitrary healthy coordinator.

A result URI's query identifier locates the binding; it is not proof that the
caller owns it. Gateway enforces the supported owner credentials or recorded
result capability for each operation. A client without credentials may use only
a continuation capability already recorded for that query, not arbitrary query
metadata or cancellation by identifier.

### Authentication protocol affinity

Gateway's own UI/API OAuth or form authentication is distinct from the Trino
query protocol. Browser session cookies may still be needed for authentication;
they do not choose a compute pool or override durable query ownership.

Where Trino OAuth2 token-exchange transport is enabled, the CLI/driver polls
`/oauth2/token/{authId}`, the browser visits
`/oauth2/token/initiate/{authIdHash}`, and the identity provider returns to
`/oauth2/callback`. That coordinator-local handshake must stay on the coordinator
that issued its challenge. Gateway's shared handshake binding is constrained to
the Host-selected pool; an unavailable or invalid binding requires
reauthentication, not an arbitrary cookie-selected coordinator.

This transport affinity does **not** provide OAuth/Bearer query admission through
the current pooled tenant adapter. That adapter derives one Basic credential
principal, qualified exactly as the coordinator will qualify it, and performs a
deny-only lookup in controller-published tenant bindings. The coordinator verifies
the credential and authorization. UI OAuth login, a Bearer claim, or handshake
pinning does not supply a verified Basic principal to that restriction.

### Forwarding URLs and trust

The internal member URL is the dispatch destination; an external URL can provide
links to the coordinator UI. Neither URL is a routing selector. Preserve the
public Host and regenerate coordinator-facing forwarding metadata from trusted
configuration, never client `Forwarded` or `X-Forwarded-*` values. Result
continuations must return through Gateway so durable ownership and result
obligations remain observable. See [security](security.md) and
[pooled operation](../testing/transaction-awareness/OPERATIONS.md).
