# Operation

> **Routing modes:** Official upstream routing functionality is retained. With
> `transactionAwareness.enabled: false` and
> `transactionAwareness.pool.enabled: false` (the defaults), the stock routing
> behavior described below applies. When both flags are `true`, stock routing
> selectors, routing rules, route cookies, and default-group fallback are
> **bypassed**, not removed. Use [pooled hostname routing](pooled-routing.md)
> instead; a pool failure never falls back to stock routing.

The following aspects apply to managing Trino Gateway and the connected Trino
clusters.

## Query History UI - check query plans etc.

Trino Gateway records history of recent queries and displays links to check query
details page in respective trino cluster.
![trino.gateway.io](./assets/trinogateway_query_history.png)

## Cluster UI - add and modify cluster information

The cluster page is used to configure Trino Gateway for multiple Trino clusters.
Existing cluster information can also be modified using the edit button.

![trino.gateway.io/entity](./assets/trinogateway_cluster_page.png)

## Cluster configuration and health status

Trino Gateway tracks cluster state at two separate layers, each serving a
different purpose.

### Database: source of truth for configuration

All cluster configuration is stored persistently in a database. This includes
each cluster's name, routing group, proxy URL, external URL, and whether it is
marked active or inactive.

Any change made through the API or the admin UI — adding, updating, activating,
deactivating, or deleting a cluster — is written to the database immediately.
The database represents **what you have configured**: the intended state of the
system. It persists across restarts.

### Health check cache: source of truth for routing

In addition to the database, Trino Gateway maintains an in-memory record of
each cluster's current health. This cache is separate from the database and is
used to make all query routing decisions.

Each cluster in the cache has one of four health statuses:

| Status | Meaning |
|--------|---------|
| `HEALTHY` | The cluster is reachable and accepting queries |
| `UNHEALTHY` | The cluster failed its most recent health check |
| `PENDING` | The cluster was recently added or changed and has not yet been health-checked |
| `UNKNOWN` | Health status could not be determined |

Only clusters with a `HEALTHY` status receive query traffic, regardless of
whether the database marks them as active. A cluster in `PENDING` state does not
receive traffic — it transitions to `HEALTHY` or `UNHEALTHY` once the next
background health check evaluates it.

The cache is updated by a background health check that runs at a configurable
interval (default: every 1 minute, set via `monitor.taskDelay`). The health
check mechanism itself is also configurable — by default it calls the `/v1/info`
endpoint on each cluster, but JDBC, JMX, and Prometheus metrics are also
supported.

### How the two layers interact

The database and health cache serve complementary roles:

- **Database** — "what you configured"
- **Health cache** — "what is actually reachable right now"

When you make a change via the API or admin UI, both layers are updated
immediately:

| Action | Database | Health cache |
|--------|----------|-------------|
| Add a cluster | Written immediately | Set to `PENDING` if active, `UNKNOWN` if inactive |
| Update a cluster | Written immediately | Reset to `PENDING` if active, `UNKNOWN` if inactive |
| Activate a cluster | Written immediately | Set to `PENDING` |
| Deactivate a cluster | Written immediately | Set to `UNKNOWN`; excluded from routing immediately |
| Delete a cluster | Removed immediately | Removed from cache and JMX; excluded from routing immediately |

Because the database and health cache are not updated atomically, a failure
during the in-memory update could leave the two layers out of sync. To prevent
this, each operation uses a compensation pattern: before applying any change,
the current state is saved (previous health status and backend configuration).
If the in-memory update fails after the database write succeeds, the database
change is reverted using the saved state:

| Action | Compensation on failure |
|--------|------------------------|
| Add a cluster | Delete the new entry from DB, remove health |
| Update a cluster | Restore old config in DB, revert health |
| Activate a cluster | Deactivate in DB, revert health |
| Deactivate a cluster | Re-activate in DB, revert health |
| Delete a cluster | Re-add old config to DB, revert health |

For update and delete, compensation is skipped if the old backend configuration
was not found before the operation, to avoid reverting to an unknown state. If
the compensation itself fails, the compensation exception is attached to the
original exception as a suppressed exception — the original is always the one
thrown to the caller.

