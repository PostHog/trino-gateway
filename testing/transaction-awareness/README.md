# Pooled hostname-routing integration tests

Read [VALIDATION.md](VALIDATION.md) for the verification checklist and
[BASELINE.md](BASELINE.md) for controlled comparisons. Keep execution results,
artifact identities, generated credentials, and Gateway/database logs private,
outside this public repository. Fixtures must never target customer services.

## Supported multi-process contracts

`gateway_fixture.py` starts two independent Gateway JVMs, a shared disposable
PostgreSQL database, and independent protocol-focused synthetic coordinators.
Every test gets fresh database state, a shared generated identity key, and a
generated administration credential. Both `transactionAwareness.enabled` and
`transactionAwareness.pool.enabled` are true.

The fixture maps `routing.hostnameToPool.localhost` to `cell`. Gateway URLs are
`http://localhost:<port>`: the real HTTP Host includes a mapped DNS name and port.
There is no default routing group or header-based selection. Gateway's HTTP
server ignores forwarded headers. Backend registry entries start active but
cannot admit queries until certified through the pooled member protocol.

From this directory, set these runtime variables outside the repository:

| Variable | Meaning |
| --- | --- |
| `GATEWAY_TEST_JAVA` | Java executable compatible with the tested Gateway artifact. |
| `GATEWAY_TEST_CLASSPATH` | Gateway compiled classes and dependencies, or its packaged classpath. |
| `GATEWAY_TEST_PG_BIN` | Directory containing `initdb`, `pg_ctl`, and `psql`; run as a non-root user. |
| `GATEWAY_TEST_LOG_DIR` | Optional private directory outside the repository retaining per-fixture JVM and PostgreSQL logs. |

```sh
python3 -m unittest -v test_pool_lifecycle test_graceful_shutdown test_whole_query_cancel
```

`test_pool_lifecycle` covers:

- Host mapping on independent replicas; spoofed `X-Trino-Routing-Group` and
  `X-Forwarded-Host` cannot change placement.
- Unmapped valid Host rejection with HTTP 421 before coordinator dispatch.
- No fallback to active nonpooled registry entries, including an absent or empty
  pool, followed by a successfully admitted-member positive control.
- Bootstrap, serving floors, surge and repair budgets, concurrent drain
  serialization, replayed admissions, and controller-epoch fencing.
- Cross-replica transaction and query affinity during drain: new statements
  exclude the draining member while existing transactions and continuations
  retain their original coordinator.
- Suspect-member replacement, forgotten executing queries, authenticated absence
  receipts, and lifecycle locks that do not stall admitted response completion.

`test_graceful_shutdown` holds a continuation, sends SIGTERM to its Gateway,
requires the admitted response to finish before process exit, and then replays
that result and commits an existing transaction through the other replica.
Its ten-second HTTP stop timeout is a bounded fixture setting, not proof of
unlimited request survival or a production load-balancer drain guarantee.

`test_whole_query_cancel` uses pooled bootstrap and lifecycle APIs. It preserves
inflight admissions, result retention, and explicit transactions as seal blockers,
checks partial cancellation and non-GET 404 boundaries, and verifies the handling
replica's lifecycle metrics.

These are real HTTP and PostgreSQL serialization tests, not mock-only distributed
correctness claims. They start no Kubernetes resources, real Trino coordinator,
or production credential store. They do not establish tenant authorization,
transaction replication, coordinator-loss recovery, or throughput guarantees.

## Protocol and fault tools

`fake_trino.py` implements statement/result requests, backend-local transactions,
replayable continuations, heartbeat and cancellation responses, independent
coordinator identities, and observable response barriers. Its unauthenticated
`/__test/*` controls must remain on an isolated test network. State records paths,
SQL, and transaction headers, but never Authorization; use only synthetic SQL.

`protocol.py` preserves repeated HTTP headers and verifies TLS. An explicitly
supplied Host replaces, rather than duplicates, its automatic URL Host. Changing
Host does not alter the verified TLS server identity. `network_fault_proxy.py`
provides an isolated TCP-transparent PostgreSQL fault boundary and a separate
HTTP control port. None of these tools authorizes clearing uncertain ledger rows.

```sh
python3 -m unittest -v test_fake_trino test_network_fault_proxy test_protocol_tls
python3 -m unittest -v test_fault_recovery
```

The remaining externally configured protocol, adversarial, fault, restart, and
real-client modules retain useful individual regression oracles. Their historical
blue/green placement setup is not the supported pooled fixture and must be
adapted before executing them against a pool-only Gateway. Do not treat a missing
legacy administration endpoint, bootstrap error, or configuration rejection as
transaction-affinity evidence. The former rollout/cutover clients and fixed
blue/green load-checkpoint drivers have been removed rather than exported as
supported operations.

For genuine baseline comparisons, establish healthy controls first and record
any compatibility-only configuration differences privately. Contract assertion
failures are distinct from compilation, readiness, TLS, authentication, and
database failures. Preserve unresolved work rather than resetting its ledger.

## Bounded load and deployment tooling

The core `load_open_loop.py`, eight-process supervisor, and aggregation tools
remain available. `TX_LOAD_HOSTNAMES` specifies explicitly mapped Host authorities,
not routing-group headers; continuations preserve the originating Host when
switching Gateway replicas. TLS name verification remains independent of Host.
Expected synthetic result identities are keyed by the same hostname inputs.
Use only approved, bounded disposable profiles and keep every receipt private.

The deployment renderer emits hostname mappings and mandatory transaction/pool
settings. Its generated administration and identity keys remain distinct. Local
Gateway forwards use localhost, which is covered by the lab certificates and
hostname map. Registry registration is not certification: configure the pool and
admit its members through the pooled API before any load or real-client request.
Use an explicitly selected compatible, pinned Gateway artifact, not a legacy
upstream image with unsupported pooled configuration.

```sh
python3 -m unittest discover -s deploy -p 'test_*.py' -v
```

The PostgreSQL-backed observer tests additionally require an explicitly owned
local database and `TX_OBSERVER_LOCAL_PG=yes`. Rendering tests are not deployment
execution receipts. Read the deployment safety guidance before operating any lab.
