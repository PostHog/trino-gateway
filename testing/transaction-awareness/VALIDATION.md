# Pooled routing verification

This is a checklist, not an execution receipt. Keep actual images, environment
settings, logs, deployment results, and performance evidence private.

## Gates

- Run Java formatting, static checks, migrations, and relevant tests with Java 25.
- Verify strict Host parsing and exact/suffix mapping precedence.
- Verify that routing headers, forwarded-host headers, and route cookies cannot
  choose a pool. Unmapped Host must not dispatch to a backend.
- Reject non-pooled configuration and unknown ownership without legacy fallback.
- Use real PostgreSQL for admissions, capability publication, uncertainty,
  terminal retention, member lifecycle, and concurrent ledger operations.
- Use separate Gateway processes sharing that database for query and transaction
  affinity, drain races, cancellation, and graceful shutdown.
- Check canonical paths, duplicate headers/JSON, forged capabilities, conflicting
  lifecycle signals, and response parsing failures before unsafe settlement.
- Preserve database owner epochs, generation checks, replay receipts, serving
  floors, publication fences, and zero-obligation seal requirements.
- Preserve uncertainty after response loss, database failure, and process loss.
- Verify HTTP/1.1 and HTTP/2 Host forwarding and original Basic credential bytes.
- Run supported real-client tests against compatible coordinator builds in an
  isolated environment. Do not fabricate process identity or rewrite client
  protocol to hide incompatibility.

## Reproducible commands

Use [README.md](README.md) for the pooled HTTP, Java, and fixture commands.
The HTTP suites start two Gateway JVMs and disposable PostgreSQL. Snapshot their
compiled classes before a run; do not rebuild those classes during the run.

The native client procedure is in [client/README.md](client/README.md). External
legacy-topology fault oracles need a pooled setup before they are runnable. Their
presence does not establish that setup or a passing pooled guarantee.

## Limits

The current admission adapter supports stable Basic credentials and inline
results. Coordinator loss loses in-memory transactions. Gateway provides neither
transaction replication nor exactly-once execution. Uncertain work and open
transactions can block retirement indefinitely. Retained result capability
windows are finite; response bodies are not durable replay storage.

Hostname mapping is not customer provisioning, authorization, catalog movement,
or connector correctness. Database failover, mixed-version behavior, and
performance need separate representative verification. A full Gateway stop is
not uninterrupted service. Historical Flyway and lock rows remain required.

See [OPERATIONS.md](OPERATIONS.md), [BASELINE.md](BASELINE.md), and
[SCALE-VALIDATION.md](SCALE-VALIDATION.md) for the related boundaries.
