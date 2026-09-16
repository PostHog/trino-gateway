"""Bounded read-only traffic during an independently controlled Gateway rollout."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import getpass
import json
import math
import os
import re
import threading
import time
import uuid

from rollout_client import RolloutClient, RolloutFailure, recovery_report, validate_recovery_directory


class OperationLedger:
    KINDS = {"autocommit", "transaction_begin", "transaction_read", "transaction_commit",
             "transaction_rollback", "transaction_cleanup", "retained", "continuation_resume"}

    def __init__(self, event):
        self.event, self.lock, self.operations = event, threading.Lock(), {}

    def offer(self, kind, parent_operation_id=None):
        if kind not in self.KINDS:
            raise ValueError("unknown_operation_kind")
        with self.lock:
            if parent_operation_id is not None and (kind != "continuation_resume" or
                    self.operations.get(parent_operation_id, {}).get("state") != "failed"):
                raise ValueError("invalid_resume_parent")
            if kind == "continuation_resume" and parent_operation_id is None:
                raise ValueError("missing_resume_parent")
            operation_id = str(uuid.uuid4())
            self.operations[operation_id] = {"kind": kind, "state": "offered", "parent_operation_id": parent_operation_id}
        self.event("operation_offered", operation_id=operation_id, kind=kind, parent_operation_id=parent_operation_id)
        return operation_id

    def transition(self, operation_id, expected, state, metadata=None):
        with self.lock:
            record = self.operations.get(operation_id)
            if record is None or record["state"] != expected:
                raise ValueError("invalid_operation_transition")
            record["state"] = state
            fields = dict(record)
        self.event("operation_state", operation_id=operation_id, **fields, **(metadata or {}))

    def submit(self, operation_id):
        self.transition(operation_id, "offered", "unresolved")

    def drop_capacity(self, operation_id):
        self.transition(operation_id, "offered", "not_submitted_capacity")

    def finish(self, operation_id, outcome, metadata=None):
        if outcome not in {"succeeded", "failed"}:
            raise ValueError("invalid_operation_outcome")
        self.transition(operation_id, "unresolved", outcome, metadata)

    def run(self, kind, operation, validate=None, operation_id=None, parent_operation_id=None, include_query_metadata=False):
        if operation_id is None:
            operation_id = self.offer(kind, parent_operation_id)
        else:
            with self.lock:
                if self.operations.get(operation_id, {}).get("kind") != kind:
                    raise ValueError("operation_kind_changed")
        self.submit(operation_id)
        try:
            result = operation()
            if validate is not None:
                validate(result)
            metadata = query_metadata(result) if include_query_metadata else None
        except BaseException as error:
            self.finish(operation_id, "failed")
            error.operation_id = operation_id
            raise
        self.finish(operation_id, "succeeded", metadata)
        return result

    def snapshot(self):
        with self.lock:
            records = [dict(record) for record in self.operations.values()]
        states = Counter(record["state"] for record in records)
        submitted = sum(states[state] for state in ("succeeded", "failed", "unresolved"))
        counts = {"offered": len(records), "submitted": submitted,
                  **{state: states[state] for state in ("succeeded", "failed", "unresolved", "not_submitted_capacity")},
                  "not_submitted_pending": states["offered"]}
        counts["accounting_valid"] = (counts["submitted"] == counts["succeeded"] + counts["failed"] + counts["unresolved"] and
                                      counts["offered"] == counts["submitted"] + counts["not_submitted_capacity"] + counts["not_submitted_pending"])
        counts["zero_error_acceptance"] = (counts["accounting_valid"] and counts["submitted"] > 0 and
                                           not any(counts[key] for key in ("failed", "unresolved", "not_submitted_capacity", "not_submitted_pending")))
        counts["by_kind"] = {kind: dict(Counter(record["state"] for record in records if record["kind"] == kind))
                             for kind in sorted({record["kind"] for record in records})}
        return counts


def query_metadata(result):
    if (not isinstance(result, dict) or not isinstance(result.get("query_id"), str) or
            not re.fullmatch(r"[0-9]{8}_[0-9]{6}_[0-9]{1,20}_[A-Za-z0-9]{5}", result["query_id"]) or
            type(result.get("pages")) is not int or not 1 <= result["pages"] <= 500 or
            not isinstance(result.get("rows"), list)):
        raise ValueError("invalid_query_metadata")
    return {"query_id": result["query_id"], "pages": result["pages"], "row_count": len(result["rows"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--group")
    parser.add_argument("--seconds", type=int, default=240)
    parser.add_argument("--rate", type=float, default=4)
    parser.add_argument("--transaction-seconds", type=int, default=180)
    parser.add_argument("--retained-seconds", type=int, default=100)
    parser.add_argument("--recovery-directory", help="Opt in to private capability files in an existing mode-0700 directory")
    args = parser.parse_args()
    validate_recovery_directory(args.recovery_directory)
    if not 30 <= args.seconds <= 600 or not 0 <= args.rate <= 4:
        raise ValueError("Bound the run to 30-600 seconds and at most four queries per second")
    if not 10 <= args.transaction_seconds < args.seconds:
        raise ValueError("Transactions must finish within the traffic window")
    if not 5 <= args.retained_seconds < args.transaction_seconds:
        raise ValueError("Retained results must resume before transactions finish")
    password = os.environ.get("TX_TRINO_PASSWORD") or getpass.getpass("Trino password: ")
    started, lock, capacity = time.monotonic(), threading.Lock(), threading.Semaphore(16)
    counts, durations, owners = Counter(), [], set()
    failed_continuations = []
    open_transactions, retained_ready, ready_reported = set(), threading.Event(), threading.Event()

    def client():
        return RolloutClient(args.server, args.user, args.catalog, password, args.group)

    def event(name, **fields):
        with lock:
            print(json.dumps({"event": name, "elapsed_seconds": time.monotonic() - started,
                              "utc": datetime.now(timezone.utc).isoformat(), **fields}), flush=True)

    ledger = OperationLedger(event)

    def failure_fields(error):
        handle = getattr(error, "recovery", None)
        if handle is not None:
            with lock:
                failed_continuations.append(handle)
        return {"kind": type(error).__name__, "operation_id": getattr(error, "operation_id", None),
                "detail": str(error) if isinstance(error, RolloutFailure) else "client_error:" + type(error).__name__,
                **recovery_report(error, args.recovery_directory)}

    def checked(connection, sql, kind, expected=None, validate=None, operation_id=None, **kwargs):
        def validate_result(result):
            if expected is not None and result["rows"] != expected:
                raise RuntimeError("result_mismatch")
            if validate is not None:
                validate(result)
        result = ledger.run(kind, lambda: connection.query(sql, **kwargs), validate_result,
                            operation_id=operation_id, include_query_metadata=True)
        with lock:
            owners.add(result["query_id"].rsplit("_", 1)[-1])
            counts["statements_completed"] += 1
            counts["http_pages_completed"] += result["pages"]
        return result

    def readiness():
        with lock:
            ready = len(open_transactions) == 3 and retained_ready.is_set() and not ready_reported.is_set()
            if ready:
                ready_reported.set()
        if ready:
            event("workload_ready_for_rollout", open_transactions=3, retained_continuation=True)

    def small(operation_id):
        try:
            result = checked(client(), "SELECT 1", "autocommit", [[1]], operation_id=operation_id)
            with lock:
                counts["small_completed"] += 1
                durations.append(result["duration_seconds"])
        except Exception as error:
            with lock:
                counts["small_failed"] += 1
                counts["error_" + type(error).__name__] += 1
            event("small_failure", **failure_fields(error))
        finally:
            capacity.release()

    def transaction(index):
        connection, transaction_owner = client(), None
        def validate_begin(result):
            if connection.transaction == "NONE":
                raise RuntimeError("missing_transaction")
        def validate_read(result):
            if result["query_id"].rsplit("_", 1)[-1] != transaction_owner:
                raise RuntimeError("transaction_owner_changed")
            if connection.transaction != transaction_id:
                raise RuntimeError("transaction_identity_changed")
        def validate_terminal(result):
            if connection.transaction != "NONE" or (transaction_owner is not None and
                    result["query_id"].rsplit("_", 1)[-1] != transaction_owner):
                raise RuntimeError("transaction_terminal_mismatch")
        try:
            result = checked(connection, "START TRANSACTION READ ONLY", "transaction_begin", validate=validate_begin)
            transaction_owner = result["query_id"].rsplit("_", 1)[-1]
            transaction_id = connection.transaction
            with lock:
                open_transactions.add(index)
            event("transaction_open", index=index, owner=transaction_owner)
            readiness()
            until = time.monotonic() + args.transaction_seconds
            while time.monotonic() < until:
                result = checked(connection, "SELECT count(*) >= 0 FROM information_schema.tables", "transaction_read",
                                 [[True]], validate=validate_read)
                time.sleep(min(5, max(0, until - time.monotonic())))
            action = "ROLLBACK" if index == 2 else "COMMIT"
            result = checked(connection, action, "transaction_" + action.lower(), validate=validate_terminal)
            with lock:
                counts["transactions_completed"] += 1
            event("transaction_finished", index=index, terminal=action)
        except Exception as error:
            with lock:
                counts["transactions_failed"] += 1
            event("transaction_failure", index=index, **failure_fields(error))
        finally:
            if connection.transaction != "NONE":
                try:
                    checked(connection, "ROLLBACK", "transaction_cleanup", validate=validate_terminal)
                    event("transaction_cleanup_rollback", index=index)
                except Exception as error:
                    with lock:
                        counts["cleanup_failed"] += 1
                    event("transaction_cleanup_failed", index=index, **failure_fields(error))
            with lock:
                open_transactions.discard(index)

    def retained():
        def first_page():
            retained_ready.set()
            event("retained_continuation_open")
            readiness()
        def validate_retained(result):
            if result["pages"] < 2:
                raise RuntimeError("no_retained_continuation")
        try:
            result = checked(client(), "SELECT n FROM UNNEST(sequence(1, 10000)) AS t(n) ORDER BY n",
                             "retained", [[n] for n in range(1, 10001)], validate=validate_retained,
                             deadline_seconds=args.retained_seconds + 60,
                             first_page_pause=args.retained_seconds, first_page_callback=first_page)
            with lock:
                counts["retained_query_completed"] += 1
            event("retained_query_finished", duration_seconds=result["duration_seconds"], pages=result["pages"])
        except Exception as error:
            with lock:
                counts["retained_query_failed"] += 1
            event("retained_query_failure", **failure_fields(error))

    event("workload_started", offered_queries_per_second=args.rate, seconds=args.seconds,
          transaction_seconds=args.transaction_seconds, concurrency_cap=16, client_retries=0)
    extra = [threading.Thread(target=transaction, args=(index,)) for index in range(3)]
    extra.append(threading.Thread(target=retained))
    for thread in extra:
        thread.start()
    with ThreadPoolExecutor(max_workers=16) as executor:
        for index in range(math.ceil(args.seconds * args.rate)):
            time.sleep(max(0, started + index / args.rate - time.monotonic()))
            with lock:
                counts["small_offered"] += 1
            operation_id = ledger.offer("autocommit")
            if capacity.acquire(blocking=False):
                executor.submit(small, operation_id)
            else:
                ledger.drop_capacity(operation_id)
                with lock:
                    counts["small_not_submitted_capacity"] += 1
    for thread in extra:
        thread.join()
    ordered = sorted(durations)
    latency = {"min": min(ordered), "max": max(ordered), "mean": sum(ordered) / len(ordered),
               "p50": ordered[int((len(ordered) - 1) * .50)],
               "p95": ordered[int((len(ordered) - 1) * .95)],
               "p99": ordered[int((len(ordered) - 1) * .99)]} if ordered else {}
    accounting = ledger.snapshot()
    event("summary", counts=dict(counts), operation_accounting=accounting,
          small_latency_seconds=latency, query_owners=sorted(owners), client_retries=0,
          failed_continuations=len(failed_continuations), recovery_persistence_requested=bool(args.recovery_directory))
    failed = any(counts[key] for key in ("small_failed", "small_not_submitted_capacity",
                                        "transactions_failed", "retained_query_failed", "cleanup_failed"))
    failed = failed or counts["transactions_completed"] != 3 or counts["retained_query_completed"] != 1
    failed = failed or not ready_reported.is_set() or not accounting["zero_error_acceptance"]
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
