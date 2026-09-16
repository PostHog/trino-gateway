"""Retain unread executing results until an external private marker appears."""

import os
from pathlib import Path
import re
import stat
import time
from urllib.parse import urlsplit

from protocol import request
from rollout_client import RolloutFailure, http_failure, private_descriptor, request_failure


def retained_sql():
    return "SELECT n, CAST(n AS varchar) || rpad('x', 1024, 'x') FROM UNNEST(sequence(1, 10000)) AS t(n) ORDER BY n"


def retained_rows(count=10000, padding=1024):
    return [[number, str(number) + "x" * padding] for number in range(1, count + 1)]


def validate_rows(rows, padding=1024):
    for number, row in enumerate(rows, 1):
        if len(row) != 2 or type(row[0]) is not int or row != [number, str(number) + "x" * padding]:
            raise ValueError("invalid_retained_result_prefix")


def validate_retained_result(result):
    if result.get("retained_hold_outcome") != "released" or type(result.get("heartbeat_requests")) is not int or result["heartbeat_requests"] < 1:
        raise ValueError("retained_barrier_not_released")
    if len(result["rows"]) != 10000:
        raise ValueError("retained_row_count_mismatch")
    validate_rows(result["rows"])


class ReleaseMarker:
    def __init__(self, path):
        target = Path(path)
        self.name = target.name
        if self.name in ("", ".", ".."):
            raise ValueError("invalid_release_marker")
        self.directory = private_descriptor(str(target.parent), os.O_RDONLY, directory=True)
        try:
            os.stat(self.name, dir_fd=self.directory, follow_symlinks=False)
        except FileNotFoundError:
            return
        except BaseException:
            self.close()
            raise
        self.close()
        raise ValueError("release_marker_already_exists")

    def released(self):
        try:
            descriptor = os.open(self.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.directory)
        except FileNotFoundError:
            return False
        try:
            info = os.fstat(descriptor)
            if info.st_uid != os.getuid() or info.st_mode & 0o077 or not stat.S_ISREG(info.st_mode):
                raise ValueError("release_marker_must_be_private_and_owned")
            return True
        finally:
            os.close(descriptor)

    def close(self):
        os.close(self.directory)


class ExecutingResultHold:
    def __init__(self, marker, seconds, stop, event, ready, expected_rows=10000, padding=1024):
        self.marker, self.seconds, self.stop = marker, seconds, stop
        self.event, self.ready = event, ready
        self.expected_rows, self.padding = expected_rows, padding

    def __call__(self, client, method, uri, payload, rows, query_deadline):
        query_id, next_uri = payload["id"], payload["nextUri"]
        prefix = "/v1/statement/executing/" + query_id + "/"
        if method != "GET" or not urlsplit(uri).path.startswith(prefix) or not urlsplit(next_uri).path.startswith(prefix) or not rows:
            return None
        if not re.fullmatch(r"[0-9]{8}_[0-9]{6}_[0-9]{1,20}_[A-Za-z0-9]{5}", query_id):
            raise ValueError("invalid_retained_query_identity")
        validate_rows(rows, self.padding)
        if len(rows) >= self.expected_rows:
            return None
        client.validate_continuation(next_uri)
        if client.transaction != "NONE":
            raise ValueError("retained_hold_requires_autocommit")
        started = time.monotonic()
        deadline = min(query_deadline, started + self.seconds)
        self.event("retained_result_held", query_id=query_id, row_count=len(rows), expected_rows=self.expected_rows)
        self.ready()
        heartbeats, next_heartbeat = 0, started
        while True:
            if self.stop.is_set():
                outcome = "stopped"
                break
            now = time.monotonic()
            if now >= deadline:
                outcome = "timed_out"
                break
            if self.marker.released():
                outcome = "released" if time.monotonic() < deadline else "timed_out"
                break
            if now >= next_heartbeat:
                self.event("retained_heartbeat_started", query_id=query_id, heartbeat_requests=heartbeats + 1)
                try:
                    response = request(next_uri, "HEAD", headers=client.headers + [("X-Trino-Transaction-Id", "NONE")],
                                       timeout=min(10, deadline - now))
                except Exception as error:
                    raise RolloutFailure(request_failure(error), client.pending_continuation) from None
                if response.status != 200:
                    raise RolloutFailure(http_failure(response, "HEAD", 0), client.pending_continuation)
                if response.values("X-Trino-Started-Transaction-Id") or response.values("X-Trino-Clear-Transaction-Id") or response.body:
                    raise RolloutFailure("heartbeat_response_invalid", client.pending_continuation)
                heartbeats += 1
                self.event("retained_heartbeat_success", query_id=query_id, heartbeat_requests=heartbeats)
                next_heartbeat = time.monotonic() + 30
            self.stop.wait(min(.1, max(0, deadline - time.monotonic())))
        elapsed = time.monotonic() - started
        self.event("retained_hold_finished", query_id=query_id, outcome=outcome, held_seconds=elapsed,
                   heartbeat_requests=heartbeats)
        return {"retained_hold_outcome": outcome, "heartbeat_requests": heartbeats, "retained_hold_seconds": elapsed}
