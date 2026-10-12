# Quick start

This fork requires hostname-selected pools and PostgreSQL. Do not use an
upstream Gateway jar with this configuration. Stock coordinators without the
pool process-identity contract cannot be admitted.

For a reproducible local test, build this checkout with Java 25. See the
[pooled fixture guide](../testing/transaction-awareness/README.md). Its HTTP
contracts start disposable PostgreSQL, independent Gateway processes, and
synthetic coordinators. It does not validate a real coordinator deployment.

For a compatible isolated deployment:

1. Pin compatible Gateway and coordinator artifacts.
2. Generate separate shared identity and administration keys outside the repo.
3. Configure exact host or one-label suffix mappings to pool identifiers.
4. Start Gateway with [the sample config](config.yaml) and runtime keys.
5. Register backend metadata. Use the fenced pool API to configure the pool,
   register each process, validate its readiness, and certify it for admission.
6. Connect with the customer hostname and supported Basic credentials.

Adding an active backend record alone does not make it routable. Routing-group
headers, cookies, rules engines, and default-group fallbacks are not supported.
An unmapped hostname fails without backend dispatch.

See [Hostname routing](routing-rules.md), [Operation](operation.md), and
[the pool contract](../testing/transaction-awareness/OPERATIONS.md). Do not use
a disposable lab as a production template. Keep credentials and execution
receipts private.
