"""Disposable two-Gateway/shared-PostgreSQL fixture for pooled HTTP contracts."""

import contextlib
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time

from fake_trino import make_server
from protocol import request


def unused_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def stop_process(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def preserve_logs(root):
    """Keep diagnostics only in an explicitly supplied private directory outside this repository."""
    destination = os.environ.get("GATEWAY_TEST_LOG_DIR")
    if not destination:
        return
    destination = Path(destination).resolve()
    repository = Path(__file__).resolve().parents[2]
    if destination == repository or repository in destination.parents:
        raise ValueError("Gateway diagnostic logs must remain outside the repository")
    destination = destination / root.name
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    for log in root.glob("*.log"):
        shutil.copyfile(log, destination / log.name)


@contextlib.contextmanager
def local_gateways(form_auth=False, processes=None, server_config=None, pool=None, backend_names=("blue", "green"), database=None):
    """Start independent replicas with hostname-only placement and mandatory pool admission.

    Registry entries are active but are not pooled members until explicitly bootstrapped.
    URLs use localhost so the automatically emitted Host contains a mapped DNS name and port.
    """
    pg_bin = Path(os.environ["GATEWAY_TEST_PG_BIN"])
    with tempfile.TemporaryDirectory(prefix="gateway-pool-contract-") as directory, contextlib.ExitStack() as cleanup:
        root = Path(directory)
        cleanup.callback(preserve_logs, root)
        pg_port = unused_port()
        subprocess.run([str(pg_bin / "initdb"), "-D", str(root / "pg"), "-U", "gateway_test", "-A", "trust", "--no-locale"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([str(pg_bin / "pg_ctl"), "-D", str(root / "pg"), "-l", str(root / "postgres.log"), "-o", f"-h 127.0.0.1 -p {pg_port} -k {directory}", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
        cleanup.callback(subprocess.run, [str(pg_bin / "pg_ctl"), "-D", str(root / "pg"), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
        if database is not None:
            database["psql"] = [str(pg_bin / "psql"), "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                "-h", "127.0.0.1", "-p", str(pg_port), "-U", "gateway_test", "postgres"]
        token = secrets.token_hex(32)
        key = secrets.token_hex(32)
        backends = []
        for name in backend_names:
            backend = make_server(identity=name)
            threading.Thread(target=backend.serve_forever, daemon=True).start()
            cleanup.callback(backend.server_close)
            cleanup.callback(backend.shutdown)
            backends.append(backend)
        gateways = []
        for index in range(2):
            port = unused_port()
            endpoint = f"http://localhost:{port}"
            gateways.append(endpoint)
            config = {
                "serverConfig": {"node.environment": "test", "node.bind-ip": "127.0.0.1",
                                 "http-server.http.port": str(port), "http-server.process-forwarded": "ignore"},
                "dataStore": {"jdbcUrl": f"jdbc:postgresql://127.0.0.1:{pg_port}/postgres", "user": "gateway_test", "password": "unused", "driver": "org.postgresql.Driver"},
                "monitor": {"taskDelay": "1s"},
                "routing": {"hostnameToPool": {"localhost": "cell"}},
                "transactionAwareness": {"enabled": True, "identityKey": key, "adminToken": token, "terminalRetentionSeconds": 1, "pool": {"enabled": True}},
            }
            if pool is not None:
                config["transactionAwareness"]["pool"] = pool
            config["serverConfig"].update(server_config or {})
            if form_auth:
                auth = Path(__file__).resolve().parents[2] / "gateway-ha/src/test/resources/auth"
                config.update({
                    "presetUsers": {"rollout": {"password": token, "privileges": "API"}, "ordinary": {"password": token, "privileges": "USER"}, "fixture-admin": {"password": token, "privileges": "ADMIN_USER_API"}},
                    "authentication": {"defaultType": "form", "form": {"selfSignKeyPair": {"privateKey": str(auth / "test_ec_private_key.pem"), "publicKey": str(auth / "test_ec_public_key.pem")}}},
                    "authorization": {"admin": "(.*)ADMIN(.*)", "api": "(.*)API(.*)", "user": "(.*)USER(.*)"},
                })
            config_file = root / f"gateway-{index}.json"
            config_file.write_text(json.dumps(config))
            logfile = cleanup.enter_context((root / f"gateway-{index}.log").open("wb"))
            process = subprocess.Popen([os.environ["GATEWAY_TEST_JAVA"], "-Xmx384m", "-cp", os.environ["GATEWAY_TEST_CLASSPATH"], "io.trino.gateway.ha.HaGatewayLauncher", str(config_file)], stdout=logfile, stderr=subprocess.STDOUT)
            if processes is not None:
                processes.append(process)
            cleanup.callback(stop_process, process)
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None or time.monotonic() > deadline:
                    raise AssertionError(f"Isolated Gateway replica {index} did not become ready; exit={process.poll()}")
                try:
                    if request(endpoint + "/trino-gateway/readyz", timeout=1).status == 200:
                        break
                except (OSError, TimeoutError):
                    pass
                time.sleep(0.1)
            if index == 0:
                for backend in backends:
                    url = f"http://127.0.0.1:{backend.server_port}"
                    body = {"name": backend.state.identity, "proxyTo": url, "externalUrl": url, "active": True, "routingGroup": "cell"}
                    headers = [("Content-Type", "application/json")]
                    if form_auth:
                        headers.append(("Authorization", "Basic " + base64.b64encode(("fixture-admin:" + token).encode()).decode()))
                    response = request(endpoint + "/entity?entityType=GATEWAY_BACKEND", "POST", json.dumps(body), headers)
                    if response.status != 200:
                        raise AssertionError("Isolated backend registration failed")
        time.sleep(2)
        yield gateways, backends, token


def pool_request(gateways, token, path="", method="GET", body=None, replica=0):
    return request(gateways[replica] + "/gateway/v1/pools/cell" + path, method,
                   None if body is None else json.dumps(body),
                   [("Authorization", "Bearer " + token), ("Content-Type", "application/json")])


def bootstrap_pool(gateways, backends, token):
    """Configure and certify every fixture backend through the public pooled protocol."""
    def successful(response):
        if response.status != 200:
            raise AssertionError(f"Pool bootstrap failed: {response.status} {response.body!r}")
        return response.json()

    guard = {"controllerEpoch": 1, "ownerIdentity": "fixture-controller"}
    successful(pool_request(gateways, token, method="PUT", body={
        **guard, "operationId": "fixture-configure", "stepId": "configure",
        "apiMode": "POOLED", "minServing": 1, "desiredMembers": len(backends),
        "maxSurge": 1, "maxRepair": 1, "desiredRevision": "r-1",
        "tenantAdmissionEnabled": False}))
    members = {}
    for index, backend in enumerate(backends):
        name = backend.state.identity
        url = f"http://127.0.0.1:{backend.server_port}"
        registered = successful(pool_request(gateways, token, "/members", "POST", {
            **guard, "operationId": "fixture-boot-" + name, "stepId": "register",
            "instanceId": name, "backendName": name, "url": url, "externalUrl": url,
            "podUid": "pod-" + name, "bootId": "boot-" + name, "configRevision": "r-1"},
            replica=index % 2))
        members[name] = successful(pool_request(gateways, token, "/members/" + name + "/admit", "POST", {
            **guard, "operationId": "fixture-boot-" + name, "stepId": "admit",
            "expectedGeneration": registered["generation"],
            "receipt": {"certificateHash": "a" * 64, "configRevision": "r-1", "authRevision": "auth-1",
                        "podUid": "pod-" + name, "bootId": "boot-" + name,
                        "nodeId": backend.state.node_id, "coordinatorId": backend.state.coordinator_id,
                        "readyWorkers": 3, "checks": ["image", "workers", "catalog-revision", "auth-revision"]}},
            replica=(index + 1) % 2))
    return members
