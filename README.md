# Trino Gateway

A hostname-routing proxy for pooled [Trino](https://trino.io) compute.
Configured public hostnames select a pool; certified member lifecycle and
PostgreSQL-backed ownership control admission and transaction/query affinity.
Client routing-group headers, routing rules, cookies, and default-group fallback
do not select compute.

<img src="./docs/assets/logos/trino-gateway-h.png"/>

Find out more details from our documentation at 
[trinodb.github.io/trino-gateway](https://trinodb.github.io/trino-gateway)

See [hostname configuration](docs/routing-rules.md),
[routing logic](docs/routing-logic.md), and [operation](docs/operation.md).
The [transaction design](docs/design/transaction-awareness.md),
[operational boundaries](testing/transaction-awareness/OPERATIONS.md), and
[repeatable test suite](testing/transaction-awareness/README.md) describe
ownership and drain safety. These documents do not constitute a production
deployment or transaction replication: a lost coordinator's in-memory
transactions cannot migrate to another member.

<img src="./docs/assets/logos/trino-gateway-v.png"/>
