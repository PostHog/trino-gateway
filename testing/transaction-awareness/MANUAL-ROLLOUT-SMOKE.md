# Manual deployment and cutover checks

These scripts exercise an operator-prepared development backend pair. They do
not implement Kargo promotion, publication, warehouse provisioning, roster
certification, Kubernetes scaling, or a deployment controller. Passing them does
not establish that an automated rollout performs those operations correctly.

Keep credentials, deployment-specific arguments, and all results outside this
public repository. Supply passwords through process environment variables; do
not put credentials in command-line arguments or shell history.

## Gateway restart workload

`rollout_workload.py` starts three read-only transactions, retains a paginated
result, and issues concurrent autocommit queries. Set `TX_TRINO_PASSWORD` and
pass `--server`, `--user`, `--catalog`, and `--group`. Wait for the JSON event
`workload_ready_for_rollout` before independently initiating a Gateway restart.
Check that all intended Gateway replicas were replaced while the transactions
were open. The client does not identify which replica handles each request.

### Failed continuations

The client preserves the exact last validated continuation in memory when a
request fails. It does not retry requests, invent continuation tokens, replay
statement POSTs, or change a failed run into a pass. Failure output contains an
opaque recovery identifier and bounded HTTP classifications, not response bodies,
credentials, SQL, or continuation capabilities. An unsuccessful initial POST has
no recovery handle unless the client actually received and validated a next URI.

Handles disappear when the process exits unless the operator explicitly passes
`--recovery-directory` to either rollout script. Create an owned mode-0700 directory
outside this repository first. The scripts create unique mode-0600 JSON files
there and never overwrite an existing file. These files contain sensitive next-URI
capabilities and transaction identifiers, but no passwords, Authorization headers,
SQL text, or result rows. Do not commit or share them. Remove them after recovery.

For an autocommit query, explicitly resume its original GET with:

```sh
python3 rollout_client.py --server "$GATEWAY_URL" --user "$TRINO_USER" \
  --catalog "$TRINO_CATALOG" --group "$ROUTING_GROUP" \
  --resume-file "$PRIVATE_RECOVERY_FILE"
```

Supply the same server, user, catalog, and routing group as the failed run. The
client prompts for a password again. A resumed result contains only rows obtained
after the saved handle, plus a count of earlier rows. It does not reconstruct or
revalidate the full original result. The original benchmark failure remains a
failure even if resumption succeeds. Retrying the same saved handle later can
repeat result pages; this is not an exactly-once result consumer.

Transaction handles can resume only in memory on a client that still holds the
same transaction identity. The CLI cannot recreate that session from a file.
Workload cleanup attempts ROLLBACK after failures, which can invalidate transaction
continuations. Expired Trino results, a lost coordinator, or a missing initial
response may make recovery impossible. These helpers do not clear Gateway ledger
records or certify that an abandoned query has stopped executing.

## Manual fenced cutover

Before running `manual_cutover_smoke.py`, independently verify:

- Catalog writers are paused and no catalog mutation is in flight.
- Both colors share the intended catalog store and expose the complete admitted
  warehouse roster. Both coordinators and their workers are ready.
- Green has sufficient capacity and both backend incarnations are registered
  as `ACTIVE` with current process identities.
- The durable route points to blue. No deployment controller or other operator
  will mutate the route, backend state, or coordinator processes during this test.
- No active Gateway rollout operation owns the routing group.

Set `TX_TRINO_PASSWORD`, `TX_ADMIN_USER`, `TX_ADMIN_PASSWORD`, and `TX_ADMIN_TOKEN`.
Pass `--server`, `--user`, `--catalog`, `--group`, `--blue`, `--green`, and
`--allow-route-mutation`. There are no deployment-specific defaults.

The script opens a transaction and retains a result on blue, switches new
queries to green with route-generation and incarnation fences, and verifies
that old work remains on blue. It checks that sealing fails while work remains,
finishes the transaction, drains and seals blue, resumes the same blue process,
and switches new queries back to blue. It then drains and seals green. Each
drain wait defaults to 300 seconds to allow terminal-result retention to expire.
The intended final state is blue routed and active, green sealed. The script
does not scale green down.

Requests are not retried. A failed or ambiguous administrative operation stops
further administrative mutations. The script attempts transaction rollback and
reads final state, but never automatically changes the route to undo a failure.
Do not scale or delete either coordinator after a failure without inspecting its
current state and outstanding work. A successful run tests a manual API sequence,
not the unfinished Kargo integration.

Run local unit checks with:

```sh
python3 -m unittest -v test_rollout_client test_manual_cutover_smoke
```
