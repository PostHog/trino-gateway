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