A cluster that crashes or becomes unreachable after the last health check will
remain active in the database but will be marked `UNHEALTHY` in the cache
within one polling interval. During that window, in-flight queries already
routed to that cluster may fail, but no new queries will be sent to it once the
cache is updated.

The health cache is not persisted — it is rebuilt from the database and
repopulated by health checks each time Trino Gateway starts.

## Graceful shutdown

These stock steps are not sufficient for pooled members. In pooled mode, drain
through the fenced lifecycle API and settle all authoritative request, query,
transaction, and result obligations before sealing and retirement. Zero monitor
query counts or a legacy active switch cannot authorize retirement.


Trino Gateway supports graceful shutdown of Trino clusters. Even when a cluster
is deactivated, any submitted query states can still be retrieved based on the
Query ID.

To graceful shutdown a trino cluster without query losses, the steps are:

1. Deactivate the cluster by turning off the 'Active' switch. This ensures that no
   new incoming queries are routed to the cluster.
2. Poll the Trino cluster coordinator URL until the queued query count and the
   running query count are both zero.
3. Terminate the Trino coordinator and worker Java processes.

To gracefully shutdown a single worker process, refer to the [Trino
documentation](https://trino.io/docs/current/admin/graceful-shutdown.html) for
more details.

## Query routing options

- The default router selects the cluster randomly to route the queries.
- If you want to route the queries to the least loaded cluster for a user
  so the cluster with the fewest running or queued queries,
use `QueryCountBasedRouter`. You can enable it by adding the module name
to the `modules` section of the config file:

```yaml
modules:
  - io.trino.gateway.ha.module.QueryCountBasedRouterProvider
```
- The router operates based on the stats it receives from the clusters, such as
the number of queued and running queries. These values are retrieved at regular
intervals. This interval can be configured by setting `taskDelay` under
`monitor` section in the config file. The default interval is 1 minute
```yaml
monitor:
  taskDelay: 1m
```

## Monitoring <a name="monitoring"></a>

Trino Gateway provides a metrics endpoint that uses the OpenMetrics format at
`/metrics`. Use it to monitor Trino Gateway instances with Prometheus and
other compatible systems with the following Prometheus configuration:

```yaml
scrape_configs:
- job_name: trino_gateway
  static_configs:
    - targets:
        - gateway1.example.com:8080
```

## Pooled administration boundary

For hostname-only pooled deployments, use `/gateway/v1/pools/...` for fenced
lifecycle administration. Stock backend registration and the cluster UI remain
available, but neither an active switch nor a healthy probe certifies a member
for admission. Stock routing selectors and route cookies do not override pool
membership or durable ownership.

The custom fixed-backend route override, cutover, resume, reincarnation, and
rollout administration APIs remain removed; they are not upstream functionality.
Do not reuse retired member identities or clear unresolved ledger rows.
See [pooled operation](../testing/transaction-awareness/OPERATIONS.md) and
[pooled cordon coordination](design/pooled-cordon-coordination.md).

## Transaction lifecycle diagnostics

With transaction-aware routing, the existing `/metrics` endpoint exports fixed-name
counters under `io_trino_gateway_ha_transaction_name_TransactionLifecycleStats_`:

| Counter suffix | Observation |
| --- | --- |
| `TerminalResults` | Successful terminal JSON-result ledger recording calls. |
| `CancellationAcknowledgements` | Successful whole-query cancellation recording calls. |
| `NotFoundContinuations` | Continuation HTTP 404 responses without transaction lifecycle headers. |
| `ExecutingNotFoundResponses` | The subset received for exact admitted executing-result GET requests. |
| `MarkUncertainSuccesses` | Calls to mark an admission uncertain that returned successfully. |
| `MarkUncertainFailures` | Calls to mark an admission uncertain that failed. |
| `SupersededAdmissions` | Failed requests whose admission this process settled as superseded instead of uncertain, because a sibling admission already recorded a terminal observation for the query. |
| `SweptSupersededAdmissions` | Uncertain admissions the periodic sweep settled as superseded. |
| `BlockedDrainObservations` | Draining members with nonzero obligations observed in member-list or obligation reads. |
| `LedgerRowsDeleted` | Settled ledger rows deleted by ledger retention on this process. |
| `LedgerRetentionFailures` | Ledger retention runs that stopped on a failure; the next run retries. |

These counters describe observations on each Gateway process, not distinct queries,
current obligations, or completed state transitions. Retries and repeated polls can
increment them again. Marking an already-complete admission uncertain is a successful
no-op. A superseded settlement counts only as `SupersededAdmissions`, not as
`MarkUncertainSuccesses`. Counters reset when the process restarts. Use the pool obligation API for
current authoritative counts; never use these counters to authorize retirement.

Diagnostic logs use the fixed reasons `EXECUTING_RESULT_NOT_FOUND`,
`MARK_UNCERTAIN_SUCCEEDED`, `MARK_UNCERTAIN_FAILED`, `ADMISSION_SUPERSEDED`, and
`POOL_DRAIN_BLOCKED`, plus `LEDGER_RETENTION` and `LEDGER_RETENTION_FAILED` for ledger retention. `ADMISSION_SUPERSEDED` carries `source=request` or `source=sweep`.
Each reason has an independent limit of one log per 30 seconds per process; counters
remain unsampled. Logs include admission/query/incarnation identifiers or member
obligation counts, but not SQL, credentials, transaction identifiers, result
capabilities, backend URLs, response bodies, or exception messages. A status of zero
means the failure handler did not have an upstream HTTP status. Ordinary terminal
responses and cancellation acknowledgements update counters without success logs.

An executing-result 404 observation does not claim that query recovery occurred.
This instrumentation does not add a reconciler or change any drain decision.

## Superseded admissions

Clients may poll one query over concurrent requests and drop the others once one
of them returns the final result. The dropped request fails, and its admission
would otherwise stay uncertain and block the member's drain forever: no later
request goes through that admission to settle it. An admission instead becomes
`COMPLETE` with observation `SUPERSEDED` when its request failed with no backend
response forwarded or recorded (marked `NO_RESPONSE` while uncertain), its query is
terminal, and another admission of the same query on the same incarnation completed
with a terminal observation (final page, cancellation acknowledgement, or query
no longer known). Only that proof settles it. An uncertain admission
marked `RESPONSE_UNRECORDED`, because a non-success response was forwarded or a
received response could not be recorded, never settles this way; neither does an
uncertain submission or transaction statement, or a continuation without that proof.
Uncertain admissions without a marker, written before this rule or by an older
replica during a rolling upgrade, do not record whether a response was forwarded,
so they never settle automatically; settling one requires an operator decision
based on evidence outside the ledger.

A failed request applies the rule when it is marked. With transaction-aware routing enabled,
every Gateway replica also sweeps independently, 10 seconds after startup and then
60 seconds after each sweep finishes. The sweep is the only path that settles a request that failed before its
sibling recorded the terminal observation; sweep failures are logged as warnings.

## Ledger retention

The transaction ledger keeps a row for every forwarded request, every query and
every transaction. Settled history is deleted after a retention period so the
ledger does not grow without bound:

```yaml
transactionAwareness:
  ledgerRetentionDays: 7              # 0 disables retention
  ledgerRetentionBatchSize: 100       # index entries examined per batch, at most 500
  ledgerRetentionBatchPauseMillis: 500
```

Retention deletes only rows that no drain decision, continuation or superseded
settlement can still read:

* a terminal query whose retry window (`retain_until`) ended at least
  `ledgerRetentionDays` ago, whose admissions are all `COMPLETE`, and whose
  transaction, if any, is `CLOSED`, together with its admissions and result
  capabilities;
* a rejected admission (`COMPLETE` without a query) created at least
  `ledgerRetentionDays` ago;
* a `CLOSED` transaction binding once the last query or admission referencing it
  is deleted.

Pending and uncertain admissions, running queries, queries still inside their retry
window, and open transactions are never deleted, however old they are, and neither
are backend incarnations or any pool table: incarnation and member identities are
never reused, and that guarantee depends on their rows. Retention therefore never
changes drain status or pool obligations. A continuation for a deleted query is
refused as an unknown query; it is never routed to another backend.

Every replica runs retention 60 seconds after startup and then 5 minutes after each
run finishes, on a thread separate from the superseded sweep. Each phase (queries and
rejections) walks the expired range in index order with a cursor that survives runs
and wraps to the start only at the end of the range, so rows that must be kept cost
one pass per cycle and never starve the rows behind them. A run pauses
`ledgerRetentionBatchPauseMillis` between full batches and stops after 1000 batches;
the next run continues from the cursor. A failure in one phase does not stop the other.

Each batch is one short transaction. It only tries the identity locks that admissions,
continuations and completions hold, and locks rows with `SKIP LOCKED`, so it does not
queue behind a request that already holds a query or transaction, and replicas share
the backlog. A late continuation for an expired query that arrives while a batch holds
that query waits for the batch, bounded by the data store `lockTimeoutMillis`, and
then either finds the query unknown or fails with a retryable database error. A batch
holds up to two advisory locks per examined row, so keep `ledgerRetentionBatchSize`
well below PostgreSQL's lock table capacity (`max_locks_per_transaction` times
connections). The data store statement and lock timeouts apply to every retention
statement. After a failed batch the next run retries from the same position with half
the batch size, down to one row; a row that still fails keeps `LEDGER_RETENTION_FAILED`
logged and `LedgerRetentionFailures` increasing, and stops that phase until it is fixed.

