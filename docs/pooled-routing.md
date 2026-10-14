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
load = queued + running * memoryPressure
memoryPressure = reservedQueryMemory / maxQueryMemory, clipped to [0, 1]
```

Gateway keeps healthy members first. Then it sorts by load, lowest first. Equal
loads keep the shuffled order, so ties spread across members.

Load is advisory, as health is:

- If memory statistics are not known, running queries count at full weight, and
  the load is `queued + running`.
- A member without statistics sorts after members with statistics. It stays
  eligible.
- Missing statistics never cause a routing failure.
- If reserved memory is `0`, running queries add no load. Queued queries still
  count.

Statistics refresh at the interval `monitor.taskDelay`. Between refreshes, new
statements go to the same least-loaded member. Use a short `taskDelay` with this
feature.

The `JMX` monitor reads memory from the
`trino.memory:type=ClusterMemoryPool,name=general` MBean when the flag is on. The
`METRICS` monitor reads memory only when you set
`monitor.reservedQueryMemoryMetricName` and `monitor.maxQueryMemoryMetricName`.
Other monitors do not supply memory statistics. Trino exports the memory pool
on `/metrics` only if `openmetrics.jmx-object-names` includes the MBean.

Members that report memory statistics can have a lower load than members that
do not, because unknown memory pressure counts as `1`. Configure all members of
a pool the same way.

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
