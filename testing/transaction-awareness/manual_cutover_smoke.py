"""Exercise a manually prepared backend pair without publishing or scaling resources."""

import argparse
import base64
from collections import Counter
import json
import os
import threading
import time
from urllib.parse import quote, urlsplit

from protocol import request
from rollout_client import RolloutClient


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def owner(result):
    return result["query_id"].rsplit("_", 1)[-1]


class Admin:
    def __init__(self, server, user, password, token):
        parsed = urlsplit(server)
        require(parsed.scheme == "https" and not parsed.username and not parsed.password
                and not parsed.query and not parsed.fragment and parsed.path in ("", "/"), "invalid_admin_origin")
        self.server = server.rstrip("/") + "/gateway/transactions"
        auth = base64.b64encode((user + ":" + password).encode()).decode()
        self.headers = [("Authorization", "Basic " + auth),
                        ("X-Gateway-Transaction-Admin-Token", token), ("Content-Type", "application/json")]

    def call(self, path, method="GET", body=None, expected=200, error_contains=None):
        result = request(self.server + path, method, json.dumps(body) if body is not None else None,
                         self.headers, timeout=20)
        require(result.status == expected, "admin_http_status:" + str(result.status))
        if error_contains is not None:
            require(error_contains.encode() in result.body[:4096], "unexpected_administrative_rejection")
        return result.json() if expected == 200 else None

    def require_no_active_rollout(self, group):
        result = request(self.server + "/rollouts/" + quote(group, safe=""), headers=self.headers, timeout=20)
        require(result.status in (200, 404), "rollout_status_unavailable")
        if result.status == 200:
            require(result.json().get("phase") == "COMPLETE", "active_rollout_blocks_manual_test")