Retention is enabled by default because the deleted rows can no longer affect
correctness, while unbounded growth slows every drain-status read. Upgrading therefore
starts deleting settled history older than seven days; set `ledgerRetentionDays: 0`
first if you keep that history for audit. A CLOSED
transaction binding is considered only when a batch deletes the last query or
rejection that references it.

Upgrading builds four indexes concurrently (migration V15). The migration connection
has no statement timeout; on a large ledger the build takes a while and waits for
older open transactions, but does not block writes. If a build is interrupted, the
next startup repairs the failed migration record and the script rebuilds all four
indexes, replacing an invalid one. The indexes add a small write cost to queries and transaction statements.

## Trino Gateway health endpoints

Trino Gateway provides two API endpoints to indicate the current status of the server:

* `/trino-gateway/livez` always returns status code 200, indicating the server is
alive. However, it might not respond if the Trino Gateway is too busy, stuck, or
taking a long time for garbage collection.
* `/trino-gateway/readyz` returns status code 200, indicating the server has
completed initialization and is ready to serve requests. This means the initial
connection to the database and the first round of health check on Trino clusters
are completed. Otherwise, status code 503 is returned.

## Database cache configuration

Trino Gateway can cache database queries to improve performance and reduce load
on the backend database. This also allow gateway to continue routing queries
when the database is temporarily unavailable. Currently only the list of backend
Trino clusters used for query routing are being cached.
The cache can be configured using the `databaseCache` section in the config file.

```yaml
databaseCache:
  enabled: true
  expireAfterWrite: 1h
  refreshAfterWrite: 5s
```

Configuration options:

* `enabled` - Enable or disable the database cache. Default is `false`.
* `expireAfterWrite` - The maximum time a cached entry is kept since it was last
  loaded or refreshed. This ensures stale data is eventually removed.
  If cache is not refreshed before expiration, requests will fail once the entry
  expires (i.e. cache miss will attempt to reload data, but if the database is unavailable,
  the request fails because there is no stale value to fall back to after
  expiration). Default value is `1h`.
* `refreshAfterWrite` - Duration after which cache entries are eligible for
  asynchronous refresh. When a refresh is triggered, the existing cached value
  continues to be served while the refresh happens in the background.
  This helps keep data fresh while serving slightly stale data to avoid blocking requests.
  Default value is `5s`.

`expireAfterWrite` and `refreshAfterWrite` can be set to `null` to disable expiration
or refresh respectively.
