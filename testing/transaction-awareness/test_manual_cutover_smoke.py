"""Verify manual cutover ordering and conservative handling of uncertain writes."""

import contextlib
import io
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from manual_cutover_smoke import Admin, CutoverSmoke
from protocol import Response


class Fixture:
    def __init__(self, ambiguous=False, final_mutation=None, wrong_owner=False, changed_generation=False):
        self.ambiguous = ambiguous
        self.final_mutation = final_mutation
        self.wrong_owner = wrong_owner
        self.changed_generation = changed_generation
        self.sealed_green_reads = 0
        self.green_reads = 0
        self.open = False
        self.writes = []
        self.route = {"routingGroup": "group", "generation": 1, "backendName": "blue", "backendIncarnation": "blue-inc"}
        self.states = {name: {"name": name, "state": "ACTIVE", "incarnation": name + "-inc", "generation": 0,
                              "nodeId": name + "-node", "coordinatorId": name, "openTransactions": 0,
                              "readyToSeal": False, "drained": False} for name in ("blue", "green")}

    def require_no_active_rollout(self, group):
        assert group == "group"

    def call(self, path, method="GET", body=None, expected=200, error_contains=None):
        if method == "GET" and self.sealed_green_reads and self.final_mutation:
            target, key, value = self.final_mutation
            if target == "route":
                self.route[key] = value
            else:
                self.states[target][key] = value
            self.final_mutation = None
        if method != "GET":
            self.writes.append((path, body))
        if path.startswith("/routes/"):
            if method == "PUT":
                assert body["expectedGeneration"] == self.route["generation"]
                assert body["expectedBackendName"] == self.route["backendName"]
                self.route.update(generation=self.route["generation"] + 1, backendName=body["backendName"],
                                  backendIncarnation=body["backendIncarnation"])
                if self.ambiguous is True or self.ambiguous == "route":
                    raise TimeoutError("simulated_lost_response_after_commit")
            return dict(self.route)
        _, _, name, action = path.split("/")
        state = self.states[name]
        if method == "GET" and name == "green":
            self.green_reads += 1
            if state["state"] == "SEALED":
                self.sealed_green_reads += 1
            if self.changed_generation and self.green_reads == 2:
                state["generation"] += 1
        if method != "GET":
            if action == "seal" and self.open and name == "blue":
                assert expected == 409
                public = Response(409, [], b"Transaction routing state rejected the operation: NOT_DRAINED")
                with patch("manual_cutover_smoke.request", return_value=public):
                    return Admin("https://gateway.example", "operator", "password", "token").call(
                        path, method, body, expected, error_contains)
            assert expected == 200
            state["state"] = {"drain": "DRAINING", "seal": "SEALED", "resume": "ACTIVE"}[action]
            state["generation"] += 1
            if self.ambiguous == action:
                raise TimeoutError("simulated_lost_response_after_commit")
        state["openTransactions"] = int(self.open and name == "blue")
        state["readyToSeal"] = state["state"] == "DRAINING" and not state["openTransactions"]
        state["drained"] = state["state"] == "SEALED"
        return dict(state)

    def client(self):
        fixture = self

        class Client:
            transaction = "NONE"

            def query(self, sql, **kwargs):
                backend = "blue" if self.transaction != "NONE" else fixture.route["backendName"]
                if fixture.wrong_owner:
                    backend = "unknown"
                rows = []
                if sql.startswith("START"):
                    self.transaction = "transaction"
                    fixture.open = True
                elif sql in ("COMMIT", "ROLLBACK"):
                    self.transaction = "NONE"
                    fixture.open = False
                elif sql == "SELECT 1":
                    rows = [[1]]
                elif "information_schema" in sql:
                    rows = [[True]]
                else:
                    kwargs["first_page_callback"]()
                    rows = [[n] for n in range(1, 10001)]
                return {"query_id": "query_" + backend, "rows": rows, "pages": 2}

        return Client()


