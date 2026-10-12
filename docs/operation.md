# Operation

The following aspects apply to managing Trino Gateway and the connected Trino
clusters.

## Query History UI - check query plans etc.

Trino Gateway records history of recent queries and displays links to check query
details page in respective trino cluster.
![trino.gateway.io](./assets/trinogateway_query_history.png)

## Pool configuration and admission

The cluster UI shows backend registration and monitoring information. Its
legacy active switch is not authority to admit a pooled coordinator. Lifecycle
administration uses `/gateway/v1/pools/...` with the required API role and separate
administration capability. Keep those credentials in runtime secret references.

PostgreSQL is authoritative for pool mode, admitted member phase, immutable
coordinator incarnation, tenant publication/admission, and outstanding request,
transaction, query, and result obligations. An independent statement must use an
admitted member of the Host-selected pool. Its admission transaction rechecks
membership and phase while holding the pool's admission fence.

A member begins unroutable. Before activation, the lifecycle protocol requires a
fresh validation receipt for the required configuration/catalog/auth revisions
and verifies the observed coordinator process identity. Publishing a tenant's
principal binding alone does not open its admission gate: the required member
publication evidence must also complete. The coordinator remains responsible for
credential verification and authorization on each statement.

## Cluster configuration and health status

Persistent backend registration records the member name, pool routing group,
internal and external URLs, and lifecycle-linked identity. Cached health provides
monitoring and candidate-ordering hints; it is not a second authority for
admission. `HEALTHY`, `UNHEALTHY`, `PENDING`, and `UNKNOWN` are observations from
background probes, not member lifecycle phases or validation certificates.

The monitor can use info API, JDBC, JMX, or metrics probes according to its
configuration. Probe freshness and reachability cannot certify a process,
settle uncertain work, or restore a retired incarnation. An eligible admitted
member may still be considered when cached health is unknown or unhealthy;
process verification and authoritative admission checks remain mandatory.

## Graceful pool-member shutdown

1. Drain the selected member through the fenced pool lifecycle protocol. This
   closes admission of new independent statements without moving its existing
   transactions, queries, or result continuations.
2. Inspect the authoritative obligation API. Pending or uncertain admissions,
   open transactions, active queries, and unresolved result delivery block
   sealing, even when the coordinator reports no queued or running queries.
3. Seal with the expected generation after all blockers are settled. The
   lifecycle operation excludes late admission and rechecks durable obligations.
4. Retire and terminate only through the controller workflow after sealing and
   any remaining lifecycle requirements are satisfied.

An idle health/metrics sample, cache expiry, elapsed timeout, or replica-local
counter is not retirement evidence. If the coordinator is lost with unresolved
work, stop automatic retirement and use the pool's explicit authorized recovery
procedure. Never delete ledger rows, reactivate through a legacy API, or claim
that another member recovered the lost process's in-memory transactions.

See [pooled cordon coordination](design/pooled-cordon-coordination.md) for lock
behavior and recovery limits. Worker-only shutdown remains a coordinator/worker
lifecycle operation, not a Gateway routing change.

## Query routing options

Configure [hostname-to-pool placement](routing-rules.md). There is no legacy
random/load-based router module to choose outside that pool, no default routing
group, and no cookie-based destination selection. Cached cluster load and health
cannot override certified pool membership or a durable transaction/query owner.
Read [routing logic](routing-logic.md) before changing placement or candidate
ordering.

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

Pooled members retain their incarnation history after draining, retirement, or
loss. Legacy backend or route-management APIs are not a supported route back into
query service. Use the fenced pool lifecycle and preserve its ledger; neither a
registration edit nor a healthy probe permits bypassing certified admission.

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
| `BlockedDrainObservations` | Draining members with nonzero obligations observed in member-list or obligation reads. |

These counters describe observations on each Gateway process, not distinct queries,
current obligations, or completed state transitions. Retries and repeated polls can
increment them again. Marking an already-complete admission uncertain is a successful
no-op. Counters reset when the process restarts. Use the pool obligation API for
current authoritative counts; never use these counters to authorize retirement.

Diagnostic logs use the fixed reasons `EXECUTING_RESULT_NOT_FOUND`,
`MARK_UNCERTAIN_SUCCEEDED`, `MARK_UNCERTAIN_FAILED`, and `POOL_DRAIN_BLOCKED`.
Each reason has an independent limit of one log per 30 seconds per process; counters
remain unsampled. Logs include admission/query/incarnation identifiers or member
obligation counts, but not SQL, credentials, transaction identifiers, result
capabilities, backend URLs, response bodies, or exception messages. A status of zero
means the failure handler did not have an upstream HTTP status. Ordinary terminal
responses and cancellation acknowledgements update counters without success logs.

An executing-result 404 observation does not claim that query recovery occurred.
This instrumentation does not add a reconciler or change any drain decision.

## Trino Gateway health endpoints

Trino Gateway provides two API endpoints to indicate the current status of the server:

* `/trino-gateway/livez` always returns status code 200, indicating the server is
alive. However, it might not respond if the Trino Gateway is too busy, stuck, or
taking a long time for garbage collection.
* `/trino-gateway/readyz` returns status code 200, indicating the server has
completed initialization and is ready to serve requests. This means the initial
connection to the database and the first round of health check on Trino clusters
are completed. Otherwise, status code 503 is returned.

## Database availability and caches

Backend registration caches are compatibility/monitoring data, not admission or
ownership authority. They cannot authorize new work during a PostgreSQL outage,
create a transaction/query binding, or waive a pool lifecycle fence. Durable
admission and continuation lookup fail closed when their authoritative state
cannot be read or committed within the request budget.

If a registration cache is configured, its expiration and refresh settings only
control that cache. Do not interpret retained entries, a successful refresh, or
cache expiry as completed work or safe retirement. Current obligation counts
come from the pool API, not from cache contents or historical query UI records.
