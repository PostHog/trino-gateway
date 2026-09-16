"""Bounded read-only traffic during an independently controlled Gateway rollout."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import getpass
import json
import math
import os
import threading
import time

from rollout_client import RolloutClient, RolloutFailure, recovery_report, validate_recovery_directory


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

    def failure_fields(error):
        handle = getattr(error, "recovery", None)
        if handle is not None:
            with lock:
                failed_continuations.append(handle)
        return {"kind": type(error).__name__,
                "detail": str(error) if isinstance(error, RolloutFailure) else "client_error:" + type(error).__name__,
                **recovery_report(error, args.recovery_directory)}

    def checked(connection, sql, expected=None, **kwargs):
        result = connection.query(sql, **kwargs)
        if expected is not None and result["rows"] != expected:
            raise RuntimeError("result_mismatch")
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

    def small():
        try:
            result = checked(client(), "SELECT 1", [[1]])
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
        try:
            result = checked(connection, "START TRANSACTION READ ONLY")
            if connection.transaction == "NONE":
                raise RuntimeError("missing_transaction")
            transaction_owner = result["query_id"].rsplit("_", 1)[-1]
            transaction_id = connection.transaction
            with lock:
                open_transactions.add(index)
            event("transaction_open", index=index, owner=transaction_owner)
            readiness()
            until = time.monotonic() + args.transaction_seconds
            while time.monotonic() < until:
                result = checked(connection, "SELECT count(*) >= 0 FROM information_schema.tables", [[True]])
                if result["query_id"].rsplit("_", 1)[-1] != transaction_owner:
                    raise RuntimeError("transaction_owner_changed")
                if connection.transaction != transaction_id:
                    raise RuntimeError("transaction_identity_changed")
                time.sleep(min(5, max(0, until - time.monotonic())))
            action = "ROLLBACK" if index == 2 else "COMMIT"
            result = checked(connection, action)
            if connection.transaction != "NONE" or result["query_id"].rsplit("_", 1)[-1] != transaction_owner:
                raise RuntimeError("transaction_terminal_mismatch")
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
                    checked(connection, "ROLLBACK")
                    event("transaction_cleanup_rollback", index=index)
                except Exception as error:
                    with lock:
                        counts["cleanup_failed"] += 1
                    event("transaction_cleanup_failed", index=index, **failure_fields(error))

    def retained():
        def first_page():
            retained_ready.set()
            event("retained_continuation_open")
            readiness()
        try:
            result = checked(client(), "SELECT n FROM UNNEST(sequence(1, 10000)) AS t(n) ORDER BY n",
                             [[n] for n in range(1, 10001)], deadline_seconds=args.retained_seconds + 60,
                             first_page_pause=args.retained_seconds, first_page_callback=first_page)
            if result["pages"] < 2:
                raise RuntimeError("no_retained_continuation")
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
            if capacity.acquire(blocking=False):
                executor.submit(small)
            else:
                with lock:
                    counts["small_not_submitted_capacity"] += 1
    for thread in extra:
        thread.join()
    ordered = sorted(durations)
    latency = {"min": min(ordered), "max": max(ordered), "mean": sum(ordered) / len(ordered),
               "p50": ordered[int((len(ordered) - 1) * .50)],
               "p95": ordered[int((len(ordered) - 1) * .95)],
               "p99": ordered[int((len(ordered) - 1) * .99)]} if ordered else {}
    event("summary", counts=dict(counts), small_latency_seconds=latency, query_owners=sorted(owners), client_retries=0,
          failed_continuations=len(failed_continuations), recovery_persistence_requested=bool(args.recovery_directory))
    failed = any(counts[key] for key in ("small_failed", "small_not_submitted_capacity",
                                        "transactions_failed", "retained_query_failed", "cleanup_failed"))
    failed = failed or counts["transactions_completed"] != 3 or counts["retained_query_completed"] != 1
    failed = failed or not ready_reported.is_set()
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
