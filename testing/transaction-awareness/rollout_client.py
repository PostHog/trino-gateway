"""Bounded read-only rollout probes with no automatic request retries."""

import argparse
import base64
import getpass
import json
import re
import time
from urllib.parse import urlsplit

from protocol import request


def http_failure(result, method, page):
    body = result.body[:4096].decode("utf-8", errors="replace").lower()
    markers = {
        "transaction_capacity": ("transaction-aware request capacity", "transaction request capacity", "transaction request limit"),
        "backend_identity": ("ready trino coordinator process identity", "backend process identity is unavailable"),
        "routing_state": ("transaction routing state is unavailable", "no backend belongs to the selected routing group",
                          "backend does not accept new", "route target does not accept new statements"),
        "shutdown": ("shutting down", "server is stopping", "server shutdown", "service unavailable: shutdown"),
    }
    categories = [name for name, phrases in markers.items() if any(phrase in body for phrase in phrases)]
    headers = {}
    known_codes = {"CAPACITY_EXHAUSTED": "transaction_capacity", "GATEWAY_STOPPING": "shutdown",
                   "ROUTING_STATE_UNAVAILABLE": "routing_state", "ROUTING_STATE_NOT_ACTIVE": "routing_state"}
    codes = result.values("X-Trino-Gateway-Error")
    if len(codes) == 1 and codes[0] in known_codes:
        headers["X-Trino-Gateway-Error"] = codes[0]
        if known_codes[codes[0]] not in categories:
            categories.append(known_codes[codes[0]])
    for name in ("Retry-After", "Content-Type", "Server"):
        values = result.values(name)
        if values:
            value = values[0]
            headers[name] = value if len(value) <= 100 and re.fullmatch(r"[A-Za-z0-9 ._;/=+()-]+", value) else "redacted"
    return "http_status:" + str(result.status) + ":" + json.dumps({
        "method": method, "page": page, "headers": headers, "classification": categories or ["unknown"]}, sort_keys=True)


class RolloutClient:
    def __init__(self, server, user, catalog, password, group=None):
        self.server = server.rstrip("/")
        self.origin = urlsplit(self.server)
        if self.origin.scheme != "https" or self.origin.username or self.origin.password:
            raise ValueError("Require HTTPS without credentials in the server URL")
        authorization = base64.b64encode((user + ":" + password).encode()).decode()
        self.headers = [("Authorization", "Basic " + authorization), ("X-Trino-User", user),
                        ("X-Trino-Catalog", catalog), ("Content-Type", "text/plain")]
        if group:
            self.headers.append(("X-Trino-Routing-Group", group))
        self.transaction = "NONE"

    def query(self, sql, deadline_seconds=60, max_pages=500, first_page_pause=0, first_page_callback=None):
        started = time.monotonic()
        deadline = started + deadline_seconds
        url, method, body = self.server + "/v1/statement", "POST", sql
        identity, rows, started_ids = None, [], set()
        for page in range(max_pages):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("query_deadline")
            result = request(url, method, body, headers=self.headers + [
                ("X-Trino-Transaction-Id", self.transaction)], timeout=min(30, remaining))
            if result.status != 200:
                raise RuntimeError(http_failure(result, method, page))
            payload = result.json()
            if identity is not None and payload.get("id") != identity:
                raise RuntimeError("query_identity_changed")
            identity = payload.get("id")
            if not identity:
                raise RuntimeError("missing_query_identity")
            started_ids.update(result.values("X-Trino-Started-Transaction-Id"))
            if len(started_ids) > 1:
                raise RuntimeError("conflicting_transaction_identity")
            if started_ids:
                if self.transaction != "NONE" and started_ids != {self.transaction}:
                    raise RuntimeError("transaction_identity_changed")
                self.transaction = next(iter(started_ids))
            if result.values("X-Trino-Clear-Transaction-Id"):
                self.transaction = "NONE"
            if "error" in payload:
                raise RuntimeError("query_error:" + payload["error"].get("errorName", "UNKNOWN"))
            rows.extend(payload.get("data", []))
            if not payload.get("nextUri"):
                return {"rows": rows, "pages": page + 1, "query_id": identity,
                        "duration_seconds": time.monotonic() - started}
            if page == 0 and first_page_pause:
                if first_page_callback:
                    first_page_callback()
                time.sleep(first_page_pause)
            target = urlsplit(payload["nextUri"])
            if (target.scheme, target.hostname, target.port) != (
                    self.origin.scheme, self.origin.hostname, self.origin.port):
                raise RuntimeError("continuation_origin")
            url, method, body = payload["nextUri"], "GET", None
        raise RuntimeError("query_page_limit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--group")
    args = parser.parse_args()
    client = RolloutClient(args.server, args.user, args.catalog, getpass.getpass("Trino password: "), args.group)
    probes = [("constant", "SELECT 1", [[1]]),
              ("catalog_metadata", "SELECT count(*) >= 0 FROM information_schema.tables", [[True]]),
              ("transaction_begin", "START TRANSACTION READ ONLY", None),
              ("transaction_constant", "SELECT 42", [[42]]),
              ("transaction_catalog", "SELECT count(*) >= 0 FROM information_schema.tables", [[True]]),
              ("transaction_commit", "COMMIT", None),
              ("rollback_begin", "START TRANSACTION READ ONLY", None),
              ("transaction_rollback", "ROLLBACK", None)]
    failed = False
    try:
        for label, sql, expected in probes:
            result = client.query(sql)
            if expected is not None and result["rows"] != expected:
                raise RuntimeError("result_mismatch:" + label)
            print(json.dumps({"probe": label, "status": "PASS", "rows": len(result["rows"]),
                              "pages": result["pages"], "duration_seconds": result["duration_seconds"],
                              "query_owner": result["query_id"].rsplit("_", 1)[-1],
                              "transaction_open": client.transaction != "NONE", "client_retries": 0}), flush=True)
    except Exception as error:
        failed = True
        print(json.dumps({"status": "FAIL", "kind": type(error).__name__, "error": str(error)}), flush=True)
    finally:
        if client.transaction != "NONE":
            try:
                client.query("ROLLBACK")
                print(json.dumps({"cleanup": "rollback_pass"}), flush=True)
            except Exception as error:
                print(json.dumps({"cleanup": "rollback_failed", "kind": type(error).__name__}), flush=True)
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
