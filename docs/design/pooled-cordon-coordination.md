# Pooled cordon coordination

Pooled draining separates three operations:

1. **Cordon:** mark a member DRAINING. New independent admissions read the
   authoritative state and reject it. Work admitted concurrently can still finish.
   Existing transaction statements and result requests remain permitted.
2. **Complete:** record query, transaction, and result obligations and settle the
   request in one database transaction. Completion does not acquire the pooled
   lifecycle row lock.
3. **Seal:** exclude admission, then check every obligation in one fresh database
   snapshot. Nonzero obligations leave the member DRAINING. An empty member becomes
   SEALED before the controller can remove it.

The API and controller workflow are unchanged. Readiness is advisory; only a
successful seal authorizes ordinary retirement. This change does not cancel
queries, close transactions, or impose a drain deadline.

## Locks and snapshots

Pooled admissions hold a shared transaction-level advisory barrier through
commit. Final sealing takes its exclusive counterpart. The barrier belongs to
the immutable backend incarnation, not a Gateway process.

The exact obligation read must occur in a separate statement **after** acquiring
the barrier, under READ COMMITTED. A snapshot taken before a lock wait can miss a
newly committed admission. Independently reading each obligation category can
also miss a request that atomically becomes a query or transaction between reads.

Admission and pooled lifecycle/candidate transactions explicitly select READ
COMMITTED, including when the connection default is REPEATABLE READ. Candidate
enumeration, absence reconciliation, LOST, and retirement also take the exclusive
barrier where their decisions require admission exclusion. Cordon and suspicion
use a shared route lock; the pool row still serializes authority and membership
changes.

The first statement sets transaction-local isolation. It does not read, change,
or restore the connection's session isolation setting on every request.

Completion still serializes updates to its admission, query, and transaction.
Removing its lifecycle lock does not remove these ownership checks or make
completion lock-free. An outstanding admission remains visible until the same
commit publishes any replacement obligations.

The lock order is route, pool authority, member row, then the admission barrier
for lifecycle operations. Admissions acquire their member row lock and shared
barrier before query or transaction identity locks. Completion locks its admission
row, then query and transaction identities; it does not acquire the pooled
lifecycle barrier.

Late completion can still settle an existing admission after an explicitly
authorized LOST transition. It does not reactivate the member. Failure receipts
remain historical observations, not a promise that every response was already
recorded when the receipt was created.

## Rolling upgrade

New admissions retain a backend KEY SHARE lock. This conflicts with an old
Gateway's FOR UPDATE seal. New lifecycle operations use NO KEY UPDATE, which
conflicts with an old admission's FOR SHARE lock. New admissions and new sealers
also use their shared/exclusive advisory barrier.

Existing legacy seal checks already read all obligations in one SQL statement.
They therefore observe either the pending admission or its atomically published
replacement obligations when a new Gateway records completion concurrently.

This bridge preserves exclusion in both upgrade directions without requiring
every replica to switch simultaneously. Old replicas can still cause the original
contention during a mixed-version rollout. Foreign-key checks can take KEY SHARE
locks; these are compatible with the new lifecycle lock.

Mixed-version safety requires older replicas to use READ COMMITTED. Before a
rolling upgrade, verify the database, role, and client settings used by the old
replicas, including `SHOW default_transaction_isolation`. An old replica that
uses REPEATABLE READ can retain its preexisting stale-snapshot bug; upgrading
another replica cannot correct it. If that precondition cannot be established,
send lifecycle administration only to upgraded replicas until the rollout ends.

Do not add a non-partial unique index covering lifecycle columns such as `state`
or `generation` without revisiting this protocol. Updating a PostgreSQL key can
escalate NO KEY UPDATE to FOR UPDATE and reintroduce incompatible lock ordering.
The current schema's unique backend keys cover identity, not lifecycle state.

A pooled incarnation remains pooled after retirement or a group-mode change.
Legacy admission, route selection, and lifecycle administration reject that
identity rather than reactivating it through the legacy API.
Returning a group to legacy mode requires a new non-pooled backend with a fresh
name, endpoint, and process identity. See the
[operator guidance](../operation.md#pooled-members-and-legacy-administration).

## Recovery remains conservative

This change does not automatically clear orphaned admissions. In particular:

- A queued-statement GET can initiate execution; a GET is not necessarily a
  read-only result request.
- A missing Kubernetes Pod does not prove process termination after force
  deletion or a network partition.
- A coordinator absence response is a snapshot, not a fence against a request
  already dispatched to that coordinator.
- Existing terminal flags include failure and cancellation, not only successful
  execution completion.

Unknown outcomes remain blockers. Never replay customer SQL or settle an
admission merely because it is old. Automatic orphan recovery needs additional,
validated request provenance and execution fencing; it is not part of this
locking change. Historical records receive no inferred recovery evidence.

## Database cost

Keep durable admission before dispatch and completion after a response. No
per-replica lease service, capability redesign, or in-memory reference-count
protocol replaces these writes. The purpose is to remove lifecycle contention,
not to claim fewer database calls or a measured throughput improvement.

PostgreSQL documents the relevant [row-lock compatibility](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS)
and [READ COMMITTED snapshots](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED).