class ManualCutoverTest(unittest.TestCase):
    def run_fixture(self, fixture):
        args = SimpleNamespace(group="group", blue="blue", green="green", drain_seconds=30)
        with contextlib.redirect_stdout(io.StringIO()):
            return CutoverSmoke(args, fixture, fixture.client).run()

    def test_roundtrip_drains_then_seals_and_returns_to_blue(self):
        fixture = Fixture()
        self.assertEqual(self.run_fixture(fixture), 0)
        self.assertEqual(fixture.route["backendName"], "blue")
        self.assertEqual(fixture.states["blue"]["state"], "ACTIVE")
        self.assertEqual(fixture.states["green"]["state"], "SEALED")
        self.assertFalse(fixture.open)
        actions = [path for path, _ in fixture.writes]
        self.assertEqual(actions, ["/routes/group", "/backends/blue/drain", "/backends/blue/seal",
                                  "/backends/blue/seal", "/backends/blue/resume", "/routes/group",
                                  "/backends/green/drain", "/backends/green/seal"])

    def test_uncertain_route_write_stops_all_administrative_mutations(self):
        fixture = Fixture(ambiguous=True)
        self.assertEqual(self.run_fixture(fixture), 1)
        self.assertEqual(fixture.route["backendName"], "green")
        self.assertEqual(len(fixture.writes), 1)
        self.assertFalse(fixture.open)
        self.assertEqual(fixture.states["blue"]["state"], "ACTIVE")

    def test_external_route_change_fails_before_mutation(self):
        fixture = Fixture()
        fixture.route["backendName"] = "green"
        self.assertEqual(self.run_fixture(fixture), 1)
        self.assertEqual(fixture.writes, [])

    def test_changed_final_snapshot_cannot_report_success(self):
        for mutation in (("route", "generation", 99), ("blue", "generation", 99),
                         ("green", "incarnation", "unexpected"), ("green", "state", "ACTIVE")):
            with self.subTest(mutation=mutation):
                fixture = Fixture(final_mutation=mutation)
                self.assertEqual(self.run_fixture(fixture), 1)

    def test_uncertain_backend_writes_stop_all_further_mutations(self):
        for action in ("drain", "seal", "resume"):
            with self.subTest(action=action):
                fixture = Fixture(ambiguous=action)
                self.assertEqual(self.run_fixture(fixture), 1)
                self.assertEqual(fixture.writes[-1][0], "/backends/blue/" + action)
                self.assertEqual(fixture.route["backendName"], "green")
                self.assertFalse(fixture.open)

    def test_wrong_query_owner_stops_before_route_mutation(self):
        fixture = Fixture(wrong_owner=True)
        self.assertEqual(self.run_fixture(fixture), 1)
        self.assertEqual(fixture.writes, [])
        self.assertFalse(fixture.open)

    def test_changed_backend_generation_stops_before_route_mutation(self):
        fixture = Fixture(changed_generation=True)
        self.assertEqual(self.run_fixture(fixture), 1)
        self.assertEqual(fixture.writes, [])
        self.assertFalse(fixture.open)

    def test_public_not_drained_response_is_accepted_but_other_conflicts_are_not(self):
        admin = Admin("https://gateway.example", "operator", "password", "token")
        for code in ("NOT_DRAINED", "STALE_GENERATION", "CONFLICT"):
            with self.subTest(code=code):
                response = Response(409, [], ("Transaction routing state rejected the operation: " + code).encode())
                with patch("manual_cutover_smoke.request", return_value=response):
                    def call():
                        return admin.call("/backends/blue/seal", "POST", {"generation": 1}, expected=409,
                                          error_contains="Transaction routing state rejected the operation: NOT_DRAINED")
                    if code == "NOT_DRAINED":
                        self.assertIsNone(call())
                    else:
                        with self.assertRaisesRegex(RuntimeError, "unexpected_administrative_rejection"):
                            call()


if __name__ == "__main__":
    unittest.main()
