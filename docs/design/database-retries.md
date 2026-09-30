# Runtime metadata database retries

Gateway applies one retry policy at each complete metadata database operation.
It retries a fresh handle or transaction, not an individual statement inside a
failed transaction. Backend HTTP requests and customer SQL are never replayed by
this policy.

## Coverage

`GatewayDatabase` covers `TransactionStore`, `PoolStore`, `RolloutStore`, backend
registration DAO calls, query-history DAO calls, and scheduled history cleanup.
DAO default methods delegate through the same wrapper. Connection acquisition,
statement execution, transaction completion, and handle-close failures reach the
operation boundary.

Startup Flyway migrations retain their separate migration semantics. The Trino
JDBC cluster monitor executes backend queries, not metadata database operations,
and is excluded. Raw Jdbi construction helpers do not themselves retry arbitrary
future callers; runtime callers must use `GatewayDatabase`.

## Replay safety

| Operation | Retry after a transient connection failure or ambiguous commit? |
| --- | --- |
| Reads, including read-only locking transactions | Yes |
| Recognized transient failure acquiring a handle before any operation executes | Yes |
| Recording the same response for the same admission | Yes; the stored observation must match |
| Marking the same admission uncertain or rejected | Yes; existing completion is not overwritten |
| Pool steps with a durable operation ID, payload hash, and result receipt | Yes; the receipt resolves replay before another effect |
| Other writes, including new admissions and history inserts | No; the operation may already have committed |

All runtime operations can retry PostgreSQL serialization failure (`40001`),
deadlock (`40P01`), or lock acquisition failure (`55P03`) after the failed
transaction or statement is rolled back. Connection-class SQLStates (`08...`),
server shutdown (`57P01`), and temporarily unavailable server (`57P03`) require
the replay-safe cases above. State-less connection failures, including local
pool-capacity exhaustion, fail promptly without adding retry load. Integrity,
authentication, syntax, cancellation, and unknown failures are not assumed transient.

A handle-close failure after a successful write does not establish rollback,
even if its SQLState normally denotes an aborted transaction. Non-idempotent
writes are not replayed in that case either.

Each operation permits at most three attempts, with small randomized delays.
The retry scheduling budget is ten seconds, shortened by an existing request or
completion deadline. No new attempt starts after that deadline. This is **not a
hard wall-clock cancellation guarantee**: an in-flight JDBC call still uses the
configured connection, socket, and statement timeouts and can finish later.
Configure those driver timeouts for unpooled metadata connections too.
The scheduling budget does not replace or shorten the configured statement timeout.

Retries run on the existing caller/completion thread. They add no executor,
background scanner, or unbounded queue. The existing request permits and database
pool continue to bound concurrency. Interruptions preserve the interrupt flag and
stop retrying. An already-interrupted caller retains its initial persistence attempt.
There are no nested service/store retry loops.

## Diagnostics

`DATABASE_OPERATION_FAILED` logs the bounded operation name, database phase,
attempt count, outcome, exception class, SQLState, and vendor code. They do not
include exception messages, SQL text, bound parameters, or credentials.
Unexpected non-SQL failures log their exception class without their message.
Expected store conflicts do not produce database failure warnings.

When a deadline expires between attempts, the `DEADLINE` diagnostic retains the
last database failure's class, SQLState, and vendor code. That failure remains
the thrown exception's cause. An already-expired phase before the initial
attempt instead reports `PHASE_DEADLINE`, zero attempts, and `NONE` for the
exception class and SQLState; no database request has failed in that case.

Identical failure signatures are sampled for 30 seconds. Different operation,
outcome, exception class, SQLState, or vendor-code signatures are independent;
the sampling cache holds at most 128 signatures. `suppressed` reports the number
of duplicate warnings suppressed across the process since its previous emitted
warning. Individual retries and recovered operations are available at DEBUG as
`DATABASE_RETRY` and `DATABASE_RETRY_RECOVERED`.

Admission lifecycle logs still provide the admission/query correlation. A failed
uncertainty write remains a durable drain blocker. Retrying that write does not
prove that the request or transaction ended. These changes do not expire orphaned
admissions, recover lost in-memory observations after a Gateway crash, or provide
automatic recovery from a prolonged database outage.
An ambiguous commit of a new admission still fails closed and can leave a drain
blocker. Automatic replay could admit a second request or select a different backend.
