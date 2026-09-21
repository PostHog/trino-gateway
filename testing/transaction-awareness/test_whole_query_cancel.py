"""Whole-query cancellation across two Gateway processes and one PostgreSQL."""

import base64
from concurrent.futures import ThreadPoolExecutor
import json
import secrets
import time
import unittest
from urllib.parse import urlsplit

from protocol import finish, request, statement, through_gateway
from test_rollout_api import local_gateways


class WholeQueryCancellationContract(unittest.TestCase):
    def setup_fixture(self, gateways, backends, token):
        self.gateways, self.backends, self.token = gateways, backends, token
        self.authorization = "Basic " + base64.b64encode(
            ("fixture-user:" + secrets.token_hex(16)).encode()).decode()

    def submit(self, sql, transaction="NONE", replica=0):
        result = statement(self.gateways[replica], sql, transaction,
                           routing_group="cell", extra=[("Authorization", self.authorization)])
        self.assertEqual(result.status, 200, result.body)
        return result

    def owner(self, initial):
        suffix = initial.json()["id"].rsplit("_", 1)[-1]
        return next(backend for backend in self.backends if backend.state.coordinator_id == suffix)

    def admin(self, name, suffix="", method="GET", body=None, replica=0):
        response = request(self.gateways[replica] + "/gateway/transactions/backends/" + name + suffix,
                           method, None if body is None else json.dumps(body),
                           [("Authorization", "Bearer " + self.token), ("Content-Type", "application/json")])
        return response

    def status(self, name):
        response = self.admin(name, "/drain")
        self.assertEqual(response.status, 200, response.body)
        return response.json()

    def eventually(self, predicate):
        deadline = time.monotonic() + 10
        while not predicate():
            self.assertLess(time.monotonic(), deadline, "Expected obligation transition did not occur")
            time.sleep(0.05)

    def test_cancel_keeps_inflight_poll_and_retention_until_safe_seal(self):
        with local_gateways() as fixture:
            self.setup_fixture(*fixture)
            initial = self.submit("SELECT 1")
            backend = self.owner(initial)
            name = backend.state.identity
            uri = through_gateway(initial.json()["nextUri"], self.gateways[0])
            backend.state.poll_release.clear()
            with backend.state.lock:
                backend.state.config["hold_poll"] = True
            with ThreadPoolExecutor(max_workers=1) as executor:
                future = executor.submit(request, uri)
                try:
                    self.eventually(lambda: self.status(name)["pendingRequests"] == 1)
                    cancelled = request(through_gateway(uri, self.gateways[1]), "DELETE")
                    self.assertEqual(cancelled.status, 204, cancelled.body)
                    self.assertEqual(request(uri, "HEAD").status, 200)
                    draining = self.admin(name, "/drain", "POST", replica=1)
                    self.assertEqual(draining.status, 200, draining.body)
                    self.assertEqual(self.status(name)["activeQueries"], 1)
                    self.eventually(lambda: self.status(name)["activeQueries"] == 0)
                    status = self.status(name)
                    self.assertEqual(status["pendingRequests"], 1)
                    self.assertFalse(status["readyToSeal"])
                    self.assertEqual(self.admin(name, "/seal", "POST", {"generation": status["generation"]}).status, 409)
                finally:
                    backend.state.poll_release.set()
                completed = future.result(timeout=10)
                self.assertEqual(completed.status, 200, completed.body)
                self.assertNotIn("nextUri", completed.json())
            self.eventually(lambda: self.status(name)["readyToSeal"])
            status = self.status(name)
            self.assertEqual(self.admin(name, "/seal", "POST", {"generation": status["generation"]}, replica=1).status, 200)

    def test_partial_cancel_and_404_do_not_close_query_or_transaction(self):
        with local_gateways() as fixture:
            self.setup_fixture(*fixture)
            start = self.submit("START TRANSACTION")
            pages = finish(start, self.gateways[1])
            self.assertTrue(all(page.status == 200 for page in pages))
            transaction = next(value for page in pages for value in page.values("X-Trino-Started-Transaction-Id"))
            backend = self.owner(start)
            name = backend.state.identity
            with backend.state.lock:
                backend.state.config["partial_cancel"] = True
            initial = self.submit("SELECT 1", transaction)
            partial = through_gateway(initial.json()["partialCancelUri"], self.gateways[1])
            self.assertEqual(request(partial, "DELETE").status, 204)
            uri = through_gateway(initial.json()["nextUri"], self.gateways[1])
            path = urlsplit(uri).path
            with backend.state.lock:
                held_result = backend.state.queries.pop(path)
            try:
                self.assertEqual(request(uri).status, 404)
                self.assertEqual(request(uri, "DELETE").status, 404)
            finally:
                with backend.state.lock:
                    backend.state.queries[path] = held_result
            time.sleep(1.2)
            self.assertEqual(self.status(name)["activeQueries"], 1)
            self.assertEqual(self.status(name)["openTransactions"], 1)
            self.assertEqual(request(uri, "DELETE").status, 204)
            self.assertEqual(self.admin(name, "/drain", "POST").status, 200)
            self.eventually(lambda: self.status(name)["activeQueries"] == 0)
            status = self.status(name)
            self.assertEqual(status["openTransactions"], 1)
            self.assertFalse(status["readyToSeal"])
            self.assertEqual(self.admin(name, "/seal", "POST", {"generation": status["generation"]}).status, 409)
            rollback = finish(self.submit("ROLLBACK", transaction, replica=1), self.gateways[0])
            self.assertTrue(all(page.status == 200 for page in rollback))
            self.assertTrue(any(page.values("X-Trino-Clear-Transaction-Id") for page in rollback))
            self.eventually(lambda: self.status(name)["readyToSeal"])
            self.assertEqual(self.admin(name, "/seal", "POST", {"generation": self.status(name)["generation"]}).status, 200)


if __name__ == "__main__":
    unittest.main()
