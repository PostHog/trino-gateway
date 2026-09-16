"""Check an externally released executing-result hold without cluster access."""

import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from protocol import Response
from rollout_client import RolloutClient, RolloutFailure
from retained_barrier import ExecutingResultHold, ReleaseMarker, retained_rows, retained_sql, validate_retained_result


QUERY = "20260101_000000_00001_owner"
QUEUED = f"https://gateway.example/v1/statement/queued/{QUERY}/slug/0"
EXECUTING = f"https://gateway.example/v1/statement/executing/{QUERY}/slug/0"
NEXT = f"https://gateway.example/v1/statement/executing/{QUERY}/slug/1"


def response(rows=None, next_uri=None, state=None):
    body = {"id": QUERY, "stats": {"state": state or ("RUNNING" if next_uri else "FINISHED")}}
    if rows is not None:
        body["data"] = rows
    if next_uri:
        body["nextUri"] = next_uri
    return Response(200, [], json.dumps(body).encode())


class RetainedBarrierTest(unittest.TestCase):
    def test_only_executing_data_page_enters_hold_and_heads_exact_next_uri(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = ReleaseMarker(str(Path(directory) / "release"))
            events, held = [], []
            hold = ExecutingResultHold(marker, 2, threading.Event(),
                                       lambda name, **fields: events.append((name, fields)),
                                       lambda: held.append(True), expected_rows=3, padding=4)
            client = RolloutClient("https://gateway.example", "reader", "catalog", "password")
            calls = []

            def request(uri, method, body=None, **kwargs):
                calls.append((uri, method))
                if method == "HEAD":
                    self.assertEqual(uri, NEXT)
                    self.assertTrue(held)
                    self.assertEqual(len(calls), 4)
                    Path(directory, "release").touch(mode=0o600)
                    return Response(200, [], b"")
                return [response(next_uri=QUEUED), response(next_uri=EXECUTING),
                        response(retained_rows(1, 4), NEXT), response(retained_rows(3, 4)[1:])][
                            sum(method == "GET" or method == "POST" for _, method in calls) - 1]

            try:
                with patch("rollout_client.request", side_effect=request), patch("retained_barrier.request", side_effect=request):
                    result = client.query(retained_sql(), executing_page_callback=hold)
                self.assertEqual(result["rows"], retained_rows(3, 4))
                self.assertEqual(result["retained_hold_outcome"], "released")
                self.assertEqual(result["heartbeat_requests"], 1)
                self.assertEqual([method for _, method in calls], ["POST", "GET", "GET", "HEAD", "GET"])
                self.assertNotIn("password", str(events))
                self.assertNotIn(NEXT, str(events))
            finally:
                marker.close()

    def test_timeout_or_stop_consumes_remaining_results_but_does_not_claim_release(self):
        for stopped in (False, True):
            with self.subTest(stopped=stopped), tempfile.TemporaryDirectory() as directory:
                marker = ReleaseMarker(str(Path(directory) / "release"))
                stop = threading.Event()
                if stopped:
                    stop.set()
                hold = ExecutingResultHold(marker, 0, stop, lambda *args, **kwargs: None,
                                           lambda: None, expected_rows=2, padding=4)
                client = RolloutClient("https://gateway.example", "reader", "catalog", "password")
                try:
                    with patch("rollout_client.request", side_effect=[response(next_uri=EXECUTING),
                               response(retained_rows(1, 4), NEXT), response(retained_rows(2, 4)[1:])]):
                        result = client.query(retained_sql(), executing_page_callback=hold)
                    self.assertEqual(result["rows"], retained_rows(2, 4))
                    self.assertEqual(result["retained_hold_outcome"], "stopped" if stopped else "timed_out")
                finally:
                    marker.close()

    def test_failed_heartbeat_is_not_retried_and_preserves_exact_capability(self):
        for heartbeat in (Response(503, [], b"private body"),
                          Response(200, [("X-Trino-Clear-Transaction-Id", "true")], b"")):
            with self.subTest(status=heartbeat.status), tempfile.TemporaryDirectory() as directory:
                marker = ReleaseMarker(str(Path(directory) / "release"))
                hold = ExecutingResultHold(marker, 2, threading.Event(), lambda *args, **kwargs: None,
                                           lambda: None, expected_rows=2, padding=4)
                client = RolloutClient("https://gateway.example", "reader", "catalog", "password")
                try:
                    with patch("rollout_client.request", side_effect=[response(next_uri=EXECUTING),
                               response(retained_rows(1, 4), NEXT)]), patch("retained_barrier.request", return_value=heartbeat) as request:
                        with self.assertRaises(RolloutFailure) as failed:
                            client.query(retained_sql(), executing_page_callback=hold)
                    self.assertEqual(request.call_count, 1)
                    self.assertEqual(failed.exception.recovery.next_uri, NEXT)
                    self.assertNotIn("private body", str(failed.exception))
                finally:
                    marker.close()

    def test_complete_or_invalid_prefix_cannot_claim_held_unread_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = ReleaseMarker(str(Path(directory) / "release"))
            try:
                for rows in (retained_rows(2, 4), [[999, "wrong"]]):
                    hold = ExecutingResultHold(marker, 2, threading.Event(), lambda *args, **kwargs: None,
                                               lambda: self.fail("must not announce ready"), expected_rows=2, padding=4)
                    client = RolloutClient("https://gateway.example", "reader", "catalog", "password")
                    with patch("rollout_client.request", side_effect=[response(next_uri=EXECUTING), response(rows, NEXT), response()]):
                        if rows[0][0] == 999:
                            with self.assertRaises(RolloutFailure):
                                client.query(retained_sql(), executing_page_callback=hold)
                        else:
                            result = client.query(retained_sql(), executing_page_callback=hold)
                            self.assertNotIn("retained_hold_outcome", result)
            finally:
                marker.close()

    def test_release_marker_rejects_preexisting_and_nonprivate_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "release")
            path.touch(mode=0o600)
            with self.assertRaises(ValueError):
                ReleaseMarker(str(path))
            path.unlink()
            os.chmod(directory, 0o755)
            with self.assertRaises(ValueError):
                ReleaseMarker(str(path))

    def test_late_marker_cannot_override_hold_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = ReleaseMarker(str(Path(directory) / "release"))
            hold = ExecutingResultHold(marker, 1, threading.Event(), lambda *args, **kwargs: None,
                                       lambda: None, expected_rows=2, padding=4)
            client = RolloutClient("https://gateway.example", "reader", "catalog", "password")
            try:
                with patch("retained_barrier.time.monotonic", side_effect=[0, 2, 2]), \
                        patch.object(marker, "released", return_value=True) as released:
                    outcome = hold(client, "GET", EXECUTING, {"id": QUERY, "nextUri": NEXT}, retained_rows(1, 4), 99)
                self.assertEqual(outcome["retained_hold_outcome"], "timed_out")
                released.assert_not_called()
            finally:
                marker.close()

    def test_workload_acceptance_requires_release_heartbeat_and_every_expected_row(self):
        rows = retained_rows()
        valid = {"rows": rows, "retained_hold_outcome": "released", "heartbeat_requests": 2}
        validate_retained_result(valid)
        for update in ({"retained_hold_outcome": "timed_out"}, {"retained_hold_outcome": "stopped"},
                       {"retained_hold_outcome": None}, {"heartbeat_requests": 0}, {"heartbeat_requests": True},
                       {"rows": rows[:-1]}, {"rows": [[True, "1" + "x" * 1024]] + rows[1:]},
                       {"rows": [[1, "wrong"]] + rows[1:]}):
            with self.subTest(keys=list(update)), self.assertRaises(ValueError):
                validate_retained_result({**valid, **update})


if __name__ == "__main__":
    unittest.main()
