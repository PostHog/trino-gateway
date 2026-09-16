"""Exercise SIGTERM during a retained response through real Gateway processes."""

import base64
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

from protocol import finish, request, through_gateway
from test_rollout_api import local_gateways


class GracefulShutdownContract(unittest.TestCase):
    def test_inflight_continuation_completes_before_process_exits(self):
        processes = []
        with local_gateways(processes=processes, server_config={"http-server.stop-timeout": "10s"}) as (gateways, backends, _token):
            headers = [("Authorization", "Basic " + base64.b64encode(b"fixture:disposable").decode())]
            started = request(gateways[0] + "/v1/statement", "POST", "START TRANSACTION", headers)
            self.assertEqual(started.status, 200, started.body)
            transaction = started.values("X-Trino-Started-Transaction-Id")
            self.assertEqual(len(transaction), 1)
            for page in finish(started, gateways[0]):
                self.assertEqual(page.status, 200, page.body)
            submitted = request(gateways[0] + "/v1/statement", "POST", "SELECT 1", headers)
            self.assertEqual(submitted.status, 200, submitted.body)
            continuation = submitted.json()["nextUri"]
            path = urlsplit(continuation).path
            for backend in backends:
                with backend.state.lock:
                    backend.state.config["hold_poll"] = True
                    backend.state.poll_release.clear()
            with ThreadPoolExecutor(max_workers=1) as executor:
                pending = executor.submit(request, through_gateway(continuation, gateways[0]))
                try:
                    deadline = time.monotonic() + 10
                    while not self.observed_path(backends, path):
                        self.assertLess(time.monotonic(), deadline, "Continuation did not reach its backend")
                        time.sleep(0.02)
                    processes[0].terminate()
                    time.sleep(1)
                    self.assertIsNone(processes[0].poll(), "Gateway exited before its admitted response completed")
                    self.assertFalse(pending.done(), "Gateway discarded an admitted response during shutdown")
                finally:
                    for backend in backends:
                        with backend.state.lock:
                            backend.state.config["hold_poll"] = False
                            backend.state.poll_release.set()
                response = pending.result(timeout=10)
                self.assertEqual(response.status, 200, response.body)
                self.assertEqual(response.json()["id"], submitted.json()["id"])
                processes[0].wait(timeout=15)
                replay = request(through_gateway(continuation, gateways[1]))
                self.assertEqual(replay.status, 200, replay.body)
                self.assertEqual(replay.json()["id"], submitted.json()["id"])
                committed = request(gateways[1] + "/v1/statement", "POST", "COMMIT",
                                    headers + [("X-Trino-Transaction-Id", transaction[0])])
                self.assertEqual(committed.status, 200, committed.body)
                pages = finish(committed, gateways[1])
                self.assertTrue(any(page.values("X-Trino-Clear-Transaction-Id") for page in pages))
                for page in pages:
                    self.assertEqual(page.status, 200, page.body)
                    self.assertNotIn("error", page.json())

    @staticmethod
    def observed_path(backends, path):
        for backend in backends:
            with backend.state.lock:
                if any(item["path"] == path for item in backend.state.requests):
                    return True
        return False