class CutoverSmoke:
    def __init__(self, args, admin, client_factory):
        self.args, self.admin, self.client_factory = args, admin, client_factory
        self.route_path = "/routes/" + quote(args.group, safe="")
        self.stop, self.release, self.retained_open = threading.Event(), threading.Event(), threading.Event()
        self.load_ready = threading.Event()
        self.lock = threading.Lock()
        self.errors, self.counts, self.threads = [], Counter(), []
        self.started = time.monotonic()
        self.tx = client_factory()
        self.route = None
        self.backends = {}

    def emit(self, event, **values):
        with self.lock:
            print(json.dumps({"event": event, "elapsed_seconds": round(time.monotonic() - self.started, 3),
                              **values}), flush=True)

    def healthy(self):
        with self.lock:
            require(not self.errors, "concurrent_query_failed")

    def status(self, name):
        return self.admin.call("/backends/" + quote(name, safe="") + "/drain")

    def unchanged_backend(self, before, state=None):
        after = self.status(before["name"])
        for key in ("name", "incarnation", "nodeId", "coordinatorId", "generation"):
            require(after[key] == before[key], "backend_changed:" + key)
        require(after["state"] == (state or before["state"]), "backend_state_changed")
        return after

    def unchanged_route(self):
        require(self.admin.call(self.route_path) == self.route, "route_changed_externally")

    def query(self, client, sql, expected=None, expected_owner=None, **kwargs):
        result = client.query(sql, **kwargs)
        if expected is not None:
            require(result["rows"] == expected, "query_result_mismatch")
        if expected_owner is not None:
            require(owner(result) == expected_owner, "query_owner_mismatch")
        with self.lock:
            self.counts["statements_completed"] += 1
            self.counts["http_pages_completed"] += result["pages"]
        return result

    def background(self, fn, category):
        def run():
            try:
                fn()
            except Exception as error:
                with self.lock:
                    self.errors.append(category)
                    self.counts[category + "_failed"] += 1
                self.emit("background_failure", category=category, kind=type(error).__name__, detail=str(error))
        thread = threading.Thread(target=run)
        self.threads.append(thread)
        thread.start()

    def load(self):
        client = self.client_factory()
        while not self.stop.is_set():
            start = time.monotonic()
            result = self.query(client, "SELECT 1", [[1]])
            require(owner(result) in {value["coordinatorId"] for value in self.backends.values()}, "unknown_query_owner")
            with self.lock:
                self.counts["autocommit_completed"] += 1
            self.load_ready.set()
            self.stop.wait(max(0, 1 - (time.monotonic() - start)))

    def retained(self):
        def hold():
            self.retained_open.set()
            require(self.release.wait(120), "retained_release_timeout")
        result = self.query(self.client_factory(),
                            "SELECT n FROM UNNEST(sequence(1, 10000)) AS t(n) ORDER BY n",
                            [[n] for n in range(1, 10001)], self.backends[self.args.blue]["coordinatorId"],
                            first_page_pause=0.01, first_page_callback=hold, deadline_seconds=180)
        require(result["pages"] >= 2, "missing_retained_continuation")
        with self.lock:
            self.counts["retained_completed"] += 1
        self.emit("retained_query_completed_on_blue")

    def cutover(self, target):
        self.healthy()
        self.unchanged_route()
        backend = self.unchanged_backend(self.backends[target], "ACTIVE")
        before = self.route
        result = self.admin.call(self.route_path, "PUT", {
            "expectedGeneration": before["generation"], "expectedBackendName": before["backendName"],
            "backendName": target, "backendIncarnation": backend["incarnation"]})
        require(result == {"routingGroup": self.args.group, "generation": before["generation"] + 1,
                           "backendName": target, "backendIncarnation": backend["incarnation"]}, "cutover_response_mismatch")
        self.route = result
        self.unchanged_route()
        self.emit("route_changed", backend=target, generation=result["generation"])

    def transition(self, name, action):
        self.healthy()
        self.unchanged_route()
        before = self.unchanged_backend(self.backends[name])
        require(action == "resume" or self.route["backendName"] != name, "cannot_drain_current_route")
        body = ({"expectedIncarnation": before["incarnation"], "expectedGeneration": before["generation"]}
                if action == "drain" else {"generation": before["generation"]})
        result = self.admin.call("/backends/" + quote(name, safe="") + "/" + action, "POST", body)
        for key in ("name", "incarnation", "nodeId", "coordinatorId"):
            require(result[key] == before[key], "transition_identity_changed")
        require(result["generation"] == before["generation"] + 1, "transition_generation_mismatch")
        require(result["state"] == {"drain": "DRAINING", "seal": "SEALED", "resume": "ACTIVE"}[action],
                "transition_state_mismatch")
        if action == "seal":
            require(result["drained"] is True, "sealed_but_not_drained")
        self.backends[name] = result
        self.emit("backend_transition", backend=name, action=action, generation=result["generation"])
        return result

    def wait_and_seal(self, name):
        deadline = time.monotonic() + self.args.drain_seconds
        while True:
            self.healthy()
            self.unchanged_route()
            status = self.unchanged_backend(self.backends[name], "DRAINING")
            if status["readyToSeal"]:
                break
            require(time.monotonic() < deadline, "drain_timeout")
            time.sleep(2)
        return self.transition(name, "seal")

    def run(self):
        failed = True
        try:
            self.admin.require_no_active_rollout(self.args.group)
            self.route = self.admin.call(self.route_path)
            require(self.route["backendName"] == self.args.blue and self.route["generation"] >= 1,
                    "initial_route_must_be_initialized_blue")
            for name in (self.args.blue, self.args.green):
                status = self.status(name)
                require(status["name"] == name and status["state"] == "ACTIVE" and status["coordinatorId"]
                        and status["nodeId"] and status["incarnation"], "backend_must_be_verified_active")
                self.backends[name] = status
            blue, green = self.backends[self.args.blue], self.backends[self.args.green]
            require(blue["coordinatorId"] != green["coordinatorId"], "coordinator_ids_must_differ")
            require(self.route["backendIncarnation"] == blue["incarnation"], "initial_route_incarnation_mismatch")
            self.query(self.tx, "START TRANSACTION READ ONLY", expected_owner=blue["coordinatorId"])
            tx_id = self.tx.transaction
            require(tx_id != "NONE", "transaction_not_started")
            self.query(self.tx, "SELECT count(*) >= 0 FROM information_schema.tables", [[True]], blue["coordinatorId"])
            require(self.tx.transaction == tx_id, "transaction_identity_changed")
            self.background(self.retained, "retained")
            self.background(self.load, "autocommit")
            require(self.retained_open.wait(45), "retained_query_not_open")
            require(self.load_ready.wait(45), "autocommit_workload_not_started")
            self.healthy()
            self.emit("cutover_workload_ready")
            self.cutover(self.args.green)
            self.query(self.client_factory(), "SELECT count(*) >= 0 FROM information_schema.tables", [[True]], green["coordinatorId"])
            for _ in range(3):
                self.query(self.tx, "SELECT count(*) >= 0 FROM information_schema.tables", [[True]], blue["coordinatorId"])
                require(self.tx.transaction == tx_id, "transaction_identity_changed")
            drained = self.transition(self.args.blue, "drain")
            require(drained["openTransactions"] >= 1 and not drained["readyToSeal"], "open_transaction_not_blocking_drain")
            self.admin.call("/backends/" + quote(self.args.blue, safe="") + "/seal", "POST",
                            {"generation": drained["generation"]}, expected=409,
                            error_contains="Transaction routing state rejected the operation: NOT_DRAINED")
            self.unchanged_backend(drained, "DRAINING")
            self.unchanged_route()
            self.emit("seal_blocked_by_open_transaction")
            self.release.set()
            self.threads[0].join(90)
            require(not self.threads[0].is_alive(), "retained_query_not_finished")
            self.healthy()
            self.query(self.tx, "SELECT count(*) >= 0 FROM information_schema.tables", [[True]], blue["coordinatorId"])
            require(self.tx.transaction == tx_id, "transaction_identity_changed")
            self.query(self.tx, "COMMIT", expected_owner=blue["coordinatorId"])
            require(self.tx.transaction == "NONE", "transaction_not_cleared")
            self.emit("blue_transaction_committed_after_cutover")
            self.wait_and_seal(self.args.blue)
            self.transition(self.args.blue, "resume")
            self.cutover(self.args.blue)
            self.query(self.client_factory(), "SELECT count(*) >= 0 FROM information_schema.tables", [[True]], blue["coordinatorId"])
            self.transition(self.args.green, "drain")
            self.wait_and_seal(self.args.green)
            self.stop.set()
            for thread in self.threads:
                thread.join(65)
                require(not thread.is_alive(), "background_not_finished")
            self.healthy()
            self.unchanged_route()
            self.unchanged_backend(self.backends[self.args.blue], "ACTIVE")
            self.unchanged_backend(self.backends[self.args.green], "SEALED")
            require(self.counts["retained_completed"] == 1 and self.counts["autocommit_completed"] >= 1, "incomplete_workload")
            failed = False
        except Exception as error:
            self.emit("cutover_failed", kind=type(error).__name__, detail=str(error),
                      automatic_route_rollback=False, safe_to_scale=False)
        finally:
            self.stop.set()
            self.release.set()
            for thread in self.threads:
                thread.join(65)
            if self.tx.transaction != "NONE":
                try:
                    self.tx.query("ROLLBACK")
                    require(self.tx.transaction == "NONE", "cleanup_transaction_not_cleared")
                    self.emit("cleanup_rollback_completed")
                except Exception as error:
                    failed = True
                    self.emit("cleanup_rollback_failed", kind=type(error).__name__)
            try:
                final_route = self.admin.call(self.route_path)
                final_blue, final_green = self.status(self.args.blue), self.status(self.args.green)
                self.emit("final_state", route=final_route, blue=final_blue, green=final_green)
                if not failed:
                    require(final_route == self.route, "final_route_changed")
                    for name, observed, expected_state in ((self.args.blue, final_blue, "ACTIVE"),
                                                            (self.args.green, final_green, "SEALED")):
                        expected = self.backends[name]
                        for key in ("name", "incarnation", "nodeId", "coordinatorId", "generation", "state"):
                            require(observed[key] == expected[key], "final_backend_changed:" + key)
                        require(observed["state"] == expected_state, "final_backend_state_changed")
                    require(final_green["drained"] is True, "final_green_not_drained")
            except Exception as error:
                failed = True
                self.emit("final_state_validation_failed", kind=type(error).__name__, detail=str(error))
            self.emit("summary", passed=not failed, counts=dict(self.counts), client_retries=0)
        return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("server", "user", "catalog", "group", "blue", "green"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--drain-seconds", type=int, default=300)
    parser.add_argument("--allow-route-mutation", action="store_true")
    args = parser.parse_args()
    require(args.allow_route_mutation, "explicit_mutation_opt_in_required")
    require(args.blue != args.green and 30 <= args.drain_seconds <= 600, "invalid_bounds_or_backend_pair")
    admin = Admin(args.server, os.environ["TX_ADMIN_USER"], os.environ["TX_ADMIN_PASSWORD"], os.environ["TX_ADMIN_TOKEN"])
    password = os.environ["TX_TRINO_PASSWORD"]
    factory = lambda: RolloutClient(args.server, args.user, args.catalog, password, args.group)
    raise SystemExit(CutoverSmoke(args, admin, factory).run())


if __name__ == "__main__":
    main()
