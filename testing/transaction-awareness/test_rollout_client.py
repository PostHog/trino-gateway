"""Protocol and reporting checks for a credential-safe rollout client."""

import unittest
from unittest.mock import patch

from protocol import Response
from rollout_client import RolloutClient


def response(body, headers=(), status=200):
    import json
    return Response(status, list(headers), json.dumps(body).encode())


class RolloutClientTest(unittest.TestCase):
    def client(self):
        return RolloutClient("https://gateway.example", "reader", "catalog", "password", "group")

    @patch("rollout_client.request")
    def test_pages_keep_credentials_and_collect_actual_rows(self, request):
        request.side_effect = [response({"id": "q_owner", "nextUri": "https://gateway.example/page"}),
                               response({"id": "q_owner", "data": [[1]]})]
        result = self.client().query("SELECT 1")
        self.assertEqual(result["rows"], [[1]])
        self.assertEqual(result["pages"], 2)
        self.assertIn("Authorization", dict(request.call_args.kwargs["headers"]))

    @patch("rollout_client.request")
    def test_cross_origin_continuation_is_never_sent_credentials(self, request):
        request.return_value = response({"id": "q_owner", "nextUri": "https://other.example/page"})
        with self.assertRaisesRegex(RuntimeError, "continuation_origin"):
            self.client().query("SELECT 1")
        self.assertEqual(request.call_count, 1)

    @patch("rollout_client.request")
    def test_transaction_headers_are_preserved(self, request):
        request.side_effect = [response({"id": "q_owner"}, [("X-Trino-Started-Transaction-Id", "tx")]),
                               response({"id": "q_owner"}, [("X-Trino-Clear-Transaction-Id", "true")])]
        client = self.client()
        client.query("START TRANSACTION READ ONLY")
        client.query("COMMIT")
        self.assertEqual(dict(request.call_args.kwargs["headers"])["X-Trino-Transaction-Id"], "tx")
        self.assertEqual(client.transaction, "NONE")

    @patch("rollout_client.request")
    def test_http_200_query_error_is_failure_and_not_retried(self, request):
        request.return_value = response({"id": "q_owner", "error": {"errorName": "NO_TABLE", "message": "private"}})
        with self.assertRaisesRegex(RuntimeError, "query_error:NO_TABLE"):
            self.client().query("SELECT 1")
        self.assertEqual(request.call_count, 1)

    @patch("rollout_client.request")
    def test_continuation_query_identity_cannot_change(self, request):
        request.side_effect = [response({"id": "q_owner", "nextUri": "https://gateway.example/page"}),
                               response({"id": "other_owner", "data": [[1]]})]
        with self.assertRaisesRegex(RuntimeError, "query_identity_changed"):
            self.client().query("SELECT 1")

    @patch("rollout_client.request")
    def test_existing_transaction_identity_cannot_change(self, request):
        request.return_value = response({"id": "q_owner"}, [("X-Trino-Started-Transaction-Id", "new-tx")])
        client = self.client()
        client.transaction = "existing-tx"
        with self.assertRaisesRegex(RuntimeError, "transaction_identity_changed"):
            client.query("SELECT 1")
        self.assertEqual(client.transaction, "existing-tx")
        self.assertEqual(request.call_count, 1)

    @patch("rollout_client.time.sleep")
    @patch("rollout_client.request")
    def test_retained_callback_requires_a_continuation(self, request, sleep):
        called = []
        request.side_effect = [response({"id": "q_owner", "nextUri": "https://gateway.example/page"}),
                               response({"id": "q_owner", "data": [[1]]})]
        self.client().query("SELECT 1", first_page_pause=1, first_page_callback=lambda: called.append(True))
        self.assertEqual(called, [True])
        sleep.assert_called_once_with(1)

    @patch("rollout_client.request")
    def test_http_diagnostics_are_bounded_and_do_not_echo_body(self, request):
        request.return_value = Response(503, [("Retry-After", "1"), ("Content-Type", "application/json"),
                                             ("Server", "gateway")],
                                       b'Transaction routing state is unavailable; private-password https://private.example')
        with self.assertRaises(RuntimeError) as failure:
            self.client().query("SELECT private_catalog")
        message = str(failure.exception)
        self.assertIn('"classification": ["routing_state"]', message)
        self.assertIn('"method": "POST"', message)
        self.assertIn('"Retry-After": "1"', message)
        self.assertNotIn("private", message)
        self.assertEqual(request.call_count, 1)

    @patch("rollout_client.request")
    def test_http_diagnostics_redact_unbounded_headers(self, request):
        request.return_value = Response(503, [("Server", "x" * 101)], b"unclassified internal diagnostic")
        with self.assertRaisesRegex(RuntimeError, '"Server": "redacted"'):
            self.client().query("SELECT 1")

    @patch("rollout_client.request")
    def test_shutdown_error_code_is_captured_without_body(self, request):
        request.return_value = Response(503, [("X-Trino-Gateway-Error", "GATEWAY_STOPPING")], b"private details")
        with self.assertRaises(RuntimeError) as failure:
            self.client().query("SELECT 1")
        message = str(failure.exception)
        self.assertIn('"classification": ["shutdown"]', message)
        self.assertIn('"X-Trino-Gateway-Error": "GATEWAY_STOPPING"', message)
        self.assertNotIn("private", message)

    @patch("rollout_client.request")
    def test_unknown_gateway_error_code_is_not_echoed(self, request):
        request.return_value = Response(503, [("X-Trino-Gateway-Error", "private-backend")], b"private details")
        with self.assertRaises(RuntimeError) as failure:
            self.client().query("SELECT 1")
        self.assertNotIn("private", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
