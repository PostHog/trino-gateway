# Pool selection

Gateway has one routing path. A configured hostname selects a pool. New queries
use a randomized list of its eligible members. Cached health orders that list;
it does not grant admission. PostgreSQL checks membership and lifecycle state
before dispatch.

Existing queries and transactions stay on their recorded coordinator. Unknown
ownership is rejected. A failed dispatched request is not replayed elsewhere.

The header, rules-engine, external-router, default-group, random legacy router,
and query-count router implementations have been removed. Custom modules cannot
replace pool admission with a backend selector.

See [Hostname routing](routing-rules.md) for configuration. See
[Routing logic](routing-logic.md) for ownership and failure boundaries.
