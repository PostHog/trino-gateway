# Trino Gateway

A load balancer, proxy server, and configurable routing gateway for multiple
[Trino](https://trino.io) clusters.

<img src="./docs/assets/logos/trino-gateway-h.png"/>

Find out more details from our documentation at
[trinodb.github.io/trino-gateway](https://trinodb.github.io/trino-gateway)

This fork retains official upstream functionality and its default behavior.
Both `transactionAwareness.enabled` and `transactionAwareness.pool.enabled`
default to `false`. Setting both to `true` enables our strict
[hostname-only pooled deployment](docs/pooled-routing.md): stock header/rule/
cookie selectors and default-group fallback remain in the codebase but are
bypassed. They cannot select compute or provide a fallback in pooled mode.

See the [transaction design](docs/design/transaction-awareness.md),
[operational boundaries](testing/transaction-awareness/OPERATIONS.md), and
[repeatable test suite](testing/transaction-awareness/README.md). This work does
not constitute a production deployment or transaction replication. Custom
fixed-backend cutover and rollout administration APIs remain removed.

<img src="./docs/assets/logos/trino-gateway-v.png"/>
