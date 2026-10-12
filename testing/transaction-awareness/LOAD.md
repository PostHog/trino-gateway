# Bounded hostname-based load tooling

Use only explicitly authorized disposable environments. Keep endpoint values,
credentials, generated manifests, measurements, receipts, and all execution
outcomes outside this public repository. A passing test is not a throughput or
capacity claim. See [README.md](README.md) for supported pooled contracts,
[SCALE-VALIDATION.md](SCALE-VALIDATION.md) for acceptance boundaries, and
[DATABASE_COST.md](DATABASE_COST.md) for aligned database measurements.

## Placement and prerequisites

A compatible Gateway artifact must require both transaction-awareness and pool
flags true. Configure exact hostname-to-pool mappings, register backend metadata,
and certify/admit the members through the pooled lifecycle API before load.
Active registry entries are not a fallback. Placement is not tenant authorization.

`TX_LOAD_HOSTNAMES` specifies mapped Host authorities. Each SQL submission rotates
these inputs independently of continuation arrivals. Every continuation retains
its initiating Host even when issued through a different Gateway process.
`X-Trino-Routing-Group` and `X-Forwarded-Host` do not select placement. Expected
synthetic result identities are keyed by the same hostname values.

```sh
export TX_ALLOW_FIXTURE_MUTATION=yes
export TX_GATEWAY_URLS='<independent-gateway-https-endpoints>'
export TX_CA_FILE='<private-lab-ca-file>'
export TX_QUERY_AUTHORIZATION='<runtime-only-query-authorization>'
export TX_LOAD_HOSTNAMES='<comma-separated-mapped-host-authorities>'
export TX_LOAD_EXPECTED_BACKENDS='<JSON-hostname-to-fixture-identity-map>'
python3 testing/transaction-awareness/load_open_loop.py \
  --rate 100 --warmup 10 --duration 60 --concurrency 128 \
  --output '<new-private-receipt-file>'
```

Verified TLS remains mandatory. `TX_TLS_SERVER_NAME` may name the expected lab
certificate identity while connecting to individual pod addresses; it does not
change Host placement or bypass certificate trust. Without it, the URL hostname
is verified. Local localhost port-forwards are functional fixtures, not evidence
of in-cluster capacity.

## Open-loop accounting

`load_open_loop.py` schedules aggregate HTTP requests, not SQL queries. Arrivals
submit a statement or consume an advertised continuation without waiting for
earlier work. SQL counts, measured HTTP traffic, and post-window cleanup are
separate. The bounded client reuses connections, never retries uncertain work,
caps concurrency, and drops arrivals exceeding its resource or lag limits.

Before each window, bounded no-traffic barrier tasks prepare every executor
worker. Setup has its own deadline and accounting; it does not prewarm Gateway,
PostgreSQL, or backends. Scheduled-start checks reject a missed boundary.
Optional `--measurement-start-utc '<ISO-8601-Z-time>'` requests a start at most
600 seconds after warmup; an excessive delay does not silently shift the window.

Private receipts distinguish offered, completed, and successful HTTP rates,
latency and scheduling-lag distributions, status/error categories, dropped
arrivals, CPU, and unconsumed continuations. Per-second buckets use their actual
durations and count POST/GET together. Warmup and cleanup remain separate;
cleanup failures can invalidate the run. HTTP 503 is not successful throughput,
and a classified 503 message does not identify its underlying cause.

Cgroup-v2 diagnostics separately sample setup/scheduled-wait and
workload/late-completion intervals. Missing files are unavailable; changed quotas,
reset counters, malformed values, and invalid ordering invalidate derived deltas.
These are bounded sampled intervals, not exact timed-window CPU attribution.
Unlimited quota is not guessed capacity. Client, backend, or database saturation
precludes a Gateway-capacity conclusion.

## Eight-process mode

`--processes 8` divides the aggregate schedule and concurrency among eight
spawned workers; it does not multiply either. Workers own their connections and
continuations, finish no-traffic preparation before a common monotonic/UTC
window, and complete measured work before any continuation cleanup.

Aggregation checks worker count, profiles, counters, sample sizes, and aligned
windows. Global percentiles use pooled samples, never worker-percentile averages;
compact final receipts omit raw arrays. Missing/failed workers invalidate the
phase instead of reallocating their share. Simultaneous inflight peak is unknown;
reported lower/upper bounds are not an observed peak. Worker CPU sums exclude
parent overhead, and shared-container cgroup observations are not summed.

`deploy/render_load_job.py` creates bounded, non-preempting Jobs with no API
credentials or retries. Explicit eight-process mode requires both
`load_multiprocess.py` and `load_aggregate.py` in `process_sources`. Provide an
approved, task-owned, negative-priority, non-default PriorityClass with
`preemptionPolicy: Never`; do not create or adopt cluster-scoped resources without
authorization. Review client, fixture, database, and replica resources first.

Synthetic credentials generated in these Jobs are not authenticated by the fake
coordinators. They do not establish real Trino authentication, unmodified-client
compatibility, or large-result performance. Test those separately.

## Lifecycle and preservation boundaries

The obsolete rollout clients, fixed blue/green load checkpoints, concurrent
cutover driver, and matrix wrapper have been removed. They must not be exported
as supported pool operations. Use the multi-process pool contracts in README for
transaction affinity, draining, controller fencing, and query reconciliation;
steady-state load does not replace those safety checks.

For controlled comparisons, bind each run privately to a source revision and
immutable artifact plus identical workload, PostgreSQL, client, and backend
resources. Account for monitor probes and request-time identity checks. Local
PostgreSQL is not managed-database failover or capacity evidence. Configured
maximum resources are not measured headroom.

Preserve failed and uncertain work. Do not reset coordinators, evict retained
results, delete ledger rows, or retry ambiguous requests to manufacture a clean
run. The fixture retains query/results and request history, so accumulated
warmups and reruns consume memory. Finite quotas and configured allowances do
not establish that a complete workload fits.

```sh
python3 -m unittest -v test_load_open_loop test_load_executor_setup \
  test_load_client_cpu test_load_error_categories test_load_multiprocess \
  test_load_aggregate test_protocol_tls
```

The retained externally configured overload/deadline and fault oracles also need
pooled placement setup before execution against a pool-only artifact. A removed
legacy API or readiness failure is not a transaction-routing regression.
