"""Verify complete logical-operation accounting without live query traffic."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import redirect_stdout
import io
import json
import threading
import unittest
from unittest.mock import patch

from rollout_workload import OperationLedger, main


class OperationLedgerTest(unittest.TestCase):
    def ledger(self):
        events = []
        return OperationLedger(lambda name, **fields: events.append({"event": name, **fields})), events

    def test_outcomes_partition_submissions_and_offers(self):
        ledger, _ = self.ledger()
        ledger.run("autocommit", lambda: 1)
        with self.assertRaises(RuntimeError):
            ledger.run("transaction_read", lambda: (_ for _ in ()).throw(RuntimeError("private SQL")))
        pending = ledger.offer("retained")
        ledger.submit(pending)
        dropped = ledger.offer("autocommit")
        ledger.drop_capacity(dropped)
        waiting = ledger.offer("autocommit")
        result = ledger.snapshot()
        self.assertEqual(result["submitted"], 3)
        self.assertEqual((result["succeeded"], result["failed"], result["unresolved"]), (1, 1, 1))
        self.assertEqual(result["offered"], 5)
        self.assertEqual(result["not_submitted_capacity"], 1)
        self.assertEqual(result["not_submitted_pending"], 1)
        self.assertTrue(result["accounting_valid"])
        self.assertFalse(result["zero_error_acceptance"])
        self.assertNotEqual(waiting, pending)

    def test_result_and_transaction_validation_happen_before_success(self):
        ledger, events = self.ledger()
        for kind in ("autocommit", "transaction_begin", "transaction_read", "transaction_commit",
                     "transaction_rollback", "transaction_cleanup", "retained"):
            with self.assertRaisesRegex(RuntimeError, "result_mismatch") as failure:
                ledger.run(kind, lambda: {"rows": [[2]]}, lambda result: (_ for _ in ()).throw(RuntimeError("result_mismatch")))
            self.assertIsNotNone(failure.exception.operation_id)
        self.assertEqual(ledger.snapshot()["succeeded"], 0)
        self.assertEqual(ledger.snapshot()["failed"], 7)
        self.assertEqual(len([row for row in events if row.get("state") == "failed"]), 7)

    def test_successful_explicit_resume_never_overwrites_original_failure(self):
        ledger, _ = self.ledger()
        with self.assertRaises(OSError) as failed:
            ledger.run("autocommit", lambda: (_ for _ in ()).throw(OSError("private capability")))
        ledger.run("continuation_resume", lambda: 1, parent_operation_id=failed.exception.operation_id)
        result = ledger.snapshot()
        self.assertEqual((result["submitted"], result["succeeded"], result["failed"]), (2, 1, 1))
        self.assertFalse(result["zero_error_acceptance"])

    def test_duplicate_finalization_and_unknown_resume_parent_are_rejected(self):
        ledger, _ = self.ledger()
        operation = ledger.offer("autocommit")
        ledger.submit(operation)
        ledger.finish(operation, "failed")
        with self.assertRaises(ValueError):
            ledger.finish(operation, "succeeded")
        with self.assertRaises(ValueError):
            ledger.offer("continuation_resume", parent_operation_id="unknown")
        self.assertEqual(ledger.snapshot()["failed"], 1)

    def test_threaded_operations_are_unique_complete_and_do_not_log_payloads(self):
        ledger, events = self.ledger()
        with ThreadPoolExecutor(max_workers=16) as executor:
            list(executor.map(lambda _: ledger.run("autocommit", lambda: "private SQL/password/capability"), range(200)))
        snapshot = ledger.snapshot()
        self.assertEqual(snapshot["submitted"], 200)
        self.assertEqual(snapshot["succeeded"], 200)
        self.assertTrue(snapshot["zero_error_acceptance"])
        offered = [row["operation_id"] for row in events if row["event"] == "operation_offered"]
        self.assertEqual(len(set(offered)), 200)
        self.assertNotIn("private", json.dumps(events))

    def test_workload_accounts_all_invocations_and_failed_commit_cleanup(self):
        clock, calls, lock = [0], [], threading.Lock()

        def monotonic():
            with lock:
                clock[0] += .1
                return clock[0]

        class Client:
            transaction = "NONE"

            def query(self, sql, **kwargs):
                with lock:
                    calls.append(sql)
                    sequence = len(calls)
                rows, pages = [], 1
                if sql.startswith("START"):
                    self.transaction = "transaction"
                elif sql == "COMMIT":
                    pass
                elif sql == "ROLLBACK":
                    self.transaction = "NONE"
                elif sql == "SELECT 1":
                    rows = [[1]]
                elif "information_schema" in sql:
                    rows = [[True]]
                else:
                    kwargs["first_page_callback"]()
                    rows, pages = [[n] for n in range(1, 10001)], 2
                return {"rows": rows, "pages": pages, "query_id": f"20260101_000000_{sequence:05d}_owner", "duration_seconds": .01}

        args = ["rollout_workload.py", "--server", "https://gateway.example", "--user", "reader",
                "--catalog", "catalog", "--seconds", "30", "--rate", ".1", "--transaction-seconds", "10",
                "--retained-seconds", "5"]
        output = io.StringIO()
        with patch("sys.argv", args), patch.dict("os.environ", {"TX_TRINO_PASSWORD": "private-password"}), \
                patch("rollout_workload.RolloutClient", side_effect=lambda *args: Client()), \
                patch("rollout_workload.time.monotonic", side_effect=monotonic), \
                patch("rollout_workload.time.sleep"), redirect_stdout(output), self.assertRaises(SystemExit) as stopped:
            main()
        self.assertEqual(stopped.exception.code, 1)
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        summary = next(row["operation_accounting"] for row in events if row["event"] == "summary")
        self.assertEqual(summary["submitted"], len(calls))
        self.assertEqual(summary["failed"], 2)
        self.assertEqual(summary["unresolved"], 0)
        self.assertEqual(summary["by_kind"]["transaction_commit"], {"failed": 2})
        self.assertEqual(summary["by_kind"]["transaction_cleanup"], {"succeeded": 2})
        self.assertEqual(summary["by_kind"]["transaction_rollback"], {"succeeded": 1})
        self.assertTrue(summary["accounting_valid"])
        query_events = [row for row in events if row.get("state") == "succeeded" and "query_id" in row]
        self.assertEqual(len(query_events), summary["succeeded"])
        self.assertEqual(len({row["query_id"] for row in query_events}), summary["succeeded"])
        for secret in ("private-password", "SELECT", "Authorization", "nextUri"):
            self.assertNotIn(secret, output.getvalue())

    def test_malformed_query_metadata_is_failed_not_successful(self):
        ledger, events = self.ledger()
        valid = {"query_id": "20260101_000000_00001_abcde", "pages": 2, "rows": [[1]]}
        for change in ({"query_id": "https://private.example/token"}, {"pages": True}, {"pages": 501},
                       {"rows": "private result"}, {"query_id": "q" * 300}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "invalid_query_metadata"):
                ledger.run("autocommit", lambda: {**valid, **change}, include_query_metadata=True)
        self.assertEqual(ledger.snapshot()["failed"], 5)
        self.assertEqual(ledger.snapshot()["succeeded"], 0)
        self.assertFalse(any("query_id" in row for row in events))


if __name__ == "__main__":
    unittest.main()
